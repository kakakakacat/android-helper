#!/usr/bin/env bash
# 打包并签名 IDE 插件，产物：ide-plugin/build/distributions/android-helper-ide-<版本>-signed.zip
#
#   ./scripts/sign-plugin.sh            # 只签名
#   ./scripts/sign-plugin.sh --publish  # 签名并发布到 JetBrains Marketplace（需要 PUBLISH_TOKEN 环境变量）
#
# 证书和加密私钥在 ~/.android-helper-signing/，私钥密码在 macOS 钥匙串里
# （服务名 android-helper-plugin-signing），都不进仓库。生成方法见 ide-plugin/marketplace/PUBLISHING.md。
set -euo pipefail
cd "$(dirname "$0")/../ide-plugin"

KEY_DIR="${ANDROID_HELPER_SIGNING_DIR:-$HOME/.android-helper-signing}"
[ -f "$KEY_DIR/chain.crt" ] && [ -f "$KEY_DIR/private_encrypted.pem" ] || {
  echo "找不到 $KEY_DIR/chain.crt 或 private_encrypted.pem"; exit 1; }

export CERTIFICATE_CHAIN="$(cat "$KEY_DIR/chain.crt")"
export PRIVATE_KEY="$(cat "$KEY_DIR/private_encrypted.pem")"
export PRIVATE_KEY_PASSWORD="${PRIVATE_KEY_PASSWORD:-$(security find-generic-password -s android-helper-plugin-signing -w)}"

TASKS=(buildPlugin signPlugin)
if [[ "${1:-}" == "--publish" ]]; then
  [ -n "${PUBLISH_TOKEN:-}" ] || { echo "发布需要 PUBLISH_TOKEN（Marketplace → My Tokens）"; exit 1; }
  TASKS+=(publishPlugin)
fi

./gradlew --console=plain -q "${TASKS[@]}"
# 单独一次调用做校验：和 signPlugin 放在同一次调用里时偶尔会在签名包写完前就开始校验
./gradlew --console=plain -q verifyPluginSignature
ls -1 build/distributions/*-signed.zip
