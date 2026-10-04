# 连接与部署指南

助手是 Android ADB 客户端和 Web 启动器，与无障碍/键盘伴侣 [android-bridge](https://github.com/nl2sh/android-bridge) 及 [JADX helper](https://github.com/nl2sh/jadx-helper) 分开维护，不包含模型 Agent 或 A2A/MCP 网关。控制端需要 Android API 26+；目标设备需要 ARM64 或 ARMv7 发布程序，并允许从 `/data/local/tmp` 执行。

## 界面

连接方式、状态和历史遵循 nl2sh 深色语义视觉规范，详见[界面与视觉规范](design.md)。当前方式以勾号标识，处理中禁用重复操作；状态详情和错误均可换行、滚动。

## 连接

- **TCP ADB：**输入目标地址和 ADB 连接端口。需要事先自行开启 TCP ADB；助手不会代为开启。首次连接在目标设备批准 RSA 授权。
- **配对码：**Android 11+ 目标设备的无线调试提供临时配对地址、配对端口和六位码。助手配对后发现独立的连接服务；配对端口不是连接端口。
- **二维码：**在助手显示二维码，用目标设备无线调试的二维码扫描入口扫描。两端需处于支持 multicast/mDNS 发现的同一网络。控制端不需要相机权限，每次二维码尝试生成新密码。

历史记录按连接方式分开，只保存成功连接，包含地址、端口、无线 GUID 和更新时间，不保存配对码或二维码密码。无线历史重连先搜索 GUID 十秒，再尝试上次地址。删除历史条目只删除记录，不撤销目标的 ADB 授权；需要撤销时到目标设备开发者设置操作。

## 安装流程

1. 连接（上限 45 秒），读取目标 ABI 列表，优先选择 `arm64-v8a`，其次 `armeabi-v7a`。安装器不支持 x86/x86_64 发布程序。
2. 查询 GitHub 的 `nl2sh/nl2sh` 最新 Release，要求同时存在 `nl2sh-android-ABI` 和 `.sha256` 资产，通过 HTTPS 下载。不提供版本选择或 Gitee 回退。
3. 缓存命中也重新获取摘要；应用私有缓存中的程序只有复算一致才复用。程序上限 32,000,000 bytes，元数据 512 KiB，摘要文件 1024 bytes。文本请求遇到 I/O 错误最多尝试三次，不代表自动重试整个部署。
4. 比较目标程序摘要，不一致时上传为 `nl2sh.download`，修改执行权限、重命名为 `nl2sh`，再校验设备摘要，并核对 `--version` 与 Release 标签。
5. 只有 `/proc/PID/exe` 匹配受管程序路径时才停止 `helper.pid` 记录的进程；遇到冲突的 9999 端口报错。通过 `nohup ./nl2sh --web-only` 启动，保存 `helper.pid` 和 `nl2sh-web.log`，检查进程及 `/api/sessions` 后才报告成功。

受管文件位于 `/data/local/tmp/nl2sh-helper/`。启动器不传 `--config`，由 nl2sh 自身按默认规则查找配置，也不提供 API Key。在控制端浏览器打开返回的 `http://目标IP:9999/`，配置模型并使用 Agent。历史重连也会检查/安装最新版本并重启受管服务。

## 权限与排障

Manifest 申请网络与 Wi-Fi multicast 权限，允许目标 Web 的明文访问，并禁用应用备份。Release 下载要求 HTTPS。目标 Web 当前监听所有 IPv4 接口且无需登录，应在可信网络使用。Web 工具仍经过原生安全和浏览器确认链；ADB 安装命令属于用户显式发起的安装操作。

配对成功但连接失败时，核对当前连接端口和 multicast 路由；配对与连接端口不同且可能变化。Release 查询失败时检查 GitHub API/资产可达性、限流及 ABI 对应的两个资产。摘要不一致会报错，不应跳过校验。9999 被占用时自行停止已识别的冲突服务，或另行配置 nl2sh 启动方式。启动失败时通过已授权的 ADB 查看 `/data/local/tmp/nl2sh-helper/nl2sh-web.log`，并确认控制端可访问目标 9999 端口。

## 构建

使用 JDK 17、Android SDK Platform 36、仓库内 Gradle 9.1.0 Wrapper 和 Android Gradle Plugin 9.0.1。应用 minSdk 26、targetSdk 35；ADB 依赖固定为 JitPack 的 `com.github.Ernest-su:adb:v0.3.0`。

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

应用 ID 为 `ernest.nl2sh.helper`。release 支持本地密钥配置与 GitHub Actions 签名，详见[签名构建与发布](releasing.md)；不要提交 `local.properties`、构建输出或签名凭据。单元测试覆盖 ABI/摘要选择及缓存复用/损坏，不验证真机配对和 Web 启动。
