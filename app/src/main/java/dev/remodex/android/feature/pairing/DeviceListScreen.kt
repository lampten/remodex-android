package dev.remodex.android.feature.pairing

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.remodex.android.ui.theme.BorderLight
import dev.remodex.android.ui.theme.CopperDeep
import dev.remodex.android.ui.theme.Divider
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.InkSecondary
import dev.remodex.android.ui.theme.InkTertiary
import dev.remodex.android.ui.theme.LightBg
import dev.remodex.android.ui.theme.SignalGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeviceListScreen(
    devices: List<DeviceHistoryEntry>,
    activeDeviceId: String?,
    isActiveDeviceConnected: Boolean = false,
    onReconnect: (DeviceHistoryEntry) -> Unit,
    onRename: (String, String) -> Unit,
    onForget: (String) -> Unit,
    onScanNewQr: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBg),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Ink,
                )
            }
            Text(
                text = "Devices",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
        }

        HorizontalDivider(color = Divider)

        if (devices.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Laptop,
                        contentDescription = null,
                        tint = InkTertiary,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "No devices paired yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = InkSecondary,
                    )
                    Text(
                        text = "Scan a QR code from your Mac to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkTertiary,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // Active device section
                val activeDevice = devices.find { it.macDeviceId == activeDeviceId }
                if (activeDevice != null) {
                    item(key = "active-header") {
                        Text(
                            text = if (isActiveDeviceConnected) "CONNECTED" else "ACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActiveDeviceConnected) SignalGreen else InkTertiary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    item(key = "active-${activeDevice.macDeviceId}") {
                        DeviceRow(
                            device = activeDevice,
                            isActive = isActiveDeviceConnected,
                            onReconnect = { onReconnect(activeDevice) },
                            onRename = { newName -> onRename(activeDevice.macDeviceId, newName) },
                            onForget = { onForget(activeDevice.macDeviceId) },
                        )
                        HorizontalDivider(
                            color = Divider,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                // History section
                val historyDevices = devices.filter { it.macDeviceId != activeDeviceId }
                if (historyDevices.isNotEmpty()) {
                    item(key = "history-header") {
                        Text(
                            text = "HISTORY",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkTertiary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(historyDevices, key = { it.macDeviceId }) { device ->
                        DeviceRow(
                            device = device,
                            isActive = false,
                            onReconnect = { onReconnect(device) },
                            onRename = { newName -> onRename(device.macDeviceId, newName) },
                            onForget = { onForget(device.macDeviceId) },
                        )
                        HorizontalDivider(
                            color = Divider,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }

        // Bottom action: Scan new QR
        HorizontalDivider(color = Divider)
        Button(
            onClick = onScanNewQr,
            colors = ButtonDefaults.buttonColors(
                containerColor = CopperDeep,
                contentColor = LightBg,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Pair New Device")
        }
    }
}

@Composable
private fun DeviceRow(
    device: DeviceHistoryEntry,
    isActive: Boolean,
    onReconnect: () -> Unit,
    onRename: (String) -> Unit,
    onForget: () -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember(device.customName, device.macDeviceId) {
        mutableStateOf(device.customName ?: "")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        color = LightBg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Device icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) SignalGreen.copy(alpha = 0.1f)
                            else Color.Black.copy(alpha = 0.04f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Laptop,
                        contentDescription = null,
                        tint = if (isActive) SignalGreen else InkTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Device info
                Column(modifier = Modifier.weight(1f)) {
                    if (isEditing) {
                        BasicTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                            cursorBrush = SolidColor(Ink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    isEditing = false
                                    onRename(editText)
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Color.Black.copy(alpha = 0.04f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    } else {
                        Text(
                            text = device.displayLabel(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = buildString {
                            append(device.relayLabel())
                            device.lastUsedAt?.let { ts ->
                                append(" · ${formatTimestamp(ts)}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Actions
                if (!isEditing) {
                    IconButton(
                        onClick = { isEditing = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename",
                            tint = InkTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Reconnect / Forget actions
            if (!isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onReconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CopperDeep,
                            contentColor = LightBg,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Reconnect", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                    TextButton(
                        onClick = onForget,
                    ) {
                        Text(
                            "Forget",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary,
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMs
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}
