package ernest.nl2sh.helper.ui

import ernest.nl2sh.helper.TransferProgress

internal data class UiStatus(val tone: StatusTone, val message: String, val progress: TransferProgress? = null)
internal enum class StatusTone(val label: String) { IDLE("就绪"), WORKING("处理中"), SUCCESS("已启动"), WARNING("需注意"), ERROR("失败") }
