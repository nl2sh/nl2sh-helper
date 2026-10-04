# Interface and visual conventions

The helper uses the same dark surfaces, neutral text and cyan/blue hierarchy as nl2sh, including when Android uses a light system theme. The launcher icon retains the brand gradients; functional screens use a shared semantic palette.

TCP ADB, pairing code and QR code buttons identify the current connection method with a checkmark and blue outline. Code and QR modes both use Android wireless debugging. Selection is separate from availability: switching, input and deployment are disabled only while a task is running, with an explicit busy label.

The status panel distinguishes ready, working, started, attention and failed states (currently labelled in Chinese). Blue indicates work in progress, green only a verified successful start, amber a warning and red an error. Details remain neutral and labels convey the result without relying on color. Deployment explicitly reports upload, target verification, version verification, startup and retry stages; if both startup attempts fail, it shows process state and the log tail. Successful pairing without a discovered connection service shows an attention state. A saved previous URL does not prove the service is currently online.

History is grouped by connection method. Long addresses and GUIDs wrap, with connect/delete actions below the details. Deleting a record does not revoke device authorization. QR dialogs are dark, but the QR image retains black/white contrast and its complete quiet zone. Cancellation stops discovery and reports the cancelled state.

The screen supports system font scaling and scrolling on narrow displays, stacking connection methods vertically at larger font sizes, with touch targets of at least 48dp. Opening the app does not automatically show the keyboard. The network notice retains the warning that the target Web UI requires no login and should only be used on a trusted network. The browser-based nl2sh interface is maintained by the nl2sh project.

Contributors must read [UI_DESIGN.md](../../UI_DESIGN.md) and [AGENTS.md](../../AGENTS.md) before interface changes. The UI uses Jetpack Compose; colors live in `colors.xml`, while `themes.xml` and `Nl2shTheme.kt` manage the window and Compose semantic themes.

## Screen examples

These show two scroll positions on a 320dp display, without a connected target device.

| Connection form | Status and history |
|---|---|
| ![Connection form](../assets/ui-tcp.png) | ![Status and history](../assets/ui-status.png) |
