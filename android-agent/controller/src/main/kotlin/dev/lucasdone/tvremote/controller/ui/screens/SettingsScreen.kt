package dev.lucasdone.tvremote.controller.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lucasdone.tvremote.controller.BuildConfig
import dev.lucasdone.tvremote.controller.R
import dev.lucasdone.tvremote.controller.ui.ControllerUiState
import dev.lucasdone.tvremote.controller.ui.ControllerViewModel
import dev.lucasdone.tvremote.controller.ui.ScreenHeader

@Composable
fun SettingsScreen(state: ControllerUiState, viewModel: ControllerViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
    ScreenHeader(title = stringResource(R.string.screen_settings), onBack = viewModel::backToDevices)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(stringResource(R.string.settings_app_info), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_version, state.version))
        Text(stringResource(R.string.settings_controller_name, ControllerViewModel.CONTROLLER_NAME))
        Text(stringResource(R.string.settings_paired_count, state.devices.size))

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_connection), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (state.connected || state.activeDeviceId != null) {
            Text(
                stringResource(
                    R.string.settings_current_device,
                    state.activeDevice?.displayName ?: "—",
                    connectionLabel(state),
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = viewModel::disconnect, enabled = state.activeDeviceId != null) {
            Text(stringResource(R.string.action_disconnect_current))
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.settings_diagnostics), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_privacy),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 仅 Debug 构建显示的非敏感诊断；正式包不包含该区块，也不提供 WS/凭据调试入口。
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.settings_debug), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_app_id, BuildConfig.APPLICATION_ID), fontSize = 13.sp)
            Text(stringResource(R.string.settings_connection_state, connectionLabel(state)), fontSize = 13.sp)
            Text(stringResource(R.string.settings_discovered_count, state.discovered.size), fontSize = 13.sp)
        }
    }
    }
}

@Composable
private fun connectionLabel(state: ControllerUiState): String = when {
    state.connected -> stringResource(R.string.status_connected)
    state.failure != null -> stringResource(R.string.status_failed)
    else -> stringResource(R.string.status_not_connected)
}
