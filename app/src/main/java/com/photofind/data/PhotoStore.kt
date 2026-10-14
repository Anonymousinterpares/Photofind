package com.photofind.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.photofind.index.PhotoFile
import com.photofind.search.IndexedPhoto
import com.photofind.search.Snapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One row per photo on the device. A row with a NULL embedding is a photo that could not be
 * decoded or embedded; it is kept so it isn't retried until the file changes.
 */
class PhotoStore(context: Context) : SQLiteOpenHelper(context, "photofind.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photos (
                key TEXT PRIMARY KEY,
                uri TEXT NOT NULL,
                date_taken INTEGER NOT NULL,
                signature TEXT NOT NULL,
                embedding BLOB
            )
            """.trimIndent(),
        )
        FaceStore.createTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) FaceStore.createTables(db)
    }

    /** Every photo that was embedded, newest first; faces are looked for in these. */
    fun embeddedPhotos(): List<PhotoFile> {
        val out = ArrayList<PhotoFile>()
        readableDatabase.rawQuery(
            "SELECT key, uri, date_taken, signature FROM photos WHERE embedding IS NOT NULL ORDER BY date_taken DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val signature = c.getString(3)
                out += PhotoFile(
                    key = c.getString(0),
                    uri = Uri.parse(c.getString(1)),
                    dateTaken = c.getLong(2),
                    modified = signature.substringBefore(':').toLongOrNull() ?: 0L,
                    size = signature.substringAfter(':').toLongOrNull() ?: 0L,
                )
            }
        }
        return out
    }

    fun signatures(): Map<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT key, signature FROM photos", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    fun signature(key: String): String? =
        readableDatabase.rawQuery("SELECT signature FROM photos WHERE key = ?", arrayOf(key))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun put(photo: PhotoFile, embedding: FloatArray?) {
        val values = ContentValues().apply {
            put("key", photo.key)
            put("uri", photo.uri.toString())
            put("date_taken", photo.dateTaken)
            put("signature", photo.signature)
            put("embedding", embedding?.let(::encode))
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun putAll(rows: List<Pair<PhotoFile, FloatArray>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { (photo, embedding) -> put(photo, embedding) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun delete(keys: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            keys.forEach {
                db.delete("photos", "key = ?", arrayOf(it))
                FaceStore.deleteForPhoto(db, it)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun countEmbedded(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM photos WHERE embedding IS NOT NULL", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun loadSnapshot(): Snapshot {
        val photos = ArrayList<IndexedPhoto>()
        var matrix = FloatArray(0)
        var dim = 0
        readableDatabase.rawQuery(
            "SELECT key, uri, date_taken, embedding, signature FROM photos " +
                "WHERE embedding IS NOT NULL ORDER BY date_taken DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val vector = decode(c.getBlob(3))
                val size = c.getString(4).substringAfter(':').toLongOrNull() ?: 0L
                if (dim == 0) {
                    dim = vector.size
                    matrix = FloatArray(dim * c.count)
                }
                if (vector.size != dim) continue
                vector.copyInto(matrix, photos.size * dim)
                photos += IndexedPhoto(c.getString(0), Uri.parse(c.getString(1)), c.getLong(2), size)
            }
        }
        return Snapshot(photos, dim, matrix)
    }

    private fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    private fun decode(bytes: ByteArray): FloatArray {
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }
}
