import { DurableObject } from "cloudflare:workers";

/**
 * DroidLens 错误报告中转。
 *
 *   POST /v1/report  ← 插件发来的（已在插件里脱敏的）报告
 *   → Registry（唯一一个 Durable Object，串行处理）
 *   → 指纹第一次出现：在 GitHub 开 issue（标签 auto-report）
 *     已有 issue：累计次数；新版本或距上次评论超过一天才追加评论，免得刷屏
 *
 * 插件里已经脱敏过，这里再兜底擦一遍 URL / 邮箱 / IP / 路径 —— issue 是公开的。
 */

interface Env {
	GITHUB_REPO: string;
	GITHUB_TOKEN: string;
	LIMITER: RateLimit;
	REGISTRY: DurableObjectNamespace<Registry>;
}

interface Report {
	source: "sdk" | "ide";
	fingerprint: string;
	where: string;
	exception: string;
	message: string;
	stack: string;
	plugin: string;
	ide: string;
	os: string;
	feature?: string;
	count?: number;
	sdk?: string;
	api?: number;
	note?: string;
}

const MAX_BODY = 32 * 1024;
/** 一天最多新开这么多 issue；超过的只计数，第二天再说（防止被刷爆仓库）。 */
const MAX_NEW_ISSUES_PER_DAY = 20;
const COMMENT_INTERVAL_MS = 24 * 3600 * 1000;

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const url = new URL(request.url);
		if (url.pathname !== "/v1/report") return json({ error: "not found" }, 404);
		if (request.method !== "POST") return json({ error: "method not allowed" }, 405);

		const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
		const { success } = await env.LIMITER.limit({ key: ip });
		if (!success) return json({ error: "rate limited" }, 429);

		const text = await request.text();
		if (text.length > MAX_BODY) return json({ error: "too large" }, 413);
		let report: Report;
		try {
			report = validate(JSON.parse(text));
		} catch (e) {
			return json({ error: `bad report: ${(e as Error).message}` }, 400);
		}

		const registry = env.REGISTRY.get(env.REGISTRY.idFromName("registry"));
		const result = await registry.submit(report);
		return json(result, result.error ? 502 : 200);
	},
} satisfies ExportedHandler<Env>;

export class Registry extends DurableObject<Env> {
	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS issues (
			fingerprint TEXT PRIMARY KEY,
			number INTEGER NOT NULL,
			url TEXT NOT NULL,
			count INTEGER NOT NULL,
			versions TEXT NOT NULL,
			last_comment INTEGER NOT NULL
		)`);
		ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS daily (day TEXT PRIMARY KEY, created INTEGER NOT NULL)`);
	}

	async submit(r: Report): Promise<{ url?: string; issue?: number; error?: string; queued?: boolean }> {
		const version = r.source === "sdk" ? `SDK ${r.sdk ?? "?"} / plugin ${r.plugin}` : `plugin ${r.plugin}`;
		const count = Math.max(1, Math.min(r.count ?? 1, 1_000_000));
		const sql = this.ctx.storage.sql;
		const existing = sql
			.exec<{ number: number; url: string; count: number; versions: string; last_comment: number }>(
				"SELECT number, url, count, versions, last_comment FROM issues WHERE fingerprint = ?",
				r.fingerprint,
			)
			.toArray()[0];

		if (existing) {
			const versions: string[] = JSON.parse(existing.versions);
			const newVersion = !versions.includes(version);
			if (newVersion) versions.push(version);
			const now = Date.now();
			let lastComment = existing.last_comment;
			if (newVersion || now - existing.last_comment > COMMENT_INTERVAL_MS) {
				const body = `Seen again${newVersion ? " in a new version" : ""}: **${version}**, ${envLine(r)}` +
					`, ×${count} in this session (≈${existing.count + count} reports total).`;
				const res = await this.github(`/issues/${existing.number}/comments`, { body });
				if (res.ok) lastComment = now;
			}
			sql.exec(
				"UPDATE issues SET count = ?, versions = ?, last_comment = ? WHERE fingerprint = ?",
				existing.count + count, JSON.stringify(versions), lastComment, r.fingerprint,
			);
			return { url: existing.url, issue: existing.number };
		}

		const day = new Date().toISOString().slice(0, 10);
		const created = sql.exec<{ created: number }>("SELECT created FROM daily WHERE day = ?", day).toArray()[0]?.created ?? 0;
		if (created >= MAX_NEW_ISSUES_PER_DAY) return { queued: true };

		const res = await this.github("/issues", {
			title: title(r),
			body: issueBody(r, version, count),
			labels: ["auto-report"],
		});
		if (!res.ok) return { error: `GitHub ${res.status}` };
		const issue = (await res.json()) as { number: number; html_url: string };
		sql.exec(
			"INSERT INTO issues (fingerprint, number, url, count, versions, last_comment) VALUES (?, ?, ?, ?, ?, ?)",
			r.fingerprint, issue.number, issue.html_url, count, JSON.stringify([version]), Date.now(),
		);
		sql.exec(
			"INSERT INTO daily (day, created) VALUES (?, 1) ON CONFLICT(day) DO UPDATE SET created = created + 1",
			day,
		);
		return { url: issue.html_url, issue: issue.number };
	}

	private github(path: string, body: unknown): Promise<Response> {
		return fetch(`https://api.github.com/repos/${this.env.GITHUB_REPO}${path}`, {
			method: "POST",
			headers: {
				authorization: `Bearer ${this.env.GITHUB_TOKEN}`,
				accept: "application/vnd.github+json",
				"user-agent": "droidlens-reports",
				"x-github-api-version": "2022-11-28",
			},
			body: JSON.stringify(body),
		});
	}
}

