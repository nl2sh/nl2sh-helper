package ernest.nl2sh.helper.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import ernest.nl2sh.helper.*

internal class HelperUiState {
    var mode by mutableStateOf(ConnectionMode.LOCAL)
    var localPairingPort by mutableStateOf("")
    var localConnectionPort by mutableStateOf("")
    var localPairingCode by mutableStateOf("")
    var host by mutableStateOf("")
    var port by mutableStateOf("5555")
    var pairingCode by mutableStateOf("")
    var status by mutableStateOf(UiStatus(StatusTone.IDLE, ""))
    var records by mutableStateOf(emptyList<ConnectionRecord>())
    var webUrl by mutableStateOf<String?>(null)
    var connectedRecord by mutableStateOf<ConnectionRecord?>(null)
    var bridgeSnapshot by mutableStateOf<BridgeSnapshot?>(null)
    var pendingBridgeInstall by mutableStateOf(false)
    var pendingDeviceAction by mutableStateOf<DeviceAction?>(null)
    var busy by mutableStateOf(false)
    var qrBitmap by mutableStateOf<Bitmap?>(null)
}

internal data class HelperActions(
    val selectMode: (ConnectionMode) -> Unit,
    val connect: () -> Unit,
    val openDeveloperOptions: () -> Unit,
    val openPairingOverlay: () -> Unit,
    val connectHistory: (ConnectionRecord) -> Unit,
    val removeHistory: (ConnectionRecord) -> Unit,
    val manageBridge: (BridgeAction) -> Unit,
    val confirmDeviceAction: (DeviceAction) -> Unit,
    val cancelQr: () -> Unit,
    val openBrowser: () -> Unit,
)
