# Interface and visual conventions

The helper uses the same dark surfaces, neutral text and cyan/blue hierarchy as nl2sh, including when Android uses a light system theme. The launcher icon retains the brand gradients; functional screens use a shared semantic palette.

TCP ADB, pairing code and QR code buttons identify the current connection method with a checkmark and blue outline. Code and QR modes both use Android wireless debugging. Selection is separate from availability: switching, input and deployment are disabled only while a task is running, with an explicit busy label.

The status panel distinguishes ready, working, started, attention and failed states (currently labelled in Chinese). Blue indicates work in progress, green only a verified successful start, amber a warning and red an error. Details remain neutral and labels convey the result without relying on color. Deployment explicitly reports reuse, staging checks, backup, startup, and restoration; errors state whether rollback completed. Successful pairing without a discovered connection service shows an attention state. A saved previous URL does not prove the service is currently online.

History is grouped by connection method. Long addresses and GUIDs wrap, with connect/delete actions below the details. Deleting a record does not revoke device authorization. QR dialogs are dark, but the QR image retains black/white contrast and its complete quiet zone. Cancellation stops discovery and reports the cancelled state.

The screen supports system font scaling and scrolling on narrow displays, stacking connection methods vertically at larger font sizes, with touch targets of at least 48dp. Opening the app does not automatically show the keyboard. The network notice retains the warning that the target Web UI requires no login and should only be used on a trusted network. The browser-based nl2sh interface is maintained by the nl2sh project.

Contributors must read [UI_DESIGN.md](../../UI_DESIGN.md) and [AGENTS.md](../../AGENTS.md) before interface changes. The UI uses Jetpack Compose; colors live in `colors.xml`, while `themes.xml` and `Nl2shTheme.kt` manage the window and Compose semantic themes.

## Screen examples

These show two scroll positions on a 320dp display, before verifying a target in the current session.

| Connection form | Status and service management |
|---|---|
| ![Connection form](../assets/ui-tcp.png) | ![Status and service management](../assets/ui-status.png) |

Service management stacks Check updates, Restart, and Stop vertically, enabled after connecting. Dark confirmation dialogs explain effects. Stopping returns to ready; legacy service compatibility shows attention.

The Android control card stacks diagnostics and five actions with at least 48dp targets. Drift uses a short warning label while version/service details stay neutral. Installation has a dark confirmation describing certificate checks, refusal on signature conflict, and manual activation. Unchecked or unauthenticated compatibility is explicitly unknown.

Local is the default among four connection modes, normally arranged in two columns and stacked on narrow displays with large fonts. Its form includes Developer options, a pairing port, masked six-digit code and optional connection port. Instructions use neutral text; local history is separate.
