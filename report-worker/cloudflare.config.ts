import { bindings, defineConfig, exports } from "cf/config";

// 错误报告中转：DroidLens 插件 → 这里 → GitHub issue（kakakakacat/android-helper，标签 auto-report）。
// GitHub token 只存在这里（secret GITHUB_TOKEN，fine-grained，只给这个仓库的 Issues 读写权限），
// 插件和 SDK 里没有任何凭证。部署：npm run deploy；设置 token：见 README。

export default defineConfig({
	worker: {
		name: "droidlens-reports",
		compatibilityDate: "2026-10-01",
		entrypoint: "src/index.ts",
		domains: ["reports.kakacat.net"],
		workersDev: false,
		previewUrls: false,
		observability: { enabled: true },
		env: {
			GITHUB_REPO: bindings.text("kakakakacat/android-helper"),
			GITHUB_TOKEN: bindings.secret(),
			// 每个 IP 每分钟最多 10 份报告（按 Cloudflare 机房计，挡住刷量就够了）
			LIMITER: bindings.rateLimit({ namespace: "2101", simple: { limit: 10, period: 60 } }),
			// 全局只有一个实例：串行处理，同一个指纹不会并发开出两个 issue
			REGISTRY: bindings.durableObject({ worker: "droidlens-reports", exportName: "Registry" }),
		},
		exports: {
			Registry: exports.durableObject({ storage: "sqlite" }),
		},
	},
});
