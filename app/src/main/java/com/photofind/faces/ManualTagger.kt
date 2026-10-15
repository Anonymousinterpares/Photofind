package com.photofind.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.photofind.data.NewFace
import com.photofind.index.decodeForEmbedding
import com.photofind.photoFind
import com.photofind.search.IndexedPhoto
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Tags a person in a box the user drew on a photo. If the detector finds a face in the box,
 * that face is stored with its identity vector, so it also teaches recognition (unsorted faces
 * like it will join the person); otherwise the box is stored as a plain tag.
 */
object ManualTagger {

    /**
     * Adds the face in [box] (0..1 photo coordinates) to [personId], or to a new person named
     * [newName] when [personId] is null. Returns whether a face was recognised in the box.
     * Runs on the calling thread; call it off the main thread.
     */
    fun tag(context: Context, photo: IndexedPhoto, box: RectF, personId: Long?, newName: String?): Boolean {
        val store = context.photoFind.faces
        val bitmap = decodeForEmbedding(context, photo.uri, maxSide = DECODE_SIDE)
        try {
            val drawn = RectF(box.left * bitmap.width, box.top * bitmap.height, box.right * bitmap.width, box.bottom * bitmap.height)
            val engine = FaceEngine(context)
            val (pixels, found) = try {
                val face = if (engine.missingModels().isEmpty()) findFace(engine, bitmap, drawn) else null
                (face?.box ?: drawn) to face?.let { it.score to engine.embed(bitmap, it) }
            } catch (e: Exception) {
                Log.w(TAG, "No face recognised in the tag", e)
                drawn to null
            } finally {
                engine.close()
            }
            val normalised = RectF(
                (pixels.left / bitmap.width).coerceIn(0f, 1f),
                (pixels.top / bitmap.height).coerceIn(0f, 1f),
                (pixels.right / bitmap.width).coerceIn(0f, 1f),
                (pixels.bottom / bitmap.height).coerceIn(0f, 1f),
            )
            val id = store.addManual(photo.key, NewFace(normalised, found?.first ?: 1f, found?.second ?: FloatArray(0)))
            val thumbnail = FaceEngine(context).thumbnail(bitmap, pixels)
            File(store.thumbnailDir(), "$id.jpg").outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            thumbnail.recycle()
            if (personId != null) store.move(listOf(id), personId) else store.newPerson(listOf(id), newName)
            return found != null
        } finally {
            bitmap.recycle()
        }
    }

    /** The most confident face whose centre lies in [drawn], looked for in a crop around it. */
    private fun findFace(engine: FaceEngine, bitmap: Bitmap, drawn: RectF): DetectedFace? {
        val margin = max(drawn.width(), drawn.height()) * 0.5f
        val left = max(0f, drawn.left - margin).toInt()
        val top = max(0f, drawn.top - margin).toInt()
        val right = min(bitmap.width.toFloat(), drawn.right + margin).toInt()
        val bottom = min(bitmap.height.toFloat(), drawn.bottom + margin).toInt()
        if (right - left < 8 || bottom - top < 8) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        try {
            return engine.detect(crop)
                .map { face ->
                    // Back from crop to photo pixels.
                    DetectedFace(
                        RectF(face.box.left + left, face.box.top + top, face.box.right + left, face.box.bottom + top),
                        FloatArray(10) { face.landmarks[it] + if (it % 2 == 0) left else top },
                        face.score,
                    )
                }
                .filter { drawn.contains(it.box.centerX(), it.box.centerY()) }
                .maxByOrNull { it.score }
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
    }

    private const val TAG = "ManualTagger"
    private const val DECODE_SIDE = 1280
}
