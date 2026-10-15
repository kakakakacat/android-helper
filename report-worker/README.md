# report-worker

DroidLens 错误报告中转，部署在 `https://reports.kakacat.net`（Cloudflare Worker `droidlens-reports`）。

```
DroidLens 插件 ──POST /v1/report──▶ Worker（限流、校验、兜底脱敏）──▶ Registry（Durable Object，串行）──▶ GitHub issue
```

- 同一 `fingerprint` 只开一个 issue（标签 `auto-report`）；指纹 → issue 的对应关系存在 Durable Object 的 SQLite 里。
- 已有 issue：出现新版本或距上次评论超过 24 小时才追加评论，其余只累计次数。
- 每天最多新开 20 个 issue；每个 IP 每分钟最多 10 份报告。
- 插件里已经用 `ErrorSanitizer`（client 模块）脱敏过，这里再擦一遍 URL / 邮箱 / IP / 路径，并让 `@` 和 `#数字` 不再触发提及、引用。

## GitHub token

Worker 用的是一个 **fine-grained token**：只授权 `kakakakacat/android-helper`，权限只有 **Issues: Read and write**。
在 kakakakacat 账号的 <https://github.com/settings/personal-access-tokens/new> 生成。它不进仓库，只存成 Worker 的 secret `GITHUB_TOKEN`。

## 部署

第一次部署（或更换 token）要同时带上 secret：

```bash
cd report-worker
npm install
read -rs "T?GitHub token: " && echo && (umask 077; printf 'GITHUB_TOKEN=%s\n' "$T" > .secrets.env) \
  && cf deploy --secrets-file .secrets.env; rm -f .secrets.env; unset T
```

之后只改代码时：`npm run deploy`。

## 本地调试

```bash
printf 'GITHUB_TOKEN=dummy\n' > .dev.vars
npx vite --port 8799
curl -X POST localhost:8799/v1/report -H 'content-type: application/json' -d '{"source":"sdk","fingerprint":"0123456789abcdef","where":"test","exception":"-","message":"","stack":"","plugin":"dev","ide":"dev","os":"dev"}'
```

插件端可以用 `-Ddroidlens.reportUrl=http://localhost:8799/v1/report` 指过来。
