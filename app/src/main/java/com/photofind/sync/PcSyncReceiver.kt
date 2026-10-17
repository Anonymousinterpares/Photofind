package com.photofind.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.work.WorkManager
import com.photofind.faces.FaceWorker
import com.photofind.index.IndexWorker
import com.photofind.index.PhotoFile
import com.photofind.index.PhotoScanner
import com.photofind.photoFind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Lets `tools/pc_index.py` embed photos on a PC over USB. Only adb's shell user can send these
 * broadcasts (the receiver requires the DUMP permission, which ordinary apps can't hold).
 *
 * EXPORT pauses on-device indexing and writes the photos still to be indexed to [MANIFEST];
 * IMPORT stores one batch of PC-made embeddings; RESUME restarts on-device indexing;
 * PAUSE stops it without handing the work to the PC.
 * Both files are tab separated: key, uri, date taken, modified, size (+ base64 vector for IMPORT).
 */
class PcSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.photoFind
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_EXPORT -> {
                        pauseWorkers(context)
                        SyncStatus.write(context, "pc", 0, 0)
                        export(context)
                    }
                    ACTION_IMPORT -> import(context, requireNotNull(intent.getStringExtra(EXTRA_FILE)))
                    ACTION_RESUME -> {
                        app.search.invalidate()
                        SyncStatus.write(context, "indexing", 0, 0)
                        IndexWorker.start(context)
                    }
                    ACTION_PAUSE -> {
                        pauseWorkers(context)
                        SyncStatus.write(context, "paused", 0, 0)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PC sync ${intent.action} failed", e)
                File(dir(), "error.txt").writeText(e.stackTraceToString())
            } finally {
                pending.finish()
            }
        }
    }

    private fun pauseWorkers(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(IndexWorker.WORK_NAME)
        work.cancelUniqueWork(FaceWorker.WORK_NAME)
    }

    private fun export(context: Context) {
        val known = context.photoFind.store.signatures()
        val todo = PhotoScanner.scan(context)
            .filter { it.key.startsWith("/") && known[it.key] != it.signature }
            .sortedByDescending { it.dateTaken }
        val partial = File(dir(), "$MANIFEST.part")
        partial.bufferedWriter().use { out ->
            todo.forEach { out.append(fields(it).joinToString("\t")).append('\n') }
        }
        check(partial.renameTo(File(dir(), MANIFEST)))
        Log.i(TAG, "Exported ${todo.size} photos for PC indexing")
    }

    private fun import(context: Context, name: String) {
        val app = context.photoFind
        val file = File(dir(), name)
        val rows = file.readLines().filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            val photo = PhotoFile(f[0], Uri.parse(f[1]), f[2].toLong(), f[3].toLong(), f[4].toLong())
            photo to decodeVector(f[5])
        }
        app.store.putAll(rows)
        app.search.invalidate()
        file.delete()
        File(dir(), "$name.ok").writeText(rows.size.toString())
        Log.i(TAG, "Imported ${rows.size} PC embeddings from $name")
    }

    private fun fields(photo: PhotoFile) = listOf(
        photo.key,
        photo.uri.toString(),
        photo.dateTaken.toString(),
        photo.modified.toString(),
        photo.size.toString(),
    )

    private fun decodeVector(base64: String): FloatArray {
        val floats = ByteBuffer.wrap(Base64.getDecoder().decode(base64))
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    private fun dir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "PhotoFind",
    ).apply { mkdirs() }

    companion object {
        private const val TAG = "PcSync"
        const val ACTION_EXPORT = "com.photofind.EXPORT"
        const val ACTION_IMPORT = "com.photofind.IMPORT"
        const val ACTION_RESUME = "com.photofind.RESUME"
        const val ACTION_PAUSE = "com.photofind.PAUSE"
        const val EXTRA_FILE = "file"
        const val MANIFEST = "manifest.tsv"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
