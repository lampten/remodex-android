package dev.remodex.android.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.remodex.android.BuildConfig
import dev.remodex.android.feature.pairing.DeviceHistoryEntry
import dev.remodex.android.feature.pairing.PairingQrPayload
import dev.remodex.android.feature.pairing.PairingStatusMessage
import dev.remodex.android.feature.pairing.PairingStatusTone
import dev.remodex.android.feature.pairing.RelayBootstrapPhase
import dev.remodex.android.feature.pairing.displayLabel
import dev.remodex.android.feature.pairing.expiryLabel
import dev.remodex.android.feature.pairing.relayDisplayLabel
import dev.remodex.android.feature.pairing.relayLabel
import dev.remodex.android.model.AccessMode
import dev.remodex.android.model.BridgeSnapshot
import dev.remodex.android.model.ConnectionPhase
import dev.remodex.android.model.ModelOption
import dev.remodex.android.model.ReasoningDisplayOption
import dev.remodex.android.model.ServiceTier
import dev.remodex.android.model.modelDisplayTitle
import dev.remodex.android.model.reasoningEffortTitle
import dev.remodex.android.ui.theme.CardBg
import dev.remodex.android.ui.theme.CopperDeep
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.InkSecondary
import dev.remodex.android.ui.theme.InkTertiary
import dev.remodex.android.ui.theme.SignalGreen
import dev.remodex.android.ui.theme.WarningAmber

