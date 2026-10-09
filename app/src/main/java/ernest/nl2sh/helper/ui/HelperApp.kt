package ernest.nl2sh.helper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import ernest.nl2sh.helper.R
import ernest.nl2sh.helper.ui.bridge.BridgeScreen
import ernest.nl2sh.helper.ui.components.*
import ernest.nl2sh.helper.ui.connection.ConnectionScreen
import ernest.nl2sh.helper.ui.history.HistoryScreen
import ernest.nl2sh.helper.ui.navigation.HelperDestination
import ernest.nl2sh.helper.ui.service.ServiceScreen

@Composable
internal fun HelperApp(state: HelperUiState, actions: HelperActions) {
    var selectedName by rememberSaveable { mutableStateOf(HelperDestination.CONNECTION.name) }
    val destination = HelperDestination.valueOf(selectedName)
    val focus = LocalFocusManager.current
    val pageStates = rememberSaveableStateHolder()
    BackHandler(destination != HelperDestination.CONNECTION) {
        focus.clearFocus()
        selectedName = HelperDestination.CONNECTION.name
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_art), null, Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.app_name), color = MaterialTheme.colorScheme.primary,
                fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        // Scrollable tabs keep navigation reachable on narrow screens and with large fonts.
        @OptIn(ExperimentalMaterial3Api::class)
        PrimaryScrollableTabRow(selectedTabIndex = destination.ordinal, edgePadding = 20.dp,
            containerColor = MaterialTheme.colorScheme.background) {
            HelperDestination.entries.forEach { tab ->
                Tab(selected = tab == destination, onClick = {
                    focus.clearFocus()
                    selectedName = tab.name
                }, modifier = Modifier.heightIn(min = 48.dp),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(tab.title) })
            }
        }
        NavDisplay(backStack = listOf(destination), modifier = Modifier.weight(1f),
            onBack = { selectedName = HelperDestination.CONNECTION.name },
            entryProvider = { key ->
                NavEntry(key) {
                    pageStates.SaveableStateProvider(key.name) {
                        if (key == HelperDestination.HISTORY) {
                            state.HistoryScreen(actions)
                        } else {
                            ModulePage {
                                with(state) {
                                    when (key) {
                                        HelperDestination.CONNECTION -> ConnectionScreen(actions)
                                        HelperDestination.SERVICE -> ServiceScreen(actions)
                                        HelperDestination.BRIDGE -> BridgeScreen(actions)
                                        HelperDestination.HISTORY -> Unit
                                    }
                                    StatusCard()
                                }
                            }
                        }
                    }
                }
            })
    }
    state.HelperDialogs(actions)
}

@Composable
private fun ModulePage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}
