package com.photofind.faces

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photofind.data.NewFace
import com.photofind.index.PhotoFile
import com.photofind.index.decodeForEmbedding
import com.photofind.photoFind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Looks for faces in every indexed photo not yet scanned (or changed since), then groups new
 * faces into people. Each photo is committed when done, so an interrupted run resumes; people
 * are regrouped every [CLUSTER_EVERY] photos so they show up while the scan is still running.
 */
class FaceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val app = applicationContext.photoFind
        val engine = FaceEngine(applicationContext)
        val missing = engine.missingModels()
        if (missing.isNotEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR to "Face models missing: ${missing.joinToString()}"))
        }
        showProgress(0, 0)

        val store = app.faces
        val todo = withContext(Dispatchers.IO) {
            val scanned = store.scanned()
            app.store.embeddedPhotos().filter { scanned[it.key] != it.signature }
        }
        var done = 0
        var faces = 0
        var groupings = 0
        val thumbnails = store.thumbnailDir()
        try {
            coroutineScope {
                fun decodeAsync(photo: PhotoFile) = async(Dispatchers.IO) {
                    runCatching { decodeForEmbedding(applicationContext, photo.uri, maxSide = DECODE_SIDE) }
                }

                var next = todo.firstOrNull()?.let(::decodeAsync)
                for ((index, photo) in todo.withIndex()) {
                    if (isStopped) {
                        next?.cancel()
                        break
                    }
                    val decoded = checkNotNull(next).await()
                    next = todo.getOrNull(index + 1)?.let(::decodeAsync)
                    val bitmap = decoded.getOrElse {
                        Log.w(TAG, "Skipping ${photo.key}", it)
                        null
                    }
                    faces += withContext(Dispatchers.Default) {
                        try {
                            scan(engine, photo, bitmap, thumbnails)
                        } finally {
                            bitmap?.recycle()
                        }
                    }
                    done++
                    if (done % PROGRESS_EVERY == 0 || done == todo.size) {
                        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_GROUPINGS to groupings))
                        showProgress(done, todo.size)
                    }
                    if (done % CLUSTER_EVERY == 0) {
                        withContext(Dispatchers.Default) { clusterFaces(store) }
                        groupings++
                        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_GROUPINGS to groupings))
                    }
                }
            }
            withContext(Dispatchers.Default) {
                clusterFaces(store)
                store.pruneThumbnails()
            }
        } finally {
            engine.close()
        }
        return Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_FACES to faces))
    }

    /**
     * Shows the progress notification. Started from the background (after indexing), Android
     * may refuse a foreground service; the scan then just runs as ordinary background work.
     */
    private suspend fun showProgress(done: Int, total: Int) {
        if (!foregroundAllowed) return
        try {
            setForeground(foregroundInfo(done, total))
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Scanning without a notification", e)
            foregroundAllowed = false
        }
    }

    private var foregroundAllowed = true

    /** Finds, embeds and stores the faces of one photo; returns how many were kept. */
    private fun scan(engine: FaceEngine, photo: PhotoFile, bitmap: Bitmap?, thumbnails: File): Int {
        val store = applicationContext.photoFind.faces
        if (bitmap == null) {
            store.replace(photo.key, photo.signature, emptyList())
            return 0
        }
        val found = engine.detect(bitmap).filter { it.score >= MIN_SCORE && it.box.width() >= MIN_FACE_PIXELS }
        val faces = found.map { face ->
            val box = RectF(
                (face.box.left / bitmap.width).coerceIn(0f, 1f),
                (face.box.top / bitmap.height).coerceIn(0f, 1f),
                (face.box.right / bitmap.width).coerceIn(0f, 1f),
                (face.box.bottom / bitmap.height).coerceIn(0f, 1f),
            )
            NewFace(box, face.score, engine.embed(bitmap, face))
        }
        val ids = store.replace(photo.key, photo.signature, faces)
        ids.zip(found).filter { (id, _) -> id >= 0 }.forEach { (id, face) ->
            val thumbnail = engine.thumbnail(bitmap, face.box)
            File(thumbnails, "$id.jpg").outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            thumbnail.recycle()
        }
        return faces.size
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Face scanning", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Finding people")
            .setContentText(if (total > 0) "$done / $total photos" else "Preparing…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val WORK_NAME = "faces"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FACES = "faces"
        const val KEY_GROUPINGS = "groupings"
        const val KEY_ERROR = "error"

        private const val TAG = "FaceWorker"
        private const val CHANNEL = "faces"
        private const val NOTIFICATION_ID = 2
        private const val PROGRESS_EVERY = 10
        private const val CLUSTER_EVERY = 1000

        /** Photos are decoded this big; faces narrower than [MIN_FACE_PIXELS] in it are skipped. */
        private const val DECODE_SIDE = 1280
        private const val MIN_FACE_PIXELS = 40f
        private const val MIN_SCORE = 0.6f

        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FaceWorker>().build(),
            )
        }
    }
}
