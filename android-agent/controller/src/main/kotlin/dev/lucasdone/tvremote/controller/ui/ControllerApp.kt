package dev.lucasdone.tvremote.controller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lucasdone.tvremote.controller.ui.screens.AddDeviceScreen
import dev.lucasdone.tvremote.controller.ui.screens.DeviceListScreen
import dev.lucasdone.tvremote.controller.ui.screens.PairingScreen
import dev.lucasdone.tvremote.controller.ui.screens.RemoteScreen
import dev.lucasdone.tvremote.controller.ui.screens.SettingsScreen

@Composable
fun ControllerApp(viewModel: ControllerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 状态栏/导航栏/刘海/手势区 insets 在内容层消费（Android 15+ edge-to-edge）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (state.screen) {
            Screen.DEVICES -> DeviceListScreen(state, viewModel)
            Screen.ADD_DEVICE -> AddDeviceScreen(state, viewModel)
            Screen.PAIRING -> PairingScreen(state, viewModel)
            Screen.REMOTE -> RemoteScreen(state, viewModel)
            Screen.SETTINGS -> SettingsScreen(state, viewModel)
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
