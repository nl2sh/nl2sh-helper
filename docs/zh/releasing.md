# 签名构建与发布

流程参考 ascrcpy：普通 CI 构建 debug 和未签名 release；`Release` 工作流构建、验证已签名 APK 并创建 GitHub Release。需要 JDK 17、Android SDK 36。

## 1. 准备长期使用的签名密钥

如果已有用于此应用的发布密钥，继续使用它。首次发布可在本机运行（密码由 keytool 交互询问）：

```sh
mkdir -p "$HOME/.android-signing"
chmod 700 "$HOME/.android-signing"
keytool -genkeypair -v -storetype JKS \
  -keystore "$HOME/.android-signing/nl2sh-helper-release.jks" \
  -alias nl2sh-helper -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$HOME/.android-signing/nl2sh-helper-release.jks"
```

安全备份密钥库、别名和两个密码。后续更新必须保持相同签名；不要提交密钥或密码，也不要将它们发送到聊天中。

## 2. 本地签名构建

在仓库根目录复制 `keystore.properties.example` 为 `keystore.properties`（已被 Git 忽略），填写：

```properties
storeFile=/absolute/path/to/nl2sh-helper-release.jks
storePassword=你的密钥库密码
keyAlias=nl2sh-helper
keyPassword=你的密钥密码
```

`storeFile` 支持绝对路径或相对仓库根目录的路径，不展开 `~`。Java properties 中密码的反斜杠需要写成 `\\`，开头的空格需要转义；也可通过环境变量 `NL2SH_HELPER_KEYSTORE_FILE`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 提供，优先于文件，避免 properties 转义。

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease \
  -Pnl2shHelperVersionName=0.1.0 -Pnl2shHelperVersionCode=100
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

产物为 `app/build/outputs/apk/release/app-release.apk`。验证必须成功并显示 v2/v3 签名。已有 debug 安装与发布签名不同，不能直接覆盖；卸载会清除应用数据。没有签名配置时允许生成 `app-release-unsigned.apk`，但配置不完整、文件缺失或密码错误会失败。

## 3. 配置 GitHub Actions

在 **nl2sh/nl2sh-helper** 仓库的 `Settings → Secrets and variables → Actions → New repository secret` 配置：

| Secret | 值 |
|---|---|
| `KEYSTORE_BASE64` | 上述密钥库的完整 Base64 编码 |
| `STORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | `nl2sh-helper`（或已有别名） |
| `KEY_PASSWORD` | 密钥密码 |

已安装并登录 GitHub CLI 时，可不显示密钥内容，直接上传：

```sh
base64 -w0 "$HOME/.android-signing/nl2sh-helper-release.jks" | \
  gh secret set KEYSTORE_BASE64 --repo nl2sh/nl2sh-helper
gh secret set STORE_PASSWORD --repo nl2sh/nl2sh-helper
gh secret set KEY_ALIAS --repo nl2sh/nl2sh-helper
gh secret set KEY_PASSWORD --repo nl2sh/nl2sh-helper
```

后三条命令会交互读取值。工作流使用环境变量传递密码，不生成包含密码的 properties 文件；结束时删除临时密钥。普通 CI 和 pull request 不读取签名 Secret。

## 4. 发布

先将构建配置提交并推送到远程，再在准备发布的提交上创建标签：

```sh
git tag -a v0.1.0 -m "nl2sh-helper 0.1.0"
git push origin v0.1.0
```

在 Actions 查看 `Release`，成功后下载 GitHub Release 中的 `nl2sh-helper-0.1.0.apk`。也可手动运行 `Release`，填写**已存在的完整标签**（如 `v0.1.0`）；工作流检出这个标签。不要重新运行已成功发布的同一标签：已有 Release 会让创建步骤失败；需要新版本时创建新标签。

版本规则沿用 ascrcpy：`versionName` 为去掉 `v` 的标签，`versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`。三段数字不得含前导零，MINOR/PATCH < 100，MAJOR < 210000，不接受 `v0.0.0`。后缀如 `-rc1` 创建预发布，但与同一三段版本的正式版共用 versionCode；正式升级应选更大的三段版本。每次发布确保 versionCode 比已分发版本更大。本地默认为 `0.2.0` / `200`，发布应显式传入与标签对应的两个 Gradle 参数。

工作流在发布前运行单元测试、release lint，并强制用 apksigner 校验签名；缺少 Secret 或签名无效时不会发布 APK。

运行时发布顺序：先发布 Bridge/JADX 指定版本，再发布带签名 Manifest 的原生版本，最后分发匹配最低版本要求的助手。生产 GPG 私钥仅保留在原生 GitHub Actions 签名环境；助手内仅打包公钥。本地可暂缓生产签名。
