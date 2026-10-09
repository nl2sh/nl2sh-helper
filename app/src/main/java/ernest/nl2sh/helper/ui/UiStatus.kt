package ernest.nl2sh.helper.ui

internal data class UiStatus(val tone: StatusTone, val message: String)
internal enum class StatusTone(val label: String) { IDLE("就绪"), WORKING("处理中"), SUCCESS("已启动"), WARNING("需注意"), ERROR("失败") }
