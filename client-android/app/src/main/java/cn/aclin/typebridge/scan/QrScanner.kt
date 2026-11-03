package cn.aclin.typebridge.scan

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX + ML Kit 二维码预览。命中一次后停止回调。 */
@Composable
fun QrCameraPreview(
    onQr: (String) -> Unit,
    onCameraError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val handled = remember { AtomicBoolean(false) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var errorReported by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { image ->
                        if (handled.get()) {
                            image.close()
                            return@setAnalyzer
                        }
                        val mediaImage = image.image
                        if (mediaImage == null) {
                            image.close()
                            return@setAnalyzer
                        }
                        val input = InputImage.fromMediaImage(
                            mediaImage,
                            image.imageInfo.rotationDegrees
                        )
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                val raw = barcodes.firstNotNullOfOrNull { it.rawValue }
                                if (raw != null && handled.compareAndSet(false, true)) {
                                    onQr(raw)
                                }
                            }
                            .addOnCompleteListener { image.close() }
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (error: Exception) {
                    if (!errorReported) {
                        errorReported = true
                        onCameraError(error.message ?: "相机不可用")
                    }
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
