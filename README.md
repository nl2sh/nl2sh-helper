# nl2sh 助手

Android 应用，使用独立的 [ADB 库](https://github.com/Ernest-su/adb) 直连目标设备 TCP `adbd`，部署 [nl2sh](https://github.com/nl2sh/nl2sh) 最新 GitHub Release，并让 Web 界面在目标设备后台运行。

## 使用

1. 在目标设备开启 ADB TCP 连接，并在首次连接时批准本助手的 RSA 密钥。当前 ADB 库不支持 Android 11+ 无线调试配对和 USB 连接。
2. 在助手首页输入目标设备 IP 与 ADB 端口，点击“安装并启动”。助手读取目标 ABI，选择 `arm64-v8a` 或 `armeabi-v7a` 的最新发布二进制及其 SHA-256 文件。
3. 相同版本、ABI 且摘要正确的文件使用应用私有缓存，不重复下载。助手校验设备上程序的摘要，相同时也不重复推送。
4. 启动成功后点击“在浏览器中打开”，由本机系统浏览器访问 `http://目标IP:9999/`。控制设备必须能访问目标设备的 9999 端口。

目标 Web 服务当前监听所有 IPv4 接口且无需登录；仅在可信网络使用。助手管理 `/data/local/tmp/nl2sh-helper/` 内的程序、日志和 PID 文件，不修改设备上其他 nl2sh 安装。目标必须允许 shell 用户在该目录执行程序。助手不自动配置模型 API Key，可在打开的 Web 界面完成设置。

## 构建

需要 JDK 17 和 Android SDK 36：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Android 包名：`ernest.nl2sh.helper`。ADB 依赖由 Gradle 从 JitPack 的固定 `v0.1.0` 版本获取。
