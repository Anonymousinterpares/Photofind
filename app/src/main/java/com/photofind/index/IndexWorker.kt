package com.photofind.index

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photofind.faces.FaceEngine
import com.photofind.faces.FaceWorker
import com.photofind.photoFind
import com.photofind.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Brings the index in line with the photos on the device: embeds new or changed photos and
 * drops deleted ones. Each photo is committed as soon as it's embedded, so an interrupted run
 * resumes where it stopped.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val app = applicationContext.photoFind
        try {
            app.embedder.warmUp()
        } catch (e: Exception) {
            Log.e(TAG, "Embedder unavailable", e)
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
        }
        setForeground(foregroundInfo(0, 0))

        val photos = withContext(Dispatchers.IO) { PhotoScanner.scan(applicationContext) }
        val known = withContext(Dispatchers.IO) { app.store.signatures() }
        val onDevice = photos.mapTo(HashSet()) { it.key }
        val removed = known.keys.filterNot { it in onDevice }
        if (removed.isNotEmpty()) withContext(Dispatchers.IO) { app.store.delete(removed) }

        val todo = photos
            .filter { known[it.key] != it.signature }
            .sortedByDescending { it.dateTaken }
        var done = 0
        var failed = 0
        var waitNanos = 0L
        var embedNanos = 0L
        SyncStatus.write(applicationContext, "indexing", 0, todo.size)
        coroutineScope {
            // Null when the PC has imported this photo since the run started.
            fun decodeAsync(photo: PhotoFile) = async(Dispatchers.IO) {
                if (app.store.signature(photo.key) == photo.signature) null
                else runCatching { decodeForEmbedding(applicationContext, photo.uri) }
            }

            var next = todo.firstOrNull()?.let(::decodeAsync)
            for ((index, photo) in todo.withIndex()) {
                if (isStopped) {
                    next?.cancel()
                    SyncStatus.write(applicationContext, "paused", done, todo.size)
                    break
                }
                val waitStart = System.nanoTime()
                val decoded = checkNotNull(next).await()
                next = todo.getOrNull(index + 1)?.let(::decodeAsync)
                if (decoded == null) continue
                val embedStart = System.nanoTime()
                waitNanos += embedStart - waitStart
                val vector = try {
                    val bitmap = decoded.getOrThrow()
                    try {
                        app.embedder.embedImage(bitmap)
                    } finally {
                        bitmap.recycle()
                        embedNanos += System.nanoTime() - embedStart
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping ${photo.key}", e)
                    failed++
                    null
                }
                record(photo, vector, ++done, todo.size, waitNanos, embedNanos)
            }
        }
        app.search.invalidate()
        // New or changed photos get their faces looked for next (a no-op when nothing changed).
        if (!isStopped && FaceEngine(applicationContext).missingModels().isEmpty()) FaceWorker.start(applicationContext)
        return Result.success(
            workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_FAILED to failed),
        )
    }

    private suspend fun record(
        photo: PhotoFile,
        vector: FloatArray?,
        done: Int,
        total: Int,
        waitNanos: Long,
        embedNanos: Long,
    ) {
        val app = applicationContext.photoFind
        withContext(Dispatchers.IO) { app.store.put(photo, vector) }
        if (done % PROGRESS_EVERY == 0 || done == total) {
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            SyncStatus.write(applicationContext, "indexing", done, total)
            setForeground(foregroundInfo(done, total))
            Log.i(
                TAG,
                "$done/$total: waiting for decode ${waitNanos / 1_000_000 / done} ms, " +
                    "embed ${embedNanos / 1_000_000 / done} ms per photo",
            )
        }
        if (done % REFRESH_SEARCH_EVERY == 0) app.search.invalidate()
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Photo indexing", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Indexing photos")
            .setContentText(if (total > 0) "$done / $total" else "Scanning folders…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val WORK_NAME = "index"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"

        private const val TAG = "IndexWorker"
        private const val CHANNEL = "indexing"
        private const val NOTIFICATION_ID = 1
        private const val PROGRESS_EVERY = 10
        private const val REFRESH_SEARCH_EVERY = 200

        fun start(context: Context, restart: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>().build(),
            )
        }
    }
}
