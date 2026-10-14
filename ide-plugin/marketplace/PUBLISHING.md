# 发布

两样东西要发布，顺序不能反：**先发 SDK，再发 IDE 插件**。插件商店页面上写的接入方式
依赖 SDK 已经能从公开仓库拉到。

## 一、SDK → GitHub mvn-repo

SDK（Gradle 插件 + 三个运行时 AAR）发布到仓库根目录的 `mvn-repo/`，随 `main` 分支推到 GitHub，
使用方通过 `https://raw.githubusercontent.com/<owner>/<repo>/main/mvn-repo/` 拉取，零认证。

仓库地址在 `sdk/gradle.properties` 的 `helper.githubRepo` 里配置（默认 `kakakakacat/android-helper`），
POM、插件商店描述、README 里的链接都指向它。

```bash
# 1. 改版本号（唯一来源）
vim sdk/gradle.properties          # helper.version=0.1.1

# 2. 提交源码改动，然后发布（脚本会检查工作区干净、版本号没发过、gh 活跃账号是仓库所有者）
./scripts/publish-sdk.sh            # 发布 + 提交 + 推送
./scripts/publish-sdk.sh --no-push  # 只提交到本地
```

第一次推送前需要先在 GitHub 上建仓库并设置 remote：

```bash
gh auth switch --user kakakakacat
gh repo create kakakakacat/android-helper --public --source . --remote origin
```

## 二、IDE 插件 → JetBrains Marketplace

### 一次性准备

1. 用 JetBrains 账号登录 <https://plugins.jetbrains.com>，同意开发者协议，创建 Vendor。
   `ide-plugin/src/main/resources/META-INF/plugin.xml` 里的 `<vendor>` 要和它一致。
2. （推荐）生成签名证书。私钥只放在本机或 CI 的密钥里，**不要提交**：

   ```bash
   mkdir -p ~/.android-helper-signing && cd ~/.android-helper-signing
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```

3. 在 Marketplace 个人页面 → My Tokens 生成一个发布用 token。

### 第一个版本：网页手动上传

```bash
cd ide-plugin
export CERTIFICATE_CHAIN="$(cat ~/.android-helper-signing/chain.crt)"
export PRIVATE_KEY="$(cat ~/.android-helper-signing/private.pem)"
export PRIVATE_KEY_PASSWORD="<生成证书时设的密码>"
./gradlew signPlugin
# 产物：build/distributions/android-helper-ide-<版本>-signed.zip
```

到 <https://plugins.jetbrains.com/plugin/add> 上传这个 zip，选择：

- License：Apache-2.0
- Category：按页面上的选项选最接近的（调试 / 工具集成类）
- Tags 建议：`android`, `network`, `okhttp`, `grpc`, `layout inspector`, `debugging`
- Source code URL：GitHub 仓库地址

第一次上传会人工审核，一般几个工作日。

### 之后的版本：命令行

```bash
# 1. 改 ide-plugin/build.gradle.kts 里的 version，在 marketplace/change-notes.html 顶部加一节
# 2. 上传前跑一遍兼容性检查（商店上传后也会跑同样的检查）
./gradlew verifyPlugin                                     # 所有配置的 AS 版本，每个 1–1.5 GB
./gradlew verifyPlugin -PverifyAndroidStudio=2026.2.1.8    # 只验一个
./gradlew verifyPlugin -PverifyAndroidStudio= -PverifyLocal=true   # 只验本机安装的，不下载

# 3. 发布（带上上面三个签名环境变量）
export PUBLISH_TOKEN="<Marketplace token>"
./gradlew publishPlugin
```

已经审核通过的插件，后续版本通常只走自动检查，几分钟到几小时上线。

## 版本号

- SDK：`sdk/gradle.properties` → `helper.version`
- IDE 插件：`ide-plugin/build.gradle.kts` → `version`

两者独立。IDE 插件能连任何版本的 SDK（协议靠 hello 帧里的 `features` 做能力协商），
所以只改了一边就只发一边。
