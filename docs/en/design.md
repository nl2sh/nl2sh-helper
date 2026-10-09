# Interface and visual conventions

The Compose + Navigation3 interface has four functional tabs: Connection (连接), Service (服务), Bridge, and History (历史). Connection shows only the selected method's form; Service contains update, restart, stop and browser actions; Bridge contains diagnostics, installation and system settings; History filters by connection method and lazily renders records. Switching tabs retains input and each page's scroll position. Tabs remain accessible during work while duplicate operations stay disabled. Back returns from another tab to Connection; rotation restores the current tab without saving pairing secrets. Each page displays the shared operation status.

The helper uses the same dark surfaces, neutral text and cyan/blue hierarchy as nl2sh, including when Android uses a light system theme. The launcher icon retains the brand gradients; functional screens use a shared semantic palette.

TCP ADB, pairing code and QR code buttons identify the current connection method with a checkmark and blue outline. Code and QR modes both use Android wireless debugging. Selection is separate from availability: switching, input and deployment are disabled only while a task is running, with an explicit busy label.

The status panel distinguishes ready, working, started, attention and failed states (currently labelled in Chinese). Blue indicates work in progress, green only a verified successful start, amber a warning and red an error. Details remain neutral and labels convey the result without relying on color. Deployment explicitly reports reuse, staging checks, backup, startup, and restoration; errors state whether rollback completed. Successful pairing without a discovered connection service shows an attention state. A saved previous URL does not prove the service is currently online.

History is grouped by connection method. Long addresses and GUIDs wrap, with connect/delete actions below the details. Deleting a record does not revoke device authorization. QR dialogs are dark, but the QR image retains black/white contrast and its complete quiet zone. Cancellation stops discovery and reports the cancelled state.

The screen supports system font scaling and scrolling on narrow displays, stacking connection methods vertically at larger font sizes, with touch targets of at least 48dp. Opening the app does not automatically show the keyboard. The network notice retains the warning that the target Web UI requires no login and should only be used on a trusted network. The browser-based nl2sh interface is maintained by the nl2sh project.

Contributors must read [UI_DESIGN.md](../../UI_DESIGN.md) and [AGENTS.md](../../AGENTS.md) before interface changes. The UI uses Jetpack Compose; colors live in `colors.xml`, while `themes.xml` and `Nl2shTheme.kt` manage the window and Compose semantic themes.

## Page organization

Functional modules use separate pages instead of stacking every card in one scrolling screen.

These four pages were captured on an API 35 emulator before connecting a target in this session. A saved browser URL does not establish service readiness.

| Connection | Service |
|---|---|
| ![Connection](../assets/ui-tabs-connection.png) | ![Service](../assets/ui-tabs-service.png) |

| Bridge | History |
|---|---|
| ![Bridge](../assets/ui-tabs-bridge.png) | ![History](../assets/ui-tabs-history.png) |

Service management stacks Check updates, Restart, and Stop vertically, enabled after connecting. Dark confirmation dialogs explain effects. Stopping returns to ready; legacy service compatibility shows attention.

The Android control card stacks diagnostics and five actions with at least 48dp targets. Drift uses a short warning label while version/service details stay neutral. Installation has a dark confirmation describing certificate checks, refusal on signature conflict, and manual activation. Unchecked or unauthenticated compatibility is explicitly unknown.

Local is the default among four connection modes, normally arranged in two columns and stacked on narrow displays with large fonts. Its form includes Developer options, a pairing port, masked six-digit code and optional connection port. Instructions use neutral text; local history is separate.

Local pairing has a dark draggable overlay for devices without Settings split-screen support. Dragging, collapse/expand, close and a scrollable form support narrow screens and large fonts. Six-digit codes stay masked and are never saved. Pairing completion is distinct from starting nl2sh; return to the helper to install/start. Inputs gain visible focus and open the keyboard only on touch.

A notification reply fallback supports systems that hide overlays. The system reply editor has no application password mask; sent input is not echoed. Notification pairing status never implies a running nl2sh service.
