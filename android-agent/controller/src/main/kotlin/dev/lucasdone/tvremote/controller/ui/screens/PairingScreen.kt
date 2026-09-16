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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lucasdone.tvremote.controller.R
import dev.lucasdone.tvremote.controller.ui.ControllerUiState
import dev.lucasdone.tvremote.controller.ui.ControllerViewModel
import dev.lucasdone.tvremote.controller.ui.NoticeBanner
import dev.lucasdone.tvremote.controller.ui.PairingChannel
import dev.lucasdone.tvremote.controller.ui.PairingPhase
import dev.lucasdone.tvremote.controller.ui.ScreenHeader

@Composable
fun PairingScreen(state: ControllerUiState, viewModel: ControllerViewModel) {
    val pairing = state.pairing
    var code by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.screen_pairing),
            subtitle = pairing?.let { "${it.host}:${it.port}" },
            onBack = viewModel::cancelPairing,
        )
        NoticeBanner(state.notice, viewModel::dismissNotice)

        if (pairing == null) {
            Text(stringResource(R.string.pairing_finished), modifier = Modifier.padding(16.dp))
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                pairing.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                when (pairing.phase) {
                    PairingPhase.INPUT -> {
                        if (pairing.channel == PairingChannel.CODE) {
                            Text(stringResource(R.string.pairing_input_hint))
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                                label = { Text(stringResource(R.string.field_pairing_code)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.submitPairingCode(code) },
                                enabled = code.length == 6,
                            ) { Text(stringResource(R.string.action_connect_pair)) }
                        } else {
                            Text(stringResource(R.string.pairing_qr_started))
                        }
                    }
                    PairingPhase.AWAITING_TV_CONFIRMATION -> {
                        Text(stringResource(R.string.pairing_sas_prompt), fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(pairing.sas ?: "------", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pairing_sas_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::cancelPairing) { Text(stringResource(R.string.action_cancel_pairing)) }
                    }
                    PairingPhase.COMPLETING -> {
                        Text(stringResource(R.string.pairing_completing))
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = viewModel::cancelPairing) { Text(stringResource(R.string.action_back)) }
            }
        }
    }
}
