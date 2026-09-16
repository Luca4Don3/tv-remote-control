package dev.lucasdone.tvremote.controller.ui.scanner

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import dev.lucasdone.tvremote.controller.R
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内二维码扫描（CameraX 预览/分析 + ZXing 纯 Java 解码，无 Google Play 服务依赖）。
 *
 * 只把解码文本交回调用方；不在此处解析或执行内容，也不记录二维码原文（可能含一次性 token）。
 */
@Composable
fun QrScanner(
    modifier: Modifier = Modifier,
    onCode: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "tvrc-qr-analyzer") } }
    val handled = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val providerRef = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>(null) }
    val hints = remember {
        mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
    }

    DisposableEffect(Unit) {
        onDispose {
            // 退出扫码页：标记已销毁并解绑，晚到的初始化/结果分发都不再生效
            disposed.set(true)
            runCatching { providerRef.get()?.unbindAll() }
            analyzerExecutor.shutdownNow()
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val mainExecutor = ContextCompat.getMainExecutor(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    // 先发布再判销毁：关闭方总能通过 providerRef 解绑；若关闭发生在发布后，
                    // 这里第二次判定会命中并立即解绑，避免相机在页面退出后仍被绑定。
                    providerRef.set(provider)
                    if (disposed.get()) {
                        runCatching { provider.unbindAll() }
                        return@addListener
                    }
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor) { image ->
                        if (handled.get() || disposed.get()) {
                            image.close()
                            return@setAnalyzer
                        }
                        try {
                            val text = decodeQr(image, hints)
                            if (text != null && !disposed.get() && handled.compareAndSet(false, true)) {
                                mainExecutor.execute { if (!disposed.get()) onCode(text) }
                            }
                        } catch (_: Exception) {
                            // 单帧解码失败属常态，忽略并等待下一帧
                        } finally {
                            image.close()
                        }
                    }
                    val selector = selectCamera(provider)
                    if (selector == null) {
                        val message = ctx.getString(R.string.camera_unavailable, "no camera")
                        mainExecutor.execute { if (!disposed.get()) onError(message) }
                        return@addListener
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } catch (error: Exception) {
                    val message = ctx.getString(R.string.camera_unavailable, error.javaClass.simpleName)
                    mainExecutor.execute { if (!disposed.get()) onError(message) }
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** 后摄优先，其次前摄；都不可用返回 null（由调用方给出指引与相册兜底）。 */
private fun selectCamera(provider: ProcessCameraProvider): CameraSelector? {
    for (candidate in listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)) {
        val available = try {
            provider.hasCamera(candidate)
        } catch (_: Exception) {
            false
        }
        if (available) return candidate
    }
    return null
}

private fun decodeQr(image: ImageProxy, hints: Map<DecodeHintType, Any>): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    buffer.rewind()
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    if (width <= 0 || height <= 0) return null

    val luminance = ByteArray(width * height)
    if (pixelStride == 1 && rowStride == width) {
        val count = minOf(buffer.remaining(), luminance.size)
        buffer.get(luminance, 0, count)
    } else {
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            val toRead = minOf(rowStride, buffer.remaining())
            if (toRead <= 0) break
            buffer.get(row, 0, toRead)
            for (x in 0 until width) {
                val index = x * pixelStride
                luminance[y * width + x] = if (index < toRead) row[index] else 0
            }
        }
    }

    val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return runCatching { MultiFormatReader().apply { setHints(hints) }.decode(bitmap).text }.getOrNull()
}
