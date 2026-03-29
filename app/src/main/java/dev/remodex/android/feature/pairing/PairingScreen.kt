package dev.remodex.android.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.remodex.android.model.BridgeSnapshot
import dev.remodex.android.model.ConnectionPhase
import dev.remodex.android.ui.theme.CardBg
import dev.remodex.android.ui.theme.CopperDeep
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.InkSecondary

@Composable
fun PairingScreen(
    bridge: BridgeSnapshot,
    pairingStatusMessage: PairingStatusMessage?,
    stagedPairingPayload: PairingQrPayload?,
    trustedReconnectRecord: TrustedReconnectRecord?,
    bootstrapPhase: RelayBootstrapPhase,
    onClearPairing: () -> Unit,
    onStartBootstrap: () -> Unit,
    onReconnectTrustedMac: () -> Unit,
    onForgetTrustedMac: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenConversations: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = bootstrapPhase == RelayBootstrapPhase.Connecting || bootstrapPhase == RelayBootstrapPhase.Handshaking

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Ink,
                )
            }
            Text(
                text = "Connect",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
        }

        if (bridge.phase == ConnectionPhase.Ready) {
            StatusCard(
                title = "Connected to your Mac",
                body = "Your encrypted link is live.",
                tone = PairingStatusTone.Success,
            )
            Button(
                onClick = onOpenConversations,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CopperDeep, contentColor = Color.White),
            ) {
                Text("Open conversations")
            }
        }

        trustedReconnectRecord?.let { record ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Saved Mac",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                    )
                    Text(
                        text = "Relay: ${record.relayDisplayLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onReconnectTrustedMac,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CopperDeep, contentColor = Color.White),
                        ) {
                            Text(if (isBusy) "Reconnecting..." else "Reconnect")
                        }
                        OutlinedButton(
                            onClick = onForgetTrustedMac,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Forget")
                        }
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CardBg,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Pair with your device",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Text(
                    text = "Scan the QR code shown by Remodex on your computer to start the connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                )
                Button(
                    onClick = onOpenScanner,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CopperDeep, contentColor = Color.White),
                ) {
                    Text("Scan QR Code")
                }
            }
        }

        pairingStatusMessage?.let { message ->
            StatusCard(
                title = message.title,
                body = message.body,
                tone = message.tone,
            )
        }

        stagedPairingPayload?.let { payload ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = CardBg,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Scanned device",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                    )
                    Text(
                        text = "Relay: ${payload.relayDisplayLabel()} | Expires: ${payload.expiryLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onStartBootstrap,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CopperDeep, contentColor = Color.White),
                        ) {
                            Text(
                                when (bootstrapPhase) {
                                    RelayBootstrapPhase.Idle -> "Connect"
                                    RelayBootstrapPhase.Connecting -> "Connecting..."
                                    RelayBootstrapPhase.Handshaking -> "Verifying..."
                                    RelayBootstrapPhase.Verified -> "Connected"
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = onClearPairing,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    tone: PairingStatusTone,
) {
    val containerColor = when (tone) {
        PairingStatusTone.Success -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        PairingStatusTone.Error -> MaterialTheme.colorScheme.errorContainer
        PairingStatusTone.UpdateRequired -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    }
    val titleColor = when (tone) {
        PairingStatusTone.Success -> MaterialTheme.colorScheme.primary
        PairingStatusTone.Error -> MaterialTheme.colorScheme.error
        PairingStatusTone.UpdateRequired -> MaterialTheme.colorScheme.tertiary
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = InkSecondary)
        }
    }
}