@Composable
fun SettingsScreen(
    bridge: BridgeSnapshot,
    pairingStatusMessage: PairingStatusMessage?,
    stagedPairingPayload: PairingQrPayload?,
    acceptedPairingPayload: PairingQrPayload?,
    stagedReconnectDevice: DeviceHistoryEntry?,
    bootstrapPhase: RelayBootstrapPhase,
    deviceHistory: List<DeviceHistoryEntry>,
    activeDeviceId: String?,
    availableModels: List<ModelOption>,
    isLoadingModels: Boolean,
    selectedModelId: String?,
    selectedModelTitle: String,
    currentReasoningOptions: List<ReasoningDisplayOption>,
    selectedReasoningEffort: String?,
    selectedReasoningTitle: String,
    effectiveReasoningEffort: String,
    selectedServiceTier: ServiceTier?,
    selectedAccessMode: AccessMode,
    onSelectModel: (String?) -> Unit,
    onSelectReasoning: (String?) -> Unit,
    onSelectServiceTier: (ServiceTier?) -> Unit,
    onSelectAccessMode: (AccessMode) -> Unit,
    onBack: () -> Unit,
    onReconnectDevice: (DeviceHistoryEntry) -> Unit,
    onRenameDevice: (String, String) -> Unit,
    onForgetDevice: (String) -> Unit,
    onOpenScanner: () -> Unit,
    onStartBootstrap: () -> Unit,
    onClearPairingDraft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = bootstrapPhase == RelayBootstrapPhase.Connecting || bootstrapPhase == RelayBootstrapPhase.Handshaking
    val pendingScannedPayload = stagedPairingPayload ?: if (isBusy) acceptedPairingPayload else null

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Back + title
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
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
        }

        // Devices card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CardBg,
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Devices",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )

                // Status pill
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dotColor = when (bridge.phase) {
                        ConnectionPhase.Ready -> SignalGreen
                        ConnectionPhase.Connecting, ConnectionPhase.Handshaking, ConnectionPhase.Syncing -> WarningAmber
                        else -> InkTertiary
                    }
                    Surface(shape = CircleShape, color = dotColor, modifier = Modifier.size(8.dp)) {}
                    Text(
                        text = when (bridge.phase) {
                            ConnectionPhase.NotPaired -> "Not paired"
                            ConnectionPhase.TrustedMac -> "Saved trust"
                            ConnectionPhase.PairingReady -> "Payload ready"
                            ConnectionPhase.Connecting -> "Connecting"
                            ConnectionPhase.Handshaking -> "Handshaking"
                            ConnectionPhase.Syncing -> "Syncing"
                            ConnectionPhase.Ready -> "Connected"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary,
                    )
                }

                // Device list inline
                deviceHistory.forEach { device ->
                    val isActive = device.macDeviceId == activeDeviceId
                    val isConnected = isActive && bridge.phase == ConnectionPhase.Ready
                    val isStagedTarget = stagedReconnectDevice?.macDeviceId == device.macDeviceId
                    SettingsDeviceRow(
                        device = device,
                        isActive = isActive,
                        isConnected = isConnected,
                        isStagedTarget = isStagedTarget,
                        isBusy = isBusy,
                        onReconnect = { onReconnectDevice(device) },
                        onRename = { newName -> onRenameDevice(device.macDeviceId, newName) },
                        onForget = { onForgetDevice(device.macDeviceId) },
                    )
                }

                if (deviceHistory.isEmpty()) {
                    Text(
                        text = "No devices paired yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTertiary,
                    )
                }

                stagedReconnectDevice?.let { device ->
                    HorizontalDivider(color = dev.remodex.android.ui.theme.Divider)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Switch to device",
                                style = MaterialTheme.typography.titleSmall,
                                color = Ink,
                            )
                            Text(
                                text = device.displayLabel(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkSecondary,
                            )
                            Text(
                                text = "Relay: ${device.relayLabel()}",
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
                                        when {
                                            bootstrapPhase == RelayBootstrapPhase.Connecting -> "Connecting..."
                                            bootstrapPhase == RelayBootstrapPhase.Handshaking -> "Verifying..."
                                            else -> "Connect"
                                        }
                                    )
                                }
                                OutlinedButton(
                                    onClick = onClearPairingDraft,
                                    enabled = !isBusy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                } ?: pendingScannedPayload?.let { payload ->
                    HorizontalDivider(color = dev.remodex.android.ui.theme.Divider)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Scanned device",
                                style = MaterialTheme.typography.titleSmall,
                                color = Ink,
                            )
                            Text(
                                text = "Relay: ${payload.relayDisplayLabel()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary,
                            )
                            Text(
                                text = "Expires: ${payload.expiryLabel()}",
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
                                        when {
                                            bootstrapPhase == RelayBootstrapPhase.Connecting -> "Connecting..."
                                            bootstrapPhase == RelayBootstrapPhase.Handshaking -> "Verifying..."
                                            else -> "Connect"
                                        },
                                    )
                                }
                                OutlinedButton(
                                    onClick = onClearPairingDraft,
                                    enabled = !isBusy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }

                pairingStatusMessage?.let { message ->
                    val statusColor = when (message.tone) {
                        PairingStatusTone.Success -> InkSecondary
                        PairingStatusTone.Error -> MaterialTheme.colorScheme.error
                        PairingStatusTone.UpdateRequired -> WarningAmber
                    }
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                    )
                }

                // Pair new device button
                OutlinedButton(
                    onClick = onOpenScanner,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pair New Device")
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
                    text = "Runtime Defaults",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Text(
                    text = "These defaults apply to new or unchanged chats. Individual chats can override speed and reasoning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                )
                RuntimeDropdownRow(
                    label = "Model",
                    value = selectedModelTitle,
                    placeholder = if (isLoadingModels) "Loading models..." else "Select model",
                ) { dismiss ->
                    if (isLoadingModels) {
                        DropdownMenuItem(
                            text = { Text("Loading models...") },
                            onClick = {},
                            enabled = false,
                        )
                    } else if (availableModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No models available") },
                            onClick = {},
                            enabled = false,
                        )
                    } else {
                        availableModels.forEach { model ->
                            val isSelected = model.id == selectedModelId
                            DropdownMenuItem(
                                text = { Text(modelDisplayTitle(model)) },
                                onClick = {
                                    onSelectModel(model.id)
                                    dismiss()
                                },
                                trailingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else {
                                    null
                                },
                                colors = MenuDefaults.itemColors(
                                    textColor = if (isSelected) Ink else InkSecondary,
                                    trailingIconColor = CopperDeep,
                                ),
                            )
                        }
                    }
                }
                RuntimeDropdownRow(
                    label = "Reasoning",
                    value = selectedReasoningTitle,
                    placeholder = "Auto",
                ) { dismiss ->
                    val usesAutomaticReasoning = selectedReasoningEffort.isNullOrBlank()
                    val resolvedReasoningEffort = selectedReasoningEffort ?: effectiveReasoningEffort
                    DropdownMenuItem(
                        text = { Text("Auto") },
                        onClick = {
                            onSelectReasoning(null)
                            dismiss()
                        },
                        trailingIcon = if (usesAutomaticReasoning) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = if (usesAutomaticReasoning) Ink else InkSecondary,
                            trailingIconColor = CopperDeep,
                        ),
                    )
                    currentReasoningOptions.forEach { option ->
                        val isSelected = resolvedReasoningEffort == option.effort
                        DropdownMenuItem(
                            text = { Text(option.title) },
                            onClick = {
                                onSelectReasoning(option.effort)
                                dismiss()
                            },
                            trailingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else {
                                null
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = if (isSelected) Ink else InkSecondary,
                                trailingIconColor = CopperDeep,
                            ),
                        )
                    }
                }
                RuntimeSegmentRow(
                    label = "Speed",
                    selectedServiceTier = selectedServiceTier,
                    onSelectServiceTier = onSelectServiceTier,
                )
                RuntimeDropdownRow(
                    label = "Permissions",
                    value = selectedAccessMode.menuTitle,
                    placeholder = selectedAccessMode.menuTitle,
                ) { dismiss ->
                    AccessMode.entries.forEach { mode ->
                        val isSelected = mode == selectedAccessMode
                        DropdownMenuItem(
                            text = { Text(mode.menuTitle) },
                            onClick = {
                                onSelectAccessMode(mode)
                                dismiss()
                            },
                            trailingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else {
                                null
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = if (isSelected) Ink else InkSecondary,
                                trailingIconColor = CopperDeep,
                            ),
                        )
                    }
                }
            }
        }

        // Build info
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CardBg,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Build",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                InfoRow(label = "Version", value = BuildConfig.VERSION_NAME)
                InfoRow(label = "Mode", value = "Local-first")
                InfoRow(label = "Hosted default", value = "None")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = InkTertiary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Ink,
        )
    }
}

