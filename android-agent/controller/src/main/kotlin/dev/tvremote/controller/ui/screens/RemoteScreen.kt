@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.tvremote.controller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tvremote.controller.R
import dev.tvremote.controller.capability.KeySupport
import dev.tvremote.controller.capability.TextSupport
import dev.tvremote.controller.session.ControllerSession
import dev.tvremote.controller.ui.ConnectionPhase
import dev.tvremote.controller.ui.FailureKind
import dev.tvremote.controller.ui.ControllerUiState
import dev.tvremote.controller.ui.ControllerViewModel
import dev.tvremote.controller.ui.NoticeBanner
import dev.tvremote.controller.ui.ScreenHeader
import dev.tvremote.controller.ui.TextSendStatus
import dev.tvremote.controller.ui.supportOf

/** 支持长按的按键（DOWN/REPEAT/UP + 递增 repeatCount）；其余按键使用 PRESS。 */
private val LONG_PRESS_KEYS = setOf(
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
    "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE",
)

@Composable
fun RemoteScreen(state: ControllerUiState, viewModel: ControllerViewModel) {
    val device = state.activeDevice
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = device?.displayName ?: stringResource(R.string.screen_remote),
            subtitle = connectionSubtitle(state),
            onBack = viewModel::backToDevices,
            actions = { TextButton(onClick = viewModel::openSettings) { Text(stringResource(R.string.action_settings)) } },
        )
        NoticeBanner(state.notice, viewModel::dismissNotice)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.connection) {
                ConnectionPhase.CONNECTING, ConnectionPhase.AUTHENTICATING -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.remote_connecting))
                    }
                }
                ConnectionPhase.FAILED -> ConnectionFailurePanel(state, viewModel)
                ConnectionPhase.CONNECTED -> RemotePanel(state, viewModel)
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.remote_not_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        device?.let { Button(onClick = { viewModel.selectDevice(it.id) }) { Text(stringResource(R.string.action_connect)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionFailurePanel(state: ControllerUiState, viewModel: ControllerViewModel) {
    val failure = state.failure
    val supportsRetry = failure?.canRetry == true
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.remote_connection_failed), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            failure?.message ?: stringResource(R.string.remote_unknown_error),
            color = MaterialTheme.colorScheme.error,
            fontSize = 14.sp,
        )
        if (failure?.kind == FailureKind.NETWORK) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.remote_tv_unreachable_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        if (supportsRetry) {
            state.activeDeviceId?.let { id ->
                Button(onClick = { viewModel.selectDevice(id) }) { Text(stringResource(R.string.action_retry)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = viewModel::backToDevices) { Text(stringResource(R.string.remote_choose_other)) }
    }
}

@Composable
private fun RemotePanel(state: ControllerUiState, viewModel: ControllerViewModel) {
    val capabilities = state.capabilities
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Dpad(capabilities, viewModel)
        KeyFlow {
            RemoteKey(stringResource(R.string.key_back), capabilities.supportOf("BACK"), key = "BACK", viewModel = viewModel)
            RemoteKey(stringResource(R.string.key_home), capabilities.supportOf("HOME"), key = "HOME", viewModel = viewModel)
            RemoteKey(stringResource(R.string.key_menu), capabilities.supportOf("MENU"), key = "MENU", viewModel = viewModel)
        }
        KeyFlow {
            RemoteKey(stringResource(R.string.key_volume_up), capabilities.supportOf("VOLUME_UP"), key = "VOLUME_UP", viewModel = viewModel)
            RemoteKey(stringResource(R.string.key_volume_mute), capabilities.supportOf("VOLUME_MUTE"), key = "VOLUME_MUTE", viewModel = viewModel)
            RemoteKey(stringResource(R.string.key_volume_down), capabilities.supportOf("VOLUME_DOWN"), key = "VOLUME_DOWN", viewModel = viewModel)
        }
        KeyFlow {
            RemoteKey(stringResource(R.string.key_play_pause), capabilities.supportOf("MEDIA_PLAY_PAUSE"), key = "MEDIA_PLAY_PAUSE", viewModel = viewModel)
            RemoteKey(stringResource(R.string.key_stop), capabilities.supportOf("MEDIA_STOP"), key = "MEDIA_STOP", viewModel = viewModel)
        }
        KeyFlow {
            RemoteKey(stringResource(R.string.key_prev), capabilities.supportOf("MEDIA_PREVIOUS"), key = "MEDIA_PREVIOUS", viewModel = viewModel)
            RemoteKey(stringResource(R.string.key_next), capabilities.supportOf("MEDIA_NEXT"), key = "MEDIA_NEXT", viewModel = viewModel)
        }

        CapabilityLegend(capabilities)

        state.lastAck?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(12.dp))
        TextInputSection(state, viewModel, capabilities?.textInput ?: TextSupport.UNSUPPORTED)

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = viewModel::disconnect) { Text(stringResource(R.string.action_disconnect)) }
        Spacer(Modifier.height(24.dp))
    }
}

