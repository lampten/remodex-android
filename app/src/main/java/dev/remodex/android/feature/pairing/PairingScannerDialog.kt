package dev.remodex.android.feature.pairing

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PairingScannerDialog(
    onDismiss: () -> Unit,
    onPayloadScanned: (PairingQrPayload) -> Unit,
    isTransitioning: Boolean = false,
    transitionTitle: String = "Connecting to your device",
    transitionBody: String = "Please wait while Remodex opens the connection.",
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanLock = remember { AtomicBoolean(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var scannerMessage by remember { mutableStateOf<PairingStatusMessage?>(null) }
    val latestOnPayloadScanned by rememberUpdatedState(onPayloadScanned)

    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { barcodeScanner.close() }
            analyzerExecutor.shutdown()
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val view = previewView
        if (view == null) {
            onDispose { }
        } else {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            val listener = Runnable {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(analyzerExecutor) { imageProxy ->
                            analyzeQrFrame(
                                imageProxy = imageProxy,
                                barcodeScanner = barcodeScanner,
                                scanLock = scanLock,
                                mainExecutor = mainExecutor,
                                onResult = { result ->
                                    when (result) {
                                        is PairingQrValidationResult.Success -> {
                                            latestOnPayloadScanned(result.payload)
                                        }
                                        is PairingQrValidationResult.Invalid -> {
                                            scannerMessage = PairingStatusMessage(
                                                tone = PairingStatusTone.Error,
                                                title = "Scan rejected",
                                                body = result.message,
                                            )
                                        }
                                        is PairingQrValidationResult.UpdateRequired -> {
                                            scannerMessage = PairingStatusMessage(
                                                tone = PairingStatusTone.UpdateRequired,
                                                title = "Mac bridge update required",
                                                body = result.message,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
            cameraProviderFuture.addListener(listener, mainExecutor)

            onDispose {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isTransitioning) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isTransitioning,
            dismissOnClickOutside = false,
        ),
    ) {
        if (isTransitioning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF8C4215),
                            strokeWidth = 3.dp,
                        )
                        Text(
                            text = transitionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF1A1A1A),
                        )
                        Text(
                            text = transitionBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF6B6B6B),
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = {
                        PreviewView(it).also { view ->
                            view.scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = view
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.size(260.dp),
                            shape = RoundedCornerShape(28.dp),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.75f)),
                        ) {}
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.58f),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Scan QR from Remodex on your Mac",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                            Text(
                                text = "Hold the QR inside the frame. Expired or incompatible codes will be rejected automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
            }
        }
    }

    scannerMessage?.let { message ->
        AlertDialog(
            onDismissRequest = {
                scannerMessage = null
                scanLock.set(false)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scannerMessage = null
                        scanLock.set(false)
                    },
                ) {
                    Text("Keep scanning")
                }
            },
            title = { Text(message.title) },
            text = { Text(message.body) },
        )
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun analyzeQrFrame(
    imageProxy: androidx.camera.core.ImageProxy,
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    scanLock: AtomicBoolean,
    mainExecutor: java.util.concurrent.Executor,
    onResult: (PairingQrValidationResult) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    if (!scanLock.compareAndSet(false, true)) {
        imageProxy.close()
        return
    }

    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    barcodeScanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            val rawValue = barcodes.firstNotNullOfOrNull { it.rawValue }
            if (rawValue != null) {
                val validationResult = validatePairingQrCode(rawValue)
                mainExecutor.execute {
                    onResult(validationResult)
                }
            } else {
                scanLock.set(false)
            }
        }
        .addOnFailureListener {
            scanLock.set(false)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
