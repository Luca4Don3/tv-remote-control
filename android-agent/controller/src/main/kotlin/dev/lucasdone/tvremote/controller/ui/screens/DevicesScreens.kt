@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lucasdone.tvremote.controller.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.lucasdone.tvremote.controller.R
import dev.lucasdone.tvremote.controller.data.DEFAULT_CONTROL_PORT
import dev.lucasdone.tvremote.controller.pairing.PairInvitationParser
import dev.lucasdone.tvremote.controller.ui.ConnectionPhase
import dev.lucasdone.tvremote.controller.ui.ControllerUiState
import dev.lucasdone.tvremote.controller.ui.ControllerViewModel
import dev.lucasdone.tvremote.controller.ui.EmptyState
import dev.lucasdone.tvremote.controller.ui.NoticeBanner
import dev.lucasdone.tvremote.controller.ui.PairingChannel
import dev.lucasdone.tvremote.controller.ui.ScreenHeader
import dev.lucasdone.tvremote.controller.ui.scanner.QrImageDecoder
import dev.lucasdone.tvremote.controller.ui.scanner.QrScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeviceListScreen(state: ControllerUiState, viewModel: ControllerViewModel) {
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var forgetTarget by remember { mutableStateOf<String?>(null) }
    var addressTarget by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.screen_devices_title),
            subtitle = stringResource(R.string.screen_devices_subtitle),
            actions = {
                TextButton(onClick = viewModel::openAddDevice) { Text(stringResource(R.string.action_add_device)) }
                TextButton(onClick = viewModel::openSettings) { Text(stringResource(R.string.action_settings)) }
            },
        )
        NoticeBanner(state.notice, viewModel::dismissNotice)

        if (state.devices.isEmpty()) {
            EmptyState(stringResource(R.string.devices_empty), modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                items(state.devices, key = { it.id }) { device ->
                    DeviceCard(
                        name = device.displayName,
                        tvName = device.tvDisplayName,
                        endpoint = device.lastHost?.let { "$it:${device.lastPort}" },
                        lastUsed = formatLastUsed(device.lastUsedAtMs),
                        status = deviceStatus(state, device.id),
                        connected = device.id == state.activeDeviceId && state.connected,
                        isActive = state.activeDeviceId == device.id,
                        onConnect = { viewModel.selectDevice(device.id) },
                        onRename = { renameTarget = device.id },
                        onForget = { forgetTarget = device.id },
                        onChangeAddress = { addressTarget = device.id },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        renameTarget?.let { id ->
            val current = state.devices.firstOrNull { it.id == id }?.displayName ?: ""
            RenameDialog(
                initial = current,
                onConfirm = { viewModel.renameDevice(id, it); renameTarget = null },
                onDismiss = { renameTarget = null },
            )
        }
        forgetTarget?.let { id ->
            val current = state.devices.firstOrNull { it.id == id }?.displayName ?: ""
            AlertDialog(
                onDismissRequest = { forgetTarget = null },
                title = { Text(stringResource(R.string.dialog_forget_title)) },
                text = { Text(stringResource(R.string.dialog_forget_message, current)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.forgetDevice(id); forgetTarget = null }) {
                        Text(stringResource(R.string.action_forget))
                    }
                },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    addressTarget?.let { id ->
        val device = state.devices.firstOrNull { it.id == id }
        ChangeAddressDialog(
            initialHost = device?.lastHost.orEmpty(),
            initialPort = device?.lastPort ?: DEFAULT_CONTROL_PORT,
            onConfirm = { host, port ->
                viewModel.connectToEndpoint(id, host, port)
                addressTarget = null
            },
            onDismiss = { addressTarget = null },
        )
    }
}
}

@Composable
private fun deviceStatus(state: ControllerUiState, id: String): String = when {
    state.activeDeviceId != id -> stringResource(R.string.status_paired)
    state.connected -> stringResource(R.string.status_connected)
    state.connection == ConnectionPhase.CONNECTING -> stringResource(R.string.status_connecting)
    state.connection == ConnectionPhase.AUTHENTICATING -> stringResource(R.string.status_authenticating)
    state.connection == ConnectionPhase.DISCONNECTING -> stringResource(R.string.status_disconnecting)
    state.connection == ConnectionPhase.FAILED -> stringResource(R.string.status_failed)
    else -> stringResource(R.string.status_not_connected)
}

@Composable
private fun DeviceCard(
    name: String,
    tvName: String?,
    endpoint: String?,
    lastUsed: String,
    status: String,
    connected: Boolean,
    isActive: Boolean,
    onConnect: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
    onChangeAddress: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (isActive) {
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        stringResource(R.string.device_current),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            tvName?.let { Text(stringResource(R.string.device_tv_name, it), fontSize = 13.sp) }
            endpoint?.let { Text(stringResource(R.string.device_last_endpoint, it), fontSize = 13.sp) }
            Text(stringResource(R.string.device_last_used, lastUsed), fontSize = 13.sp)
            Text(
                stringResource(R.string.device_status, status),
                fontSize = 13.sp,
                color = if (connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onConnect, enabled = !connected) { Text(stringResource(R.string.action_connect)) }
                OutlinedButton(onClick = onRename) { Text(stringResource(R.string.action_rename)) }
                OutlinedButton(onClick = onChangeAddress) { Text(stringResource(R.string.action_change_address)) }
                OutlinedButton(onClick = onForget) { Text(stringResource(R.string.action_forget)) }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(64) },
                label = { Text(stringResource(R.string.field_device_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** R09：用已保存凭据连接新地址（仍校验证书指纹）。 */
@Composable
private fun ChangeAddressDialog(
    initialHost: String,
    initialPort: Int,
    onConfirm: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_change_address_title)) },
        text = {
            Column {
                Text(stringResource(R.string.dialog_change_address_hint), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text(stringResource(R.string.field_tv_host)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.field_port, DEFAULT_CONTROL_PORT)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(host, port.toIntOrNull() ?: DEFAULT_CONTROL_PORT) },
                enabled = host.isNotBlank(),
            ) { Text(stringResource(R.string.action_connect)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun formatLastUsed(lastUsedAtMs: Long): String {
    if (lastUsedAtMs <= 0L) return stringResource(R.string.last_used_never)
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastUsedAtMs))
}

@Composable
fun AddDeviceScreen(state: ControllerUiState, viewModel: ControllerViewModel) {
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf(DEFAULT_CONTROL_PORT.toString()) }
    var showScanner by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val cameraDenied = stringResource(R.string.camera_denied)
    val scannerInvalid = stringResource(R.string.scanner_invalid)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scanError = null
            showScanner = true
        } else {
            scanError = cameraDenied
        }
    }
    val requestScan = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            scanError = null
            showScanner = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { QrImageDecoder.decode(context, uri) }
            val invitation = text?.let { PairInvitationParser.parse(it) }
            if (invitation == null) {
                scanError = scannerInvalid
            } else {
                scanError = null
                viewModel.submitPairingInvitation(invitation)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.screen_add_device), onBack = viewModel::backToDevices)
        NoticeBanner(state.notice, viewModel::dismissNotice)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
            Text(stringResource(R.string.section_scan), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.scan_description),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = requestScan) { Text(stringResource(R.string.action_open_scanner)) }
                OutlinedButton(
                    onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                ) { Text(stringResource(R.string.action_pick_image)) }
            }
            scanError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.section_lan_search), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                state.discoveryMessage ?: stringResource(R.string.discovery_idle),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::discover, enabled = !state.discovering) {
                    Text(stringResource(R.string.action_search_again))
                }
                if (state.discovering) {
                    Spacer(Modifier.padding(8.dp))
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            state.discovered.forEach { tv ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tv.displayName, fontSize = 15.sp)
                            Text(
                                stringResource(R.string.discovered_unpaired, tv.address, tv.controlPort),
                                fontSize = 12.sp,
                            )
                        }
                        Button(onClick = {
                            viewModel.openPairing(tv.address, tv.controlPort, PairingChannel.CODE, tv.displayName)
                        }) { Text(stringResource(R.string.action_pair)) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.section_manual), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it.trim() },
                label = { Text(stringResource(R.string.field_tv_host)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualPort,
                onValueChange = { manualPort = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.field_port, DEFAULT_CONTROL_PORT)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val port = manualPort.toIntOrNull() ?: DEFAULT_CONTROL_PORT
                    viewModel.openPairing(manualHost, port, PairingChannel.CODE)
                },
                enabled = manualHost.isNotBlank(),
            ) { Text(stringResource(R.string.action_pair_with_code)) }
            Spacer(Modifier.height(24.dp))
        }

        if (showScanner) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                QrScanner(
                    modifier = Modifier.fillMaxSize(),
                    onCode = { text ->
                        val invitation = PairInvitationParser.parse(text)
                        showScanner = false
                        if (invitation == null) {
                            scanError = scannerInvalid
                        } else {
                            scanError = null
                            viewModel.submitPairingInvitation(invitation)
                        }
                    },
                    onError = { message ->
                        showScanner = false
                        scanError = message
                    },
                )
                Text(
                    stringResource(R.string.scanner_aim),
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                )
                TextButton(
                    onClick = { showScanner = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Text(stringResource(R.string.action_cancel), color = Color.White) }
            }
        }
        }
    }
}
