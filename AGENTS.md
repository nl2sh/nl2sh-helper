# AGENTS.md

## 项目与边界

nl2sh助手是独立的 Android ADB 客户端、nl2sh 安装器和 Web 启动器，应用 ID `ernest.nl2sh.helper`，API 26+。ADB 使用固定的 `com.github.Ernest-su:adb:v0.3.0`；不在助手内复制 ADB 实现，不包含模型 Agent、A2A/MCP 网关或无障碍伴侣。

修改前阅读本文件、README、`docs/zh/guide.md`，涉及签名/发布时阅读 `docs/zh/releasing.md`。业务代码位于 `app/src/main/java/ernest/nl2sh/helper/`；单元测试位于 `app/src/test/`。

## 视觉规范是强约束

任何界面、主题、文案布局或控件改动前必须完整阅读 [UI_DESIGN.md](UI_DESIGN.md)，并检查 `HelperUi.kt`、`res/values/colors.xml`、`res/values/themes.xml` 和 AndroidManifest。助手必须保持与 nl2sh TUI/Web 一致的深色语义 palette，不得恢复浅色系统主题或各自硬编码业务颜色。

- `colors.xml` 是唯一业务颜色源，`HelperUi` 是程序化原生控件样式入口；新增组件引用已有语义 token。禁止在 Activity 中新增 `Color.rgb`、`Color.parseColor` 或业务 hex 常量。
- 品牌图标、二维码黑白与透明遮罩是 UI_DESIGN 明确列出的例外，不能据此改成白底页面或纯黑大面积背景。
- accent 表示导航/焦点，cyan 表示分区，success/warning/error 只表达真实结果/注意事项/错误。长正文和状态详情保持中性，不能整段染绿或染红。
- 选中连接方式用 selected、边框、`✓` 和无障碍说明表达，不能用 disabled 伪装选中。处理中、取消、失败、保存地址和已验证成功分别保留真实状态和文字，不根据消息关键词推测状态。
- 输入焦点必须可见；触摸目标至少 48dp；sp 字号支持缩放；窄屏、横屏和长地址/GUID 必须换行/滚动，不裁掉动作或错误；首次打开不能弹出键盘。
- UI 改动在同一变更中同步视觉规范与相关双语文档。ADB 库升级或界面重写必须重新走视觉验收，不能删掉主题/控件状态作为升级捷径。

## 业务与凭据约束

保留 TCP、配对码、二维码及各自历史；不保存配对秘密。网络/部署工作留在 IO dispatcher，Activity 销毁和取消时关闭发现与客户端。不能为了 UI 简化而删除 SHA-256 校验、缓存损坏检查、目标版本核验、受管 PID 边界或 Web 健康检查。保存的浏览器入口不等于当前服务在线。

不要提交 `keystore.properties`、密钥库、密码、Base64 密钥、local.properties 或构建产物；不得将凭据写入文档、日志或提交说明。示例只用占位值。发布按 `docs/zh/releasing.md` 和 Release workflow 执行；不移动已发布标签，不通过更换密钥解决安装签名冲突。

## 验证与文档

使用仓库 Gradle Wrapper、JDK 17、SDK Platform 36：

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

界面变更必须按 UI_DESIGN 的清单进行模拟器/设备 smoke test，并准确说明实际覆盖范围；真实无线配对和部署不能用静态检查替代。签名变更额外验证 apksigner。修复业务缺陷时补覆盖缺陷的测试，避免仅镜像实现的测试。

正式用户指南保留 `docs/zh/`、`docs/en/` 的对应页面，README 仅提供简介、构建命令和链接。内部视觉规范使用根目录 UI_DESIGN。项目文档不得写入个人用户名、机器绝对路径或仅对某次本地会话成立的环境说明。
