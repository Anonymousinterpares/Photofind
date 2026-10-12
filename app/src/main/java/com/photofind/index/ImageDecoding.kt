package com.photofind.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes a photo upright (EXIF rotation applied) and downscaled; the embedder resizes
 * internally, so full resolution only costs memory and time.
 */
fun decodeForEmbedding(context: Context, uri: Uri, maxSide: Int = 1024): Bitmap {
    val source = if (uri.scheme == "file") {
        ImageDecoder.createSource(File(requireNotNull(uri.path)))
    } else {
        ImageDecoder.createSource(context.contentResolver, uri)
    }
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val width = info.size.width
        val height = info.size.height
        val scale = maxSide.toFloat() / max(width, height)
        if (scale < 1f) {
            decoder.setTargetSize(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }
    if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
    return bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
}
