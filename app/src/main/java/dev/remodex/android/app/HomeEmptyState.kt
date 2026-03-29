package dev.remodex.android.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.remodex.android.R
import dev.remodex.android.model.ConnectionPhase
import dev.remodex.android.ui.theme.BorderLight
import dev.remodex.android.ui.theme.CardBg
import dev.remodex.android.ui.theme.CopperDeep
import dev.remodex.android.ui.theme.CopperLight
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.InkSecondary
import dev.remodex.android.ui.theme.InkTertiary
import dev.remodex.android.ui.theme.LightBg
import dev.remodex.android.ui.theme.SignalGreen
import dev.remodex.android.ui.theme.WarningAmber

@Composable
fun HomeEmptyState(
    connectionPhase: ConnectionPhase,
    hasTrustedMac: Boolean,
    isReconnecting: Boolean,
    statusMessage: String?,
    savedMacName: String?,
    savedMacDetail: String?,
    hasDeviceHistory: Boolean = false,
    onReconnect: () -> Unit,
    onPair: () -> Unit,
    onScanNewQr: () -> Unit,
    onForgetPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (shouldShowHomeOnboarding(hasTrustedMac = hasTrustedMac, hasDeviceHistory = hasDeviceHistory)) {
        OnboardingGuide(
            connectionPhase = connectionPhase,
            isReconnecting = isReconnecting,
            statusMessage = statusMessage,
            onPair = onPair,
            modifier = modifier,
        )
        return
    }
    val isBusy = connectionPhase == ConnectionPhase.Connecting
        || connectionPhase == ConnectionPhase.Handshaking
        || connectionPhase == ConnectionPhase.Syncing
        || isReconnecting

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val primaryActionTitle = when {
        isReconnecting -> "Reconnecting..."
        connectionPhase == ConnectionPhase.Connecting -> "Reconnecting..."
        connectionPhase == ConnectionPhase.Handshaking -> "Verifying..."
        connectionPhase == ConnectionPhase.Syncing -> "Syncing..."
        hasTrustedMac -> "Reconnect"
        else -> "Scan QR Code"
    }
    val showSecondaryActions = isBusy || (hasTrustedMac && connectionPhase != ConnectionPhase.Ready)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 280.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.androidremodex),
                contentDescription = "Remodex",
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(22.dp)),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                val dotColor = when {
                    isBusy -> WarningAmber
                    connectionPhase == ConnectionPhase.Ready -> SignalGreen
                    else -> InkTertiary
                }
                Surface(
                    shape = CircleShape,
                    color = dotColor,
                    modifier = Modifier
                        .size(6.dp)
                        .alpha(if (isBusy) pulseAlpha else 1f),
                ) {
                    if (isBusy) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(pulseAlpha),
                        )
                    }
                }

                Text(
                    text = when {
                        isReconnecting -> "Reconnecting..."
                        connectionPhase == ConnectionPhase.Connecting -> "Connecting"
                        connectionPhase == ConnectionPhase.Handshaking -> "Verifying"
                        connectionPhase == ConnectionPhase.Syncing -> "Syncing"
                        connectionPhase == ConnectionPhase.Ready -> "Connected"
                        else -> "Offline"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = InkSecondary),
                )
            }

            if (hasTrustedMac && savedMacName != null) {
                SavedMacSummaryCard(
                    name = savedMacName,
                    detail = savedMacDetail,
                )
            }

            statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Button(
                onClick = if (hasTrustedMac) onReconnect else onPair,
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CopperDeep,
                    contentColor = LightBg,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Text(primaryActionTitle)
            }

            if (showSecondaryActions) {
                TextButton(
                    onClick = onScanNewQr,
                    enabled = true,
                ) {
                    Text(
                        text = "Scan New QR Code",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                    )
                }

                if (hasTrustedMac) {
                    TextButton(
                        onClick = onForgetPair,
                        enabled = true,
                    ) {
                        Text(
                            text = "Forget Pair",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary,
                        )
                    }
                }
            }
        }
    }
}

internal fun shouldShowHomeOnboarding(
    hasTrustedMac: Boolean,
    @Suppress("UNUSED_PARAMETER") hasDeviceHistory: Boolean,
): Boolean = !hasTrustedMac

@Composable
private fun OnboardingGuide(
    connectionPhase: ConnectionPhase,
    isReconnecting: Boolean,
    statusMessage: String?,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = connectionPhase == ConnectionPhase.Connecting
        || connectionPhase == ConnectionPhase.Handshaking
        || isReconnecting

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 340.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.androidremodex),
                contentDescription = "Remodex",
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )

            Text(
                text = "Welcome to Remodex",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )

            Text(
                text = "Connect to your computer to start coding conversations.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            // Setup steps
            OnboardingStep(
                number = "1",
                title = "Install the bridge on your computer",
                description = "Run the Remodex bridge on the device you want to connect to.",
            )
            OnboardingStep(
                number = "2",
                title = "Start the bridge",
                description = "Launch the bridge — it will show a QR code when ready.",
            )
            OnboardingStep(
                number = "3",
                title = "Scan the QR code",
                description = "Tap the button below and point your camera at the code.",
            )

            statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Button(
                onClick = onPair,
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CopperDeep,
                    contentColor = LightBg,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(if (isBusy) "Connecting..." else "Scan QR Code to Pair")
            }
        }
    }
}

@Composable
private fun OnboardingStep(
    number: String,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = CopperLight,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelMedium,
                    color = CopperDeep,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary,
            )
        }
    }
}

@Composable
private fun SavedMacSummaryCard(
    name: String,
    detail: String?,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "SAVED DEVICE",
                style = MaterialTheme.typography.labelSmall,
                color = InkSecondary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.04f),
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("D", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                    )
                    detail?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary,
                        )
                    }
                }
            }
        }
    }
}
