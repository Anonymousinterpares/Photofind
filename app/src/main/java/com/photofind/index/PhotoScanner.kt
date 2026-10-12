package com.photofind.index

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class PhotoFile(
    /** Absolute path when known, so MediaStore rows and files found on disk dedupe. */
    val key: String,
    val uri: Uri,
    val dateTaken: Long,
    val modified: Long,
    val size: Long,
) {
    val signature: String get() = "$modified:$size"
}

/**
 * Finds every photo on shared storage: everything Android's media library knows about, plus
 * (with all-files access) images it deliberately skips, e.g. folders with a `.nomedia` marker
 * such as WhatsApp's "Sent". Other apps' private `Android/data` folders are unreadable.
 */
object PhotoScanner {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    /** Skips stickers, avatars and cached thumbnails among files the media library ignores. */
    private const val MIN_UNLISTED_BYTES = 30_000L

    fun scan(context: Context): List<PhotoFile> {
        val found = LinkedHashMap<String, PhotoFile>()
        queryMediaStore(context, found)
        if (Environment.isExternalStorageManager()) {
            walk(Environment.getExternalStorageDirectory(), found)
        }
        return found.values.toList()
    }

    private fun queryMediaStore(context: Context, out: MutableMap<String, PhotoFile>) {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
        )
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val key = c.getString(dataCol) ?: "media:$id"
                val modified = c.getLong(modifiedCol) * 1000
                val taken = if (c.isNull(takenCol)) modified else c.getLong(takenCol)
                out[key] = PhotoFile(
                    key = key,
                    uri = ContentUris.withAppendedId(collection, id),
                    dateTaken = taken,
                    modified = modified,
                    size = c.getLong(sizeCol),
                )
            }
        }
    }

    private fun walk(root: File, out: MutableMap<String, PhotoFile>) {
        val skipped = setOf(
            File(root, "Android/data").absolutePath,
            File(root, "Android/obb").absolutePath,
        )
        val pending = ArrayDeque<File>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (child.absolutePath !in skipped) pending.add(child)
                    continue
                }
                val path = child.absolutePath
                if (path in out) continue
                if (child.extension.lowercase() !in imageExtensions) continue
                val size = child.length()
                if (size < MIN_UNLISTED_BYTES) continue
                val modified = child.lastModified()
                out[path] = PhotoFile(path, Uri.fromFile(child), modified, modified, size)
            }
        }
    }
}
