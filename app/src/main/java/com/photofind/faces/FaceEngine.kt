package com.photofind.faces

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Environment
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FaceModelsMissingException(message: String) : IllegalStateException(message)

/** A detected face in bitmap pixels: box and five landmarks (eyes, nose, mouth corners) as x, y pairs. */
class DetectedFace(val box: RectF, val landmarks: FloatArray, val score: Float)

/**
 * InsightFace "buffalo_s" running through ONNX Runtime: SCRFD-500M finds faces and their
 * landmarks, then each face is aligned to the standard 112×112 ArcFace pose and MobileFaceNet
 * turns it into a 512-number identity vector. Two faces of one person score high on cosine
 * similarity regardless of the scene, unlike the photo embeddings.
 *
 * Not thread safe; [FaceWorker] uses it from one coroutine.
 */
class FaceEngine(private val context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private var detector: OrtSession? = null
    private var recognizer: OrtSession? = null

    fun modelLocations(): List<File> = listOfNotNull(
        context.filesDir,
        context.getExternalFilesDir(null),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
    )

    private fun model(name: String): File? =
        modelLocations().map { File(it, name) }.firstOrNull { it.isFile && it.length() > 0 }

    /** Model files that could not be found. */
    fun missingModels(): List<String> = listOf(DETECTOR, RECOGNIZER).filter { model(it) == null }

    private fun session(name: String): OrtSession {
        val file = model(name) ?: throw FaceModelsMissingException(
            "Face model $name not found. Looked in:\n" + modelLocations().joinToString("\n") { it.absolutePath },
        )
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        return env.createSession(file.absolutePath, options)
    }

    fun detect(bitmap: Bitmap): List<DetectedFace> {
        val session = detector ?: session(DETECTOR).also { detector = it }
        val scale = DETECT_SIZE.toFloat() / max(bitmap.width, bitmap.height)
        val width = (bitmap.width * scale).roundToInt().coerceIn(1, DETECT_SIZE)
        val height = (bitmap.height * scale).roundToInt().coerceIn(1, DETECT_SIZE)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        // Letterboxed into the top-left corner; padding is black, as in InsightFace.
        val plane = DETECT_SIZE * DETECT_SIZE
        val input = FloatArray(3 * plane) { -127.5f / 128f }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val i = y * DETECT_SIZE + x
                input[i] = (((p shr 16) and 0xFF) - 127.5f) / 128f
                input[plane + i] = (((p shr 8) and 0xFF) - 127.5f) / 128f
                input[2 * plane + i] = ((p and 0xFF) - 127.5f) / 128f
            }
        }

        val candidates = ArrayList<DetectedFace>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, DETECT_SIZE.toLong(), DETECT_SIZE.toLong()))
            .use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    STRIDES.forEachIndexed { level, stride ->
                        @Suppress("UNCHECKED_CAST")
                        val scores = result[level].value as Array<FloatArray>
                        @Suppress("UNCHECKED_CAST")
                        val boxes = result[level + STRIDES.size].value as Array<FloatArray>
                        @Suppress("UNCHECKED_CAST")
                        val points = result[level + 2 * STRIDES.size].value as Array<FloatArray>
                        val grid = DETECT_SIZE / stride
                        for (a in scores.indices) {
                            val score = scores[a][0]
                            if (score < DETECT_THRESHOLD) continue
                            val cell = a / ANCHORS
                            val cx = (cell % grid) * stride.toFloat()
                            val cy = (cell / grid) * stride.toFloat()
                            val b = boxes[a]
                            val box = RectF(
                                (cx - b[0] * stride) / scale,
                                (cy - b[1] * stride) / scale,
                                (cx + b[2] * stride) / scale,
                                (cy + b[3] * stride) / scale,
                            )
                            val k = points[a]
                            val landmarks = FloatArray(10) { j ->
                                ((if (j % 2 == 0) cx else cy) + k[j] * stride) / scale
                            }
                            candidates += DetectedFace(box, landmarks, score)
                        }
                    }
                }
            }
        return nonMaxSuppression(candidates)
    }

    /** The identity vector of [face], L2-normalised. */
    fun embed(bitmap: Bitmap, face: DetectedFace): FloatArray {
        val session = recognizer ?: session(RECOGNIZER).also { recognizer = it }
        val aligned = align(bitmap, face.landmarks)
        val pixels = IntArray(ALIGNED * ALIGNED)
        aligned.getPixels(pixels, 0, ALIGNED, 0, 0, ALIGNED, ALIGNED)
        aligned.recycle()
        val plane = ALIGNED * ALIGNED
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            input[i] = (((p shr 16) and 0xFF) - 127.5f) / 127.5f
            input[plane + i] = (((p shr 8) and 0xFF) - 127.5f) / 127.5f
            input[2 * plane + i] = ((p and 0xFF) - 127.5f) / 127.5f
        }
        val vector = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, ALIGNED.toLong(), ALIGNED.toLong()))
            .use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    (result[0].value as Array<FloatArray>)[0]
                }
            }
        val norm = sqrt(vector.fold(0f) { sum, v -> sum + v * v })
        return FloatArray(vector.size) { vector[it] / norm }
    }

    /**
     * A square crop centred on the face with a small margin, so it shows that face and not its
     * neighbours; where the square runs off the photo the thumbnail stays dark.
     */
    fun thumbnail(bitmap: Bitmap, box: RectF): Bitmap {
        val side = max(box.width(), box.height()) * 1.3f
        val crop = RectF(box.centerX() - side / 2, box.centerY() - side / 2, box.centerX() + side / 2, box.centerY() + side / 2)
        val visible = RectF(crop)
        visible.intersect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val scale = THUMBNAIL / side
        val out = Bitmap.createBitmap(THUMBNAIL, THUMBNAIL, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            bitmap,
            Rect(visible.left.toInt(), visible.top.toInt(), visible.right.toInt(), visible.bottom.toInt()),
            RectF(
                (visible.left - crop.left) * scale,
                (visible.top - crop.top) * scale,
                (visible.right - crop.left) * scale,
                (visible.bottom - crop.top) * scale,
            ),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    fun close() {
        detector?.close()
        recognizer?.close()
        detector = null
        recognizer = null
    }

    /**
     * Warps the face so its landmarks land on ArcFace's reference points, using the
     * least-squares similarity transform (rotation, uniform scale, shift) between them.
     */
    private fun align(bitmap: Bitmap, landmarks: FloatArray): Bitmap {
        var sx = 0f
        var sy = 0f
        var dx = 0f
        var dy = 0f
        for (i in 0 until 5) {
            sx += landmarks[2 * i]
            sy += landmarks[2 * i + 1]
            dx += REFERENCE[2 * i]
            dy += REFERENCE[2 * i + 1]
        }
        sx /= 5; sy /= 5; dx /= 5; dy /= 5
        var a = 0f
        var b = 0f
        var norm = 0f
        for (i in 0 until 5) {
            val px = landmarks[2 * i] - sx
            val py = landmarks[2 * i + 1] - sy
            val qx = REFERENCE[2 * i] - dx
            val qy = REFERENCE[2 * i + 1] - dy
            a += px * qx + py * qy
            b += px * qy - py * qx
            norm += px * px + py * py
        }
        val c = a / norm
        val s = b / norm
        val matrix = Matrix().apply {
            setValues(floatArrayOf(c, -s, dx - (c * sx - s * sy), s, c, dy - (s * sx + c * sy), 0f, 0f, 1f))
        }
        val out = Bitmap.createBitmap(ALIGNED, ALIGNED, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun nonMaxSuppression(faces: List<DetectedFace>): List<DetectedFace> {
        val kept = ArrayList<DetectedFace>()
        for (face in faces.sortedByDescending { it.score }) {
            if (kept.none { iou(it.box, face.box) > NMS_IOU }) kept += face
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0 || h <= 0) return 0f
        val overlap = w * h
        return overlap / (a.width() * a.height() + b.width() * b.height() - overlap)
    }

    companion object {
        const val DETECTOR = "det_500m.onnx"
        const val RECOGNIZER = "w600k_mbf.onnx"
        const val THUMBNAIL = 160

        private const val DETECT_SIZE = 640
        private const val DETECT_THRESHOLD = 0.5f
        private const val NMS_IOU = 0.4f
        private const val ANCHORS = 2
        private val STRIDES = intArrayOf(8, 16, 32)
        private const val ALIGNED = 112

        /** Where ArcFace expects the eyes, nose tip and mouth corners in a 112×112 face. */
        private val REFERENCE = floatArrayOf(
            38.2946f, 51.6963f,
            73.5318f, 51.5014f,
            56.0252f, 71.7366f,
            41.5493f, 92.3655f,
            70.7299f, 92.2041f,
        )
    }
}
