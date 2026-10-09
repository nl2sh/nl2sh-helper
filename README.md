[简体中文](README.md) | [English](README_EN.md)

# nl2sh助手

<img src="assets/icon.png" width="128" alt="nl2sh助手图标">

Android 应用，使用独立的 [ADB 库](https://github.com/Ernest-su/adb) 通过 TCP ADB 或 Android 11+ 无线调试连接目标设备，部署 [nl2sh](https://github.com/nl2sh/nl2sh) 最新 GitHub Release，并让 Web 界面在目标设备后台运行。

界面使用 Jetpack Compose + Navigation3，按连接、服务、Bridge 和历史四个 Tab 组织，并遵循 nl2sh Web 的暗色视觉规范，详见
[`docs/zh/design.md`](docs/zh/design.md) 和 [`UI_DESIGN.md`](UI_DESIGN.md)。连接和部署诊断写入 Android logcat，
可使用 `adb logcat -s Nl2shHelper Nl2shInstaller` 查看（包括底层异常原因）。

## 使用

1. 选择连接方式：TCP ADB 输入设备地址和端口；无线调试配对码输入目标设备显示的临时配对地址、端口和六位码；二维码方式让目标设备在同一 Wi-Fi 网络扫描助手显示的二维码。无线调试需要在目标设备的开发者选项中开启。
2. 首次安装或显式更新时，助手读取目标 ABI，选择 `x86_64`、`arm64-v8a` 或 `armeabi-v7a` 的最新发布二进制及其签名 Manifest。首次 TCP 连接需在目标设备批准 RSA 授权。
3. 相同版本、ABI 且摘要正确的文件使用应用私有缓存，不重复下载。助手校验设备上程序的摘要，相同时也不重复推送。
4. 启动成功后点击“在浏览器中打开”，由本机系统浏览器访问 返回的实际 Web 地址。控制设备必须能访问目标设备的 实际 Web 端口。

成功的 TCP、配对码和二维码连接分别保存在历史设备列表中。点击历史设备的“连接”会复用健康服务；独立按钮用于检查更新、重启或停止；无线调试先按设备 GUID 发现当前连接端口，发现失败时尝试上次地址。配对码和二维码密码不保存。

目标 Web 服务当前监听所有 IPv4 接口且无需登录；仅在可信网络使用。助手将程序部署到 nl2sh 默认位置 `/data/local/tmp/nl2sh`，由原生 service 管理配置旁的私有状态和日志，旧版服务保留兼容管理。目标必须允许 shell 用户从 `/data/local/tmp` 执行程序。助手不自动配置模型 API Key，可在打开的 Web 界面完成设置。

## 构建

需要 JDK 17 和 Android SDK 36：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Android 包名：`ernest.nl2sh.helper`。ADB 依赖由 Gradle 从 JitPack 的固定 `v0.3.0` 版本获取。

[完整连接、部署与排障指南](docs/zh/guide.md)

[签名构建与 GitHub Release 发布](docs/zh/releasing.md)

[界面与视觉规范](docs/zh/design.md) · [贡献者视觉约束](UI_DESIGN.md)

发布下载先验证签名兼容性 Manifest，再验证原生资产的 SHA-256、精确大小与独立 GPG 签名。公钥指纹固定为 `5230D3A7CCBEED4616D39C51FC6AD1BC63F7D4D8`，网络不能替换信任根。Manifest 要求助手版本与 service 协议兼容；无签名发布不能用于新安装或显式升级。已安装健康服务仍可连接，不查询发布。


Android 11+ 可选择“本机”，通过本机无线调试配对、安装并以 shell 权限运行 nl2sh。支持无需分屏的可拖动配对浮窗、自动发现与手动连接端口，Web 使用 `127.0.0.1` 的实际端口；详见[本机使用指南](docs/zh/guide.md#本机安装与启动)。