function validate(o: unknown): Report {
	if (typeof o !== "object" || o === null) throw new Error("not an object");
	const r = o as Record<string, unknown>;
	const str = (k: string, max: number, required = true): string | undefined => {
		const v = r[k];
		if (v === undefined || v === null) {
			if (required) throw new Error(`missing ${k}`);
			return undefined;
		}
		if (typeof v !== "string") throw new Error(`${k} must be a string`);
		return scrub(v.slice(0, max));
	};
	const source = r.source;
	if (source !== "sdk" && source !== "ide") throw new Error("bad source");
	const fingerprint = r.fingerprint;
	if (typeof fingerprint !== "string" || !/^[0-9a-f]{16}$/.test(fingerprint)) throw new Error("bad fingerprint");
	return {
		source,
		fingerprint,
		where: str("where", 120)!,
		exception: str("exception", 200)!,
		message: str("message", 500) ?? "",
		stack: str("stack", 6000) ?? "",
		plugin: str("plugin", 40)!,
		ide: str("ide", 60)!,
		os: str("os", 60)!,
		feature: str("feature", 40, false),
		sdk: str("sdk", 40, false),
		note: str("note", 1000, false),
		count: typeof r.count === "number" ? Math.floor(r.count) : undefined,
		api: typeof r.api === "number" ? Math.floor(r.api) : undefined,
	};
}

/** 兜底脱敏（插件里已经做过一遍），再禁掉 @提及 和 #引用，免得公开 issue 去打扰别人。 */
function scrub(s: string): string {
	return s
		.replace(/\b[a-zA-Z][\w+.-]*:\/\/\S+/g, "<url>")
		.replace(/[\w.+-]+@[\w-]+\.[\w.-]+/g, "<email>")
		.replace(/\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b/g, "<ip>")
		.replace(/(?<![\w.<])(?:\/[\w.@+-]+){2,}\/?/g, "<path>")
		.replace(/@(?=\w)/g, "@​")
		.replace(/#(?=\d)/g, "#​");
}

function title(r: Report): string {
	const msg = r.message ? `: ${r.message}` : "";
	// 没有异常对象的错误（比如 PixelCopy 返回失败码）exception 是 "-"
	if (r.exception === "-") return `[auto] ${r.where}${msg}`.slice(0, 120);
	const exc = r.exception.split(".").pop() || r.exception;
	return `[auto] ${exc} in ${r.where}${msg}`.slice(0, 120);
}

function envLine(r: Report): string {
	return [r.ide && `IDE ${r.ide}`, r.os, r.api && `Android API ${r.api}`].filter(Boolean).join(", ");
}

function issueBody(r: Report, version: string, count: number): string {
	const fence = "```";
	return [
		`Automatically reported by DroidLens (${r.source === "sdk" ? "error inside the SDK on the device" : "error in the IDE plugin"}).`,
		"",
		`- **Where:** \`${r.where}\`${r.feature ? ` (feature \`${r.feature}\`)` : ""}`,
		`- **Exception:** \`${r.exception}\``,
		r.message ? `- **Message:** ${r.message}` : null,
		`- **Version:** ${version}`,
		`- **Environment:** ${envLine(r)}`,
		`- **Count:** ×${count} in the first report`,
		r.note ? `- **User note:** ${r.note}` : null,
		"",
		r.stack ? `${fence}\n${r.stack}\n${fence}` : "_no stack trace_",
		"",
		`<sub>fingerprint \`${r.fingerprint}\` · app class names, package names, URLs and paths were removed before sending</sub>`,
	].filter((l) => l !== null).join("\n");
}

function json(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}
