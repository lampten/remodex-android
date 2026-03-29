package dev.remodex.android.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.remodex.android.ui.theme.CopperDeep
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.InkSecondary
import dev.remodex.android.ui.theme.LightBg
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.remodex.android.model.ConnectionPhase
import dev.remodex.android.feature.pairing.PairingScannerDialog
import dev.remodex.android.feature.pairing.PairingScreen
import dev.remodex.android.feature.pairing.maskedMacDeviceLabel
import dev.remodex.android.feature.pairing.relayDisplayLabel
import dev.remodex.android.feature.settings.SettingsScreen
import dev.remodex.android.feature.threads.ConversationScreen
import dev.remodex.android.model.SampleShellData
import dev.remodex.android.ui.theme.RemodexTheme
import java.io.File

@Composable
fun RemodexAndroidApp() {
    RemodexTheme {
        val vm: AppViewModel = viewModel()
        val snackbarHostState = remember { SnackbarHostState() }
        val lifecycleOwner = LocalLifecycleOwner.current

        // Show snackbar messages from ViewModel
        LaunchedEffect(vm.snackbarMessage) {
            vm.snackbarMessage?.let {
                snackbarHostState.showSnackbar(it)
                vm.snackbarMessage = null
            }
        }

        // Foreground reconnect
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    vm.setAppInForeground(true)
                } else if (event == Lifecycle.Event.ON_STOP) {
                    vm.setAppInForeground(false)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0),
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
            ) {
                AppDrawerLayout(
                    vm = vm,
                    modifier = Modifier.fillMaxSize(),
                )

                if (shouldShowPairingTransitionOverlay(vm.bootstrapPhase, vm.isShowingLiveThreads)) {
                    PairingTransitionOverlay(
                        title = when (vm.bootstrapPhase) {
                            dev.remodex.android.feature.pairing.RelayBootstrapPhase.Handshaking -> "Verifying device"
                            else -> "Connecting to your device"
                        },
                        body = vm.pairingStatusMessage?.body
                            ?: "Please wait while Remodex opens the connection.",
                    )
                }
            }
        }

        // Device naming dialog — shown after first pairing a new device
        vm.pendingDeviceRenameId?.let { macDeviceId ->
            DeviceNamingDialog(
                suggestedName = vm.pendingDeviceRenameSuggestedName,
                onConfirm = { name -> vm.confirmDeviceRename(macDeviceId, name) },
                onDismiss = { vm.dismissDeviceRename() },
            )
        }
    }
}

@Composable
private fun PairingTransitionOverlay(
    title: String,
    body: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = LightBg,
            shadowElevation = 10.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 320.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(
                    color = CopperDeep,
                    strokeWidth = 3.dp,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                )
            }
        }
    }
}

