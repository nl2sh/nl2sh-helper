# 连接与部署指南

助手是 Android ADB 客户端和 Web 启动器，与无障碍/键盘伴侣 [android-bridge](https://github.com/nl2sh/android-bridge) 及 [JADX helper](https://github.com/nl2sh/jadx-helper) 分开维护，不包含模型 Agent 或 A2A/MCP 网关。控制端需要 Android API 26+；目标设备需要 ARM64、ARMv7 或 x86_64 发布程序，并允许从 `/data/local/tmp` 执行。

## 界面

连接方式、状态和历史遵循 nl2sh 深色语义视觉规范，详见[界面与视觉规范](design.md)。当前方式以勾号标识，处理中禁用重复操作；状态详情和错误均可换行、滚动。

## 连接

- **TCP ADB：**输入目标地址和 ADB 连接端口。需要事先自行开启 TCP ADB；助手不会代为开启。首次连接在目标设备批准 RSA 授权。
- **配对码：**Android 11+ 目标设备的无线调试提供临时配对地址、配对端口和六位码。助手配对后发现独立的连接服务；配对端口不是连接端口。
- **二维码：**在助手显示二维码，用目标设备无线调试的二维码扫描入口扫描。两端需处于支持 multicast/mDNS 发现的同一网络。控制端不需要相机权限，每次二维码尝试生成新密码。

历史记录按连接方式分开，只保存成功连接，包含地址、端口、无线 GUID 和更新时间，不保存配对码或二维码密码。无线历史重连先搜索 GUID 十秒，再尝试上次地址。删除历史条目只删除记录，不撤销目标的 ADB 授权；需要撤销时到目标设备开发者设置操作。

## 连接、更新与服务管理

“连接 / 首次安装”和历史“连接”先检查已安装程序。健康的原生服务直接复用，不查询 Release、不下载、不推送、不重启；已安装但停止的服务只启动现有程序。未安装时才下载最新发布并安装。连接后，“服务管理”提供独立的“检查更新”“重启服务”“停止服务”；这些动作先展示确认，停止/重启会取消任务及待决审批，保留配置与会话。重新发现无线设备 GUID 后仍使用同一动作。

原生生命周期协议 1 通过 `nl2sh service ... --json` 管理状态；助手按返回的实际端口构造浏览器地址。设备内健康检查与控制端 `/healthz`、`/api/info` 检查分别确认状态、PID、运行版本和端口，不假定始终是 9999。保存的浏览器地址不代表当前在线。连接旧版受管 `nohup` 服务时显示兼容提示，保留旧 PID/可执行文件与 Web 检查，不自动迁移或升级；旧版仍需要固定 9999 可用。显式更新后，支持原生协议的版本采用新管理方式。

## 安装与更新流程

1. 连接上限 45 秒。读取目标 ABI，原生 `x86_64` 优先于翻译的 ARM ABI，其次 `arm64-v8a`、`armeabi-v7a`；不支持 x86 发布包。
2. 首次安装或显式更新查询 GitHub `nl2sh/nl2sh` 最新 Release，要求匹配 `nl2sh-android-ABI` 和 `.sha256`，使用 HTTPS。无版本选择或 Gitee 回退。
3. 缓存命中也重新获取摘要并复算文件；程序上限 32,000,000 bytes、元数据 512 KiB、摘要 1024 bytes。文本 I/O 错误最多尝试三次，不代表自动重试整个部署。
4. 相同目标版本与摘要直接复用服务。不同程序先上传 `/data/local/tmp/nl2sh.download`，验证设备摘要及 `--version`，此时现有服务继续运行。原程序校验后备份到 `nl2sh.previous`，再停止受管服务、原子重命名并启动新程序。
5. 新服务必须通过目标版本、实际 PID/端口及控制端 Web 检查。失败或取消时，在关闭 ADB 客户端前尝试停止新服务、隔离 `nl2sh.failed`、校验并恢复 previous，再检查旧版本服务。不能完成回滚时如实报告，保留文件供检查；断开 ADB、权限异常或损坏备份可能需要人工恢复，不能声称已恢复。
6. 成功后记录相邻 `nl2sh.owner.json`，包括 Helper 归属、版本、摘要和 GitHub 来源，不包含 ADB 配对秘密或模型凭据。previous 保留到下一次更新；旧助手目录内的程序仅在成功后清理。

程序默认位于 `/data/local/tmp/nl2sh`。助手不传 `--config`、不提供 API Key；nl2sh 使用自身默认配置规则。原生 service 的锁、状态和日志位于默认配置旁的 `config.service/`；兼容旧服务的 `helper.pid` 与 `nl2sh-web.log` 位于 `/data/local/tmp/nl2sh-helper/`。在控制端打开返回的实际 URL，配置模型并使用 Agent。

## 权限与排障

Manifest 申请网络与 Wi-Fi multicast 权限，允许目标 Web 的明文访问，并禁用应用备份。Release 下载要求 HTTPS。目标 Web 当前监听所有 IPv4 接口且无需登录，应在可信网络使用。Web 工具仍经过原生安全和浏览器确认链；ADB 安装命令属于用户显式发起的安装操作。

配对成功但连接失败时，核对当前连接端口和 multicast 路由；配对与连接端口不同且可能变化。Release 查询失败时检查 GitHub API/资产可达性、限流及 ABI 对应的两个资产。摘要不一致会报错，不应跳过校验。原生服务会返回可用端口；控制端必须能访问实际端口。启动失败时通过已授权 ADB 查看 `config.service/service.log`；兼容旧服务查看 `/data/local/tmp/nl2sh-helper/nl2sh-web.log`。更新错误会注明回滚是否完成。

## 构建

使用 JDK 17、Android SDK Platform 36、仓库内 Gradle 9.1.0 Wrapper 和 Android Gradle Plugin 9.0.1。应用 minSdk 26、targetSdk 35；ADB 依赖固定为 JitPack 的 `com.github.Ernest-su:adb:v0.3.0`。

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

应用 ID 为 `ernest.nl2sh.helper`。release 支持本地密钥配置与 GitHub Actions 签名，详见[签名构建与发布](releasing.md)；不要提交 `local.properties`、构建输出或签名凭据。单元测试覆盖 ABI/ELF/摘要、缓存及取消/失败恢复；端到端模拟器测试见下节，不等同于厂商真机或无线配对验收。

## 可复现的生命周期验证

仅在可重置的 x86_64 Android 模拟器执行：准备真实程序和故意启动失败的 Android ELF，设置已授权 TCP ADB 以及 Web 路由，测试不会访问发布网络。覆盖连接不下载/不重启、更新失败恢复旧程序/归属、成功更新并记录 Helper 归属，再连接保持 PID。测试安装和覆盖目标默认程序，请勿对生产设备执行。

```sh
python3 scripts/prepare-runtime-fixtures.py --runtime ../nl2sh/target/x86_64-linux-android/release/nl2sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class ernest.nl2sh.helper.RuntimeLifecycleTest \
  -e target_host TARGET_ADB_HOST -e adb_port TARGET_ADB_PORT -e web_port TARGET_WEB_PORT \
  -e runtime_version RUNTIME_VERSION \
  ernest.nl2sh.helper.test/androidx.test.runner.AndroidJUnitRunner
```

准备脚本要求 ANDROID_NDK_HOME/ANDROID_NDK_ROOT、rustc 及 x86_64-linux-android target；fixture 仅在忽略的 app/build 内，正式 APK 不包含它们。Web 路由必须允许控制端访问服务实际端口。
