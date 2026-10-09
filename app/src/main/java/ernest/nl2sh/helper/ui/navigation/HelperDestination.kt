package ernest.nl2sh.helper.ui.navigation

import androidx.navigation3.runtime.NavKey

internal enum class HelperDestination(val title: String) : NavKey {
    CONNECTION("连接"), SERVICE("服务"), BRIDGE("Bridge"), HISTORY("历史")
}
