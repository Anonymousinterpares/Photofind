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
import com.photofind.photoFind
import kotlinx.coroutines.Dispatchers
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
        for (photo in todo) {
            if (isStopped) break
            val vector = try {
                val bitmap = withContext(Dispatchers.IO) { decodeForEmbedding(applicationContext, photo.uri) }
                try {
                    app.embedder.embedImage(bitmap)
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping ${photo.key}", e)
                failed++
                null
            }
            withContext(Dispatchers.IO) { app.store.put(photo, vector) }
            done++
            if (done % PROGRESS_EVERY == 0 || done == todo.size) {
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size))
                setForeground(foregroundInfo(done, todo.size))
            }
            if (done % REFRESH_SEARCH_EVERY == 0) app.search.invalidate()
        }
        app.search.invalidate()
        return Result.success(
            workDataOf(KEY_DONE to done, KEY_TOTAL to todo.size, KEY_FAILED to failed),
        )
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