/** 按键行：窄屏自动换行（适配小屏/横屏/大字体）。 */
@Composable
private fun KeyFlow(content: @Composable () -> Unit) {
    Spacer(Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Dpad(capabilities: ControllerSession.Capabilities?, viewModel: ControllerViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RemoteKey("▲", capabilities.supportOf("DPAD_UP"), large = true, key = "DPAD_UP", viewModel = viewModel)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteKey("◀", capabilities.supportOf("DPAD_LEFT"), large = true, key = "DPAD_LEFT", viewModel = viewModel)
            RemoteKey(stringResource(R.string.key_ok), capabilities.supportOf("DPAD_CENTER"), large = true, key = "DPAD_CENTER", viewModel = viewModel)
            RemoteKey("▶", capabilities.supportOf("DPAD_RIGHT"), large = true, key = "DPAD_RIGHT", viewModel = viewModel)
        }
        RemoteKey("▼", capabilities.supportOf("DPAD_DOWN"), large = true, key = "DPAD_DOWN", viewModel = viewModel)
    }
}

@Composable
private fun CapabilityLegend(capabilities: ControllerSession.Capabilities?) {
    val bestEffort = capabilities?.keySupport?.values?.count { it == KeySupport.BEST_EFFORT } ?: 0
    val permission = capabilities?.keySupport?.values?.count { it == KeySupport.PERMISSION_REQUIRED } ?: 0
    val unverified = capabilities?.keySupport?.values?.count { it == KeySupport.UNVERIFIED } ?: 0
    if (bestEffort == 0 && permission == 0 && unverified == 0) return
    Spacer(Modifier.height(8.dp))
    Column {
        if (bestEffort > 0) Text(stringResource(R.string.legend_best_effort), fontSize = 12.sp)
        if (permission > 0) Text(stringResource(R.string.legend_permission), fontSize = 12.sp)
        if (unverified > 0) Text(stringResource(R.string.legend_unverified), fontSize = 12.sp)
    }
}

@Composable
private fun RemoteKey(
    label: String,
    support: KeySupport,
    large: Boolean = false,
    key: String,
    viewModel: ControllerViewModel,
) {
    val enabled = support.canAttempt
    val display = if (support == KeySupport.BEST_EFFORT) "$label *" else label
    val background = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        large -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .then(if (large) Modifier.size(76.dp) else Modifier.height(56.dp).widthIn(min = 88.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .pointerInput(enabled, key) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        if (key in LONG_PRESS_KEYS) {
                            viewModel.beginKeyPress(key)
                            // 页面销毁/手势取消也要释放，避免电视保留按下状态
                            try {
                                tryAwaitRelease()
                            } finally {
                                viewModel.endKeyPress(key)
                            }
                        } else {
                            viewModel.sendKey(key, "PRESS")
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            display,
            fontSize = if (large) 20.sp else 14.sp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
        )
    }
}

@Composable
private fun TextInputSection(
    state: ControllerUiState,
    viewModel: ControllerViewModel,
    textSupport: TextSupport,
) {
    // 草稿保存在 ViewModel（按设备归属、仅内存）：离开页面/断线不丢，发送成功或切设备才清
    val text = state.textDraft

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.section_text_input), fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
    when (textSupport) {
        TextSupport.SUPPORTED -> {
            OutlinedTextField(
                value = text,
                onValueChange = viewModel::updateTextDraft,
                label = { Text(stringResource(R.string.text_field_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.sendText(text) },
                    enabled = text.isNotEmpty() && state.textStatus != TextSendStatus.SENDING,
                ) { Text(stringResource(R.string.action_send)) }
                TextButton(onClick = viewModel::refreshCapabilities) { Text(stringResource(R.string.action_refresh_tv)) }
            }
            when (state.textStatus) {
                TextSendStatus.SENT -> Text(stringResource(R.string.text_sent), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                TextSendStatus.FAILED -> Text(
                    stringResource(R.string.text_failed),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
        TextSupport.PERMISSION_REQUIRED -> {
            Text(
                stringResource(R.string.text_permission),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = viewModel::refreshCapabilities) { Text(stringResource(R.string.action_refresh_tv)) }
        }
        TextSupport.UNVERIFIED -> Text(
            stringResource(R.string.text_unverified),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextSupport.UNSUPPORTED -> Text(
            stringResource(R.string.text_unsupported),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun connectionSubtitle(state: ControllerUiState): String {
    val endpoint = state.activeDevice?.let {
        stringResource(R.string.endpoint_suffix, it.lastHost ?: stringResource(R.string.endpoint_unknown))
    }.orEmpty()
    return when (state.connection) {
        ConnectionPhase.CONNECTED -> stringResource(R.string.subtitle_connected, endpoint)
        ConnectionPhase.CONNECTING -> stringResource(R.string.subtitle_connecting, endpoint)
        ConnectionPhase.AUTHENTICATING -> stringResource(R.string.subtitle_authenticating, endpoint)
        ConnectionPhase.DISCONNECTING -> stringResource(R.string.subtitle_disconnecting)
        ConnectionPhase.FAILED -> stringResource(R.string.subtitle_failed)
        ConnectionPhase.IDLE -> stringResource(R.string.subtitle_idle)
    }
}
