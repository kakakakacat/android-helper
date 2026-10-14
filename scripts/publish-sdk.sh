#!/usr/bin/env bash
# 把 SDK（runtime AAR + Gradle 插件）发布到仓库根目录的 mvn-repo/，提交并推送。
#
#   ./scripts/publish-sdk.sh            # 发布、提交、推送
#   ./scripts/publish-sdk.sh --no-push  # 只发布并提交到本地，自己检查后再推
#
# 使用方读的是 raw.githubusercontent.com 下的静态文件：纯目录、零认证。
# 不用 GitHub Packages：它即使在公开仓库下也强制要求使用方带 token。
#
# 发版前先改 sdk/gradle.properties 里的 helper.version —— 同版本号覆盖发布，
# 使用方的 Gradle 缓存里可能还是旧产物，而且没有任何提示。
set -euo pipefail
cd "$(dirname "$0")/.."

PUSH=true
[[ "${1:-}" == "--no-push" ]] && PUSH=false

prop() { grep -m1 "^$1=" sdk/gradle.properties | cut -d= -f2-; }
VERSION="$(prop helper.version)"
REPO="$(prop helper.githubRepo)"
OWNER="${REPO%%/*}"

if [ -d "mvn-repo/kakacat/android/helper/runtime-core/${VERSION}" ]; then
  echo "mvn-repo 里已经有 ${VERSION} 了。先在 sdk/gradle.properties 里升 helper.version。"
  exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "工作区有未提交的改动，先提交（发布的产物应该对应一个确定的源码版本）。"
  exit 1
fi

(cd sdk && ./gradlew --console=plain -q \
  publishAllPublicationsToMvnRepoDirRepository \
  :gradle-plugin:publishAllPublicationsToMvnRepoDirRepository)

git add -A mvn-repo
git commit -q -m "publish SDK ${VERSION}"
echo "已提交：SDK ${VERSION}"

if [ "$PUSH" = true ]; then
  # 这台机器上 gh 登录了多个账号，git 的凭据助手用的是**当前活跃**的那个；
  # 不是仓库所有者就会 403，而报错信息完全不提这件事。
  if command -v gh >/dev/null 2>&1; then
    ACTIVE="$(gh api user --jq .login 2>/dev/null || true)"
    if [ -n "${ACTIVE}" ] && [ "${ACTIVE}" != "${OWNER}" ]; then
      echo "当前活跃的 gh 账号是 ${ACTIVE}，仓库属于 ${OWNER}，推送会 403。先执行："
      echo "  gh auth switch --user ${OWNER}"
      exit 1
    fi
  fi
  git push -u origin main
  echo
  echo "使用方的 maven url："
  echo "  https://raw.githubusercontent.com/${REPO}/main/mvn-repo/"
fi