@Composable
fun MainContent(
    vm: AppViewModel,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (vm.currentScreen) {
        AppScreen.Pairing -> {
            val context = LocalContext.current
            var showPairingScanner by remember { mutableStateOf(false) }
            var scannerStartedConnect by remember { mutableStateOf(false) }

            val pairingCameraPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    showPairingScanner = true
                } else {
                    vm.snackbarMessage = "Camera permission is required to scan the pairing QR."
                }
            }

            PairingScreen(
                bridge = vm.bridge,
                pairingStatusMessage = vm.pairingStatusMessage,
                stagedPairingPayload = vm.stagedPairingPayload,
                trustedReconnectRecord = vm.trustedReconnectRecord,
                bootstrapPhase = vm.bootstrapPhase,
                onClearPairing = { vm.clearPairing() },
                onStartBootstrap = { vm.startBootstrap() },
                onReconnectTrustedMac = { vm.reconnectTrustedMac() },
                onForgetTrustedMac = { vm.forgetTrustedMac() },
                onOpenScanner = {
                    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                        vm.snackbarMessage = "This device does not have a camera, so QR pairing is unavailable."
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        showPairingScanner = true
                    } else {
                        pairingCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onOpenConversations = { vm.navigateHome() },
                onBack = { vm.navigateHome() },
                modifier = modifier,
            )

            LaunchedEffect(showPairingScanner, scannerStartedConnect, vm.bootstrapPhase, vm.isShowingLiveThreads) {
                if (!showPairingScanner || !scannerStartedConnect) {
                    return@LaunchedEffect
                }
                val conversationUiReady = vm.isShowingLiveThreads &&
                    (vm.threadSummaries.isEmpty() || (vm.selectedThread != null && vm.selectedDetail != null))
                if (vm.bootstrapPhase == dev.remodex.android.feature.pairing.RelayBootstrapPhase.Idle) {
                    scannerStartedConnect = false
                    showPairingScanner = false
                } else if (conversationUiReady) {
                    scannerStartedConnect = false
                    showPairingScanner = false
                }
            }

            if (showPairingScanner) {
                PairingScannerDialog(
                    onDismiss = {
                        scannerStartedConnect = false
                        showPairingScanner = false
                    },
                    onPayloadScanned = { payload ->
                        scannerStartedConnect = true
                        vm.acceptScannedPairingPayload(payload, autoConnect = true)
                    },
                    isTransitioning = scannerStartedConnect,
                    transitionTitle = when (vm.bootstrapPhase) {
                        dev.remodex.android.feature.pairing.RelayBootstrapPhase.Handshaking -> "Verifying device"
                        else -> "Connecting to your device"
                    },
                    transitionBody = vm.pairingStatusMessage?.body
                        ?: "Please wait while Remodex opens the connection.",
                )
            }
        }

        AppScreen.Settings -> {
            val context = LocalContext.current
            var showPairingScanner by remember { mutableStateOf(false) }
            var scannerStartedConnect by remember { mutableStateOf(false) }

            val settingsCameraPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    showPairingScanner = true
                } else {
                    vm.snackbarMessage = "Camera permission is required to scan the pairing QR."
                }
            }

            SettingsScreen(
                bridge = vm.bridge,
                pairingStatusMessage = vm.pairingStatusMessage,
                stagedPairingPayload = vm.stagedPairingPayload,
                acceptedPairingPayload = vm.acceptedPairingPayload,
                stagedReconnectDevice = vm.stagedReconnectDevice,
                bootstrapPhase = vm.bootstrapPhase,
                deviceHistory = vm.deviceHistory,
                activeDeviceId = vm.trustedReconnectRecord?.macDeviceId,
                availableModels = vm.availableModels,
                isLoadingModels = vm.isLoadingModels,
                selectedModelId = vm.selectedModelId,
                selectedModelTitle = vm.selectedModelTitle,
                currentReasoningOptions = vm.currentReasoningOptions,
                selectedReasoningEffort = vm.defaultReasoningEffortSelection,
                selectedReasoningTitle = vm.globalSelectedReasoningTitle,
                effectiveReasoningEffort = vm.effectiveGlobalReasoningEffort,
                selectedServiceTier = vm.defaultServiceTierSelection,
                selectedAccessMode = vm.selectedAccessMode,
                onSelectModel = { vm.selectModel(it) },
                onSelectReasoning = { vm.selectGlobalReasoningEffort(it) },
                onSelectServiceTier = { vm.selectGlobalServiceTier(it) },
                onSelectAccessMode = { vm.selectAccessMode(it) },
                onBack = { vm.navigateHome() },
                onReconnectDevice = { entry -> vm.reconnectDevice(entry) },
                onRenameDevice = { macId, name -> vm.renameDevice(macId, name) },
                onForgetDevice = { macId -> vm.forgetDevice(macId) },
                onOpenScanner = {
                    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                        vm.snackbarMessage = "This device does not have a camera, so QR pairing is unavailable."
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        showPairingScanner = true
                    } else {
                        settingsCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onStartBootstrap = { vm.startBootstrap() },
                onClearPairingDraft = { vm.clearPairing() },
                modifier = modifier,
            )

            LaunchedEffect(showPairingScanner, scannerStartedConnect, vm.bootstrapPhase, vm.isShowingLiveThreads) {
                if (!showPairingScanner || !scannerStartedConnect) {
                    return@LaunchedEffect
                }
                val settingsUiReady = vm.bootstrapPhase == dev.remodex.android.feature.pairing.RelayBootstrapPhase.Verified
                if (vm.bootstrapPhase == dev.remodex.android.feature.pairing.RelayBootstrapPhase.Idle) {
                    scannerStartedConnect = false
                    showPairingScanner = false
                } else if (settingsUiReady) {
                    scannerStartedConnect = false
                    showPairingScanner = false
                }
            }

            if (showPairingScanner) {
                PairingScannerDialog(
                    onDismiss = {
                        scannerStartedConnect = false
                        showPairingScanner = false
                    },
                    onPayloadScanned = { payload ->
                        scannerStartedConnect = true
                        vm.acceptScannedPairingPayload(payload, autoConnect = true)
                    },
                    isTransitioning = scannerStartedConnect,
                    transitionTitle = when (vm.bootstrapPhase) {
                        dev.remodex.android.feature.pairing.RelayBootstrapPhase.Handshaking -> "Verifying device"
                        else -> "Connecting to your device"
                    },
                    transitionBody = vm.pairingStatusMessage?.body
                        ?: "Please wait while Remodex opens the connection.",
                )
            }
        }

        AppScreen.Home -> {
            val appContext = LocalContext.current
            var showPairingScanner by remember { mutableStateOf(false) }
            var scannerStartedConnect by remember { mutableStateOf(false) }
            val homeCameraPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    showPairingScanner = true
                } else {
                    vm.snackbarMessage = "Camera permission is required to scan the pairing QR."
                }
            }
            val requestPairingScan = {
                if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                    vm.snackbarMessage = "This device does not have a camera, so QR pairing is unavailable."
                } else if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    showPairingScanner = true
                } else {
                    homeCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            LaunchedEffect(showPairingScanner, scannerStartedConnect, vm.bootstrapPhase, vm.isShowingLiveThreads) {
                if (!showPairingScanner || !scannerStartedConnect) {
                    return@LaunchedEffect
                }
                val conversationUiReady = vm.isShowingLiveThreads &&
                    (vm.threadSummaries.isEmpty() || (vm.selectedThread != null && vm.selectedDetail != null))
                if (vm.bootstrapPhase == dev.remodex.android.feature.pairing.RelayBootstrapPhase.Idle) {
                    scannerStartedConnect = false
                    showPairingScanner = false
                } else if (conversationUiReady) {
                    scannerStartedConnect = false
                    showPairingScanner = false
                }
            }
            val selectedThread = vm.selectedThread
            val selectedDetail = vm.selectedDetail
            if (selectedThread != null && selectedDetail != null && vm.isShowingLiveThreads) {
                // Image picker launchers
                val context = LocalContext.current

                val galleryLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent(),
                ) { uri: Uri? ->
                    uri?.let(vm::addAttachmentFromUri)
                }

                var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
                val cameraLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.TakePicture(),
                ) { success ->
                    if (success) {
                        cameraImageUri?.let(vm::addAttachmentFromUri)
                    }
                }

                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        val photoFile = File(
                            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                            "remodex_${System.currentTimeMillis()}.jpg",
                        )
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            photoFile,
                        )
                        cameraImageUri = uri
                        cameraLauncher.launch(uri)
                    } else {
                        vm.snackbarMessage = "Camera permission is required to take photos."
                    }
                }

                ConversationScreen(
                    summary = selectedThread,
                    detail = selectedDetail,
                    draftMessage = vm.draftMessage,
                    isLive = vm.isShowingLiveThreads,
                    isSending = vm.isSendingTurn,
                    isStopping = vm.isStoppingTurn,
                    isContinuing = vm.isContinuingTurn,
                    availableModels = vm.availableModels,
                    isLoadingModels = vm.isLoadingModels,
                    selectedModelId = vm.selectedModelId,
                    selectedModelTitle = vm.selectedModelTitle,
                    selectedReasoningTitle = vm.selectedReasoningTitle,
                    currentReasoningOptions = vm.currentReasoningOptions,
                    effectiveReasoningEffort = vm.effectiveReasoningEffort,
                    selectedReasoningEffort = vm.selectedReasoningEffort,
                    selectedServiceTier = vm.selectedServiceTier,
                    isReasoningOverrideActive = vm.isCurrentThreadReasoningOverridden,
                    isServiceTierOverrideActive = vm.isCurrentThreadServiceTierOverridden,
                    onSelectModel = { vm.selectModel(it) },
                    onSelectReasoning = { vm.selectReasoningEffort(it) },
                    onSelectServiceTier = { vm.selectServiceTier(it) },
                    onUseDefaultReasoning = { vm.useDefaultReasoningForCurrentThread() },
                    onUseDefaultServiceTier = { vm.useDefaultServiceTierForCurrentThread() },
                    isPlanModeArmed = vm.isPlanModeArmed,
                    onTogglePlanMode = { vm.togglePlanMode() },
                    selectedAccessMode = vm.selectedAccessMode,
                    onSelectAccessMode = { vm.selectAccessMode(it) },
                    currentGitBranch = vm.currentGitBranch,
                    contextWindowUsage = vm.contextWindowUsage,
                    pendingAttachments = vm.pendingAttachments,
                    onRemoveAttachment = { vm.removeAttachment(it) },
                    onPickFromGallery = { galleryLauncher.launch("image/*") },
                    onTakePhoto = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            val photoFile = File(
                                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                                "remodex_${System.currentTimeMillis()}.jpg",
                            )
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                photoFile,
                            )
                            cameraImageUri = uri
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onDraftChange = { vm.updateDraftMessage(it) },
                    onSend = { vm.sendTurn() },
                    onStop = { vm.stopTurn() },
                    onContinue = { vm.continueTurn() },
                    onMenuClick = onOpenDrawer,
                    modifier = modifier,
                )
            } else {
                HomeEmptyState(
                    connectionPhase = vm.bridge.phase,
                    hasTrustedMac = vm.trustedReconnectRecord != null,
                    isReconnecting = vm.isRecoveringTrustedLiveSession,
                    statusMessage = vm.pairingStatusMessage?.body,
                    savedMacName = vm.trustedReconnectRecord?.let { vm.bridge.deviceName },
                    savedMacDetail = vm.trustedReconnectRecord?.let { vm.bridge.relayLabel },
                    hasDeviceHistory = vm.deviceHistory.isNotEmpty(),
                    onReconnect = { vm.reconnectTrustedMac() },
                    onPair = requestPairingScan,
                    onScanNewQr = requestPairingScan,
                    onForgetPair = { vm.forgetTrustedMac() },
                    modifier = modifier,
                )
            }

            if (showPairingScanner) {
                PairingScannerDialog(
                    onDismiss = {
                        scannerStartedConnect = false
                        showPairingScanner = false
                    },
                    onPayloadScanned = { payload ->
                        scannerStartedConnect = true
                        vm.acceptScannedPairingPayload(payload, autoConnect = true)
                    },
                    isTransitioning = scannerStartedConnect,
                    transitionTitle = when (vm.bootstrapPhase) {
                        dev.remodex.android.feature.pairing.RelayBootstrapPhase.Handshaking -> "Verifying device"
                        else -> "Connecting to your device"
                    },
                    transitionBody = vm.pairingStatusMessage?.body
                        ?: "Please wait while Remodex opens the connection.",
                )
            }
        }
    }
}

@Composable
private fun DeviceNamingDialog(
    suggestedName: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(suggestedName) { mutableStateOf(suggestedName ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = LightBg,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Name this device",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Text(
                    text = "Give this device a friendly name so you can find it easily.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                )
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { onConfirm(name) },
                    ),
                    decorationBox = { innerTextField ->
                        if (name.isEmpty()) {
                            Text(
                                text = "e.g. Josh's MacBook",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Black.copy(alpha = 0.3f),
                            )
                        }
                        innerTextField()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color.Black.copy(alpha = 0.04f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Skip", color = InkSecondary)
                    }
                    Button(
                        onClick = { onConfirm(name) },
                        enabled = name.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CopperDeep,
                            contentColor = LightBg,
                        ),
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
