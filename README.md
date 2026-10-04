[简体中文](README.md) | [English](README_EN.md)

# nl2sh 助手

Android 应用，使用独立的 [ADB 库](https://github.com/Ernest-su/adb) 通过 TCP ADB 或 Android 11+ 无线调试连接目标设备，部署 [nl2sh](https://github.com/nl2sh/nl2sh) 最新 GitHub Release，并让 Web 界面在目标设备后台运行。

## 使用

1. 选择连接方式：TCP ADB 输入设备地址和端口；无线调试配对码输入目标设备显示的临时配对地址、端口和六位码；二维码方式让目标设备在同一 Wi-Fi 网络扫描助手显示的二维码。无线调试需要在目标设备的开发者选项中开启。
2. 连接成功后，助手读取目标 ABI，选择 `arm64-v8a` 或 `armeabi-v7a` 的最新发布二进制及其 SHA-256 文件。首次 TCP 连接需在目标设备批准 RSA 授权。
3. 相同版本、ABI 且摘要正确的文件使用应用私有缓存，不重复下载。助手校验设备上程序的摘要，相同时也不重复推送。
4. 启动成功后点击“在浏览器中打开”，由本机系统浏览器访问 `http://目标IP:9999/`。控制设备必须能访问目标设备的 9999 端口。

成功的 TCP、配对码和二维码连接分别保存在历史设备列表中。点击历史设备的“连接”会重新安装并启动；无线调试先按设备 GUID 发现当前连接端口，发现失败时尝试上次地址。配对码和二维码密码不保存。

目标 Web 服务当前监听所有 IPv4 接口且无需登录；仅在可信网络使用。助手管理 `/data/local/tmp/nl2sh-helper/` 内的程序、日志和 PID 文件，不修改设备上其他 nl2sh 安装。目标必须允许 shell 用户在该目录执行程序。助手不自动配置模型 API Key，可在打开的 Web 界面完成设置。

## 构建

需要 JDK 17 和 Android SDK 36：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Android 包名：`ernest.nl2sh.helper`。ADB 依赖由 Gradle 从 JitPack 的固定 `v0.3.0` 版本获取。

[完整连接、部署与排障指南](docs/zh/guide.md)
