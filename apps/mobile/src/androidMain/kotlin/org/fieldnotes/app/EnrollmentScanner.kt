package org.fieldnotes.app

import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** ZXing's local decoder, not ML Kit or Play services. Frames are neither saved
 * nor sent anywhere. The camera exists only while this dialog is visible. */
@Composable fun EnrollmentScanner(activity: MainActivity, found: (String) -> Unit, close: () -> Unit) {
    val preview = remember { PreviewView(activity) }; var error by remember { mutableStateOf<String?>(null) }
    val currentFound by rememberUpdatedState(found)
    DisposableEffect(Unit) {
        val disposed = AtomicBoolean(false); val delivered = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor(); val future = ProcessCameraProvider.getInstance(activity)
        var provider: ProcessCameraProvider? = null
        val useCases = mutableListOf<UseCase>()
        future.addListener({
            if (!disposed.get()) try {
                val camera = future.get(); provider = camera
                val view = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                @Suppress("DEPRECATION") val analysis = ImageAnalysis.Builder().setTargetResolution(Size(1280, 720)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { frame ->
                    try {
                        if (!delivered.get() && !disposed.get()) {
                            val plane = frame.planes[0]; val width = frame.width; val height = frame.height
                            val pixels = ByteArray(width * height); val buffer = plane.buffer
                            for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
                            var source: LuminanceSource = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
                            val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
                            for (rotation in 0..3) {
                                val code = try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text } catch (_: NotFoundException) { null } finally { reader.reset() }
                                if (code != null && code.startsWith("fieldnotes://enroll#") && code.length <= 20000 && delivered.compareAndSet(false, true)) {
                                    ContextCompat.getMainExecutor(activity).execute { if (!disposed.get()) currentFound(code) }; break
                                }
                                source = source.rotateCounterClockwise()
                            }
                        }
                    } catch (_: Exception) { /* A malformed frame is discarded, never logged. */ } finally { frame.close() }
                }
                useCases.addAll(listOf(view, analysis)); camera.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, view, analysis)
            } catch (_: Exception) { error = "Camera unavailable. You can paste the invitation instead." }
        }, ContextCompat.getMainExecutor(activity))
        onDispose { disposed.set(true); provider?.unbind(*useCases.toTypedArray()); executor.shutdown() }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(20.dp)) {
            Text("Scan the invitation from your trusted server", style = MaterialTheme.typography.titleLarge)
            AndroidView(factory = { preview }, modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 18.dp))
            error?.let { Text(it) }; TextButton(onClick = close) { Text("Cancel") }
        } }
    }
}
