package dev.lucasdone.tvremote.controller.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 从相册/文件图片解码二维码（相机不可用或相机损坏机型的兜底）。
 * 只返回解码文本；不记录图片或二维码原文。
 */
object QrImageDecoder {
    private const val MAX_DIMENSION = 1_200
    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun decode(context: Context, uri: Uri): String? {
        val sample = sampleSize(context, uri)
        val bitmap = readBitmap(context, uri, sample) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return runCatching { MultiFormatReader().apply { setHints(HINTS) }.decode(binary).text }.getOrNull()
    }

    private fun sampleSize(context: Context, uri: Uri): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return 1
        var sample = 1
        while (longest / sample > MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun readBitmap(context: Context, uri: Uri, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }
}