@Composable
private fun RuntimeDropdownRow(
    label: String,
    value: String,
    placeholder: String,
    content: @Composable ((dismiss: () -> Unit) -> Unit),
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = InkTertiary,
        )
        Box {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = value.ifBlank { placeholder },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (value.isBlank()) InkTertiary else Ink,
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = InkTertiary,
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color.White),
            ) {
                content { expanded = false }
            }
        }
    }
}

@Composable
private fun RuntimeSegmentRow(
    label: String,
    selectedServiceTier: ServiceTier?,
    onSelectServiceTier: (ServiceTier?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = InkTertiary,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimeSegmentChip(
                label = "Normal",
                selected = selectedServiceTier == null,
                onClick = { onSelectServiceTier(null) },
            )
            RuntimeSegmentChip(
                label = "Fast",
                selected = selectedServiceTier == ServiceTier.Fast,
                onClick = { onSelectServiceTier(ServiceTier.Fast) },
            )
        }
    }
}

@Composable
private fun RuntimeSegmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) CopperDeep.copy(alpha = 0.12f) else Color.White,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) CopperDeep else InkSecondary,
        )
    }
}

@Composable
private fun SettingsDeviceRow(
    device: DeviceHistoryEntry,
    isActive: Boolean,
    isConnected: Boolean = isActive,
    isStagedTarget: Boolean = false,
    isBusy: Boolean,
    onReconnect: () -> Unit,
    onRename: (String) -> Unit,
    onForget: () -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember(device.customName, device.macDeviceId) {
        mutableStateOf(device.customName ?: "")
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) SignalGreen.copy(alpha = 0.1f)
                            else Color.Black.copy(alpha = 0.04f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Laptop,
                        contentDescription = null,
                        tint = if (isConnected) SignalGreen else InkTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    if (isEditing) {
                        BasicTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
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
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    } else {
                        Text(
                            text = device.displayLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = device.relayLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!isEditing) {
                    IconButton(
                        onClick = { isEditing = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename",
                            tint = InkTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            if (!isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onReconnect,
                        enabled = !isBusy && !isStagedTarget,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CopperDeep,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(
                            when {
                                isStagedTarget -> "Selected"
                                isActive && isBusy -> "Connecting..."
                                isActive -> "Reconnect"
                                else -> "Switch"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = onForget,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Forget", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}
