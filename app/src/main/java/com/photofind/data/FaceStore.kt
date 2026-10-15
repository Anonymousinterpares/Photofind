package com.photofind.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import android.net.Uri
import com.photofind.search.IndexedPhoto
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A face found in a photo; [box] is in 0..1 photo coordinates. A face tagged by hand may have
 * an empty [embedding] when no face could be found in the box (a back of a head, a blur).
 */
class NewFace(val box: RectF, val score: Float, val embedding: FloatArray)

/** A stored face as clustering sees it. */
class StoredFace(val id: Long, val personId: Long?, val embedding: FloatArray)

data class Person(val id: Long, val name: String?, val faceCount: Int, val coverFaceId: Long) {
    val unsorted: Boolean get() = id == UNSORTED

    companion object {
        /** Stands for the faces that belong to nobody yet, shown like a person. */
        const val UNSORTED = -1L
    }
}

data class PersonFace(val id: Long, val photo: IndexedPhoto)

/** A face in one photo, for drawing tags over it; [personId] is null for a face nobody claims. */
data class PhotoFace(val id: Long, val box: RectF, val personId: Long?, val name: String?, val manual: Boolean)

/**
 * Faces, the people they belong to, and the user's corrections, in the photo database.
 *
 * A face's person is either a guess (clustering) or the user's doing (merge, move); both are
 * kept as-is by later clustering, which only places faces that have no person. Removing a face
 * from a person records a rejection, so clustering never puts it back there. Hidden faces
 * (strangers, statues, posters) are left out of people, clustering and the unsorted faces.
 * Faces tagged by hand are kept when the photo is scanned again.
 */
class FaceStore(context: Context, private val photos: PhotoStore) {

    private val thumbnails = File(context.filesDir, "faces")

    fun thumbnail(faceId: Long): File = File(thumbnails, "$faceId.jpg")

    /** Photo key to the signature it had when its faces were last looked for. */
    fun scanned(): Map<String, String> {
        val out = HashMap<String, String>()
        photos.readableDatabase.rawQuery("SELECT photo_key, signature FROM face_scans", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    fun isScanned(key: String, signature: String): Boolean =
        photos.readableDatabase.rawQuery(
            "SELECT 1 FROM face_scans WHERE photo_key = ? AND signature = ?",
            arrayOf(key, signature),
        ).use { it.moveToFirst() }

    /**
     * Replaces the detected faces of one photo and returns the new faces' ids in order, -1 for a
     * face skipped because the user already tagged it by hand.
     */
    fun replace(key: String, signature: String, faces: List<NewFace>): List<Long> {
        val db = photos.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM face_rejections WHERE face_id IN (SELECT id FROM faces WHERE photo_key = ? AND manual = 0)",
                arrayOf(key),
            )
            db.delete("faces", "photo_key = ? AND manual = 0", arrayOf(key))
            val manual = facesIn(key).filter { it.manual }.map { it.box }
            val ids = faces.map { face ->
                if (manual.any { overlap(it, face.box) > 0.3f }) -1L else insert(db, key, face, manual = false)
            }
            db.insertWithOnConflict(
                "face_scans",
                null,
                ContentValues().apply {
                    put("photo_key", key)
                    put("signature", signature)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
            return ids
        } finally {
            db.endTransaction()
        }
    }

    /** Adds a face the user tagged by hand; returns its id. */
    fun addManual(key: String, face: NewFace): Long = insert(photos.writableDatabase, key, face, manual = true)

    private fun insert(db: SQLiteDatabase, key: String, face: NewFace, manual: Boolean): Long =
        db.insertOrThrow(
            "faces",
            null,
            ContentValues().apply {
                put("photo_key", key)
                put("x0", face.box.left)
                put("y0", face.box.top)
                put("x1", face.box.right)
                put("y1", face.box.bottom)
                put("score", face.score)
                put("embedding", encode(face.embedding))
                put("manual", if (manual) 1 else 0)
            },
        )

    private fun overlap(a: RectF, b: RectF): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0f
        val both = w * h
        return both / (a.width() * a.height() + b.width() * b.height() - both)
    }

    fun faceCount(): Int =
        photos.readableDatabase.rawQuery("SELECT COUNT(*) FROM faces", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun allFaces(): List<StoredFace> {
        val out = ArrayList<StoredFace>()
        photos.readableDatabase.rawQuery(
            "SELECT id, person_id, embedding FROM faces WHERE hidden = 0 AND length(embedding) > 0",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += StoredFace(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), decode(c.getBlob(2)))
            }
        }
        return out
    }

    /** Face id to the people the user removed it from. */
    fun rejections(): Map<Long, Set<Long>> {
        val out = HashMap<Long, MutableSet<Long>>()
        photos.readableDatabase.rawQuery("SELECT face_id, person_id FROM face_rejections", null).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { HashSet() } += c.getLong(1)
        }
        return out
    }

    /** People with at least one face, most faces first; the cover is their clearest face. */
    fun people(): List<Person> {
        val out = ArrayList<Person>()
        photos.readableDatabase.rawQuery(
            """
            SELECT p.id, p.name, COUNT(f.id),
                (SELECT id FROM faces WHERE person_id = p.id
                    ORDER BY score * (x1 - x0) DESC LIMIT 1)
            FROM persons p JOIN faces f ON f.person_id = p.id
            GROUP BY p.id
            ORDER BY COUNT(f.id) DESC
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) out += Person(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3))
        }
        return out
    }

    /** The faces nobody claims yet, as a [Person] with id [Person.UNSORTED]; null when there are none. */
    fun unsorted(): Person? =
        photos.readableDatabase.rawQuery(
            """
            SELECT COUNT(*), (SELECT id FROM faces WHERE person_id IS NULL AND hidden = 0
                ORDER BY score * (x1 - x0) DESC LIMIT 1)
            FROM faces WHERE person_id IS NULL AND hidden = 0
            """.trimIndent(),
            null,
        ).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) Person(Person.UNSORTED, null, c.getInt(0), c.getLong(1)) else null
        }

    /** One person's faces (or the unsorted ones) with their photos, newest first. */
    fun facesOf(personId: Long): List<PersonFace> {
        val out = ArrayList<PersonFace>()
        val where = if (personId == Person.UNSORTED) "f.person_id IS NULL AND f.hidden = 0" else "f.person_id = ?"
        photos.readableDatabase.rawQuery(
            """
            SELECT f.id, p.key, p.uri, p.date_taken, p.signature
            FROM faces f JOIN photos p ON p.key = f.photo_key
            WHERE $where
            ORDER BY p.date_taken DESC
            """.trimIndent(),
            if (personId == Person.UNSORTED) null else arrayOf(personId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val size = c.getString(4).substringAfter(':').toLongOrNull() ?: 0L
                out += PersonFace(c.getLong(0), IndexedPhoto(c.getString(1), Uri.parse(c.getString(2)), c.getLong(3), size))
            }
        }
        return out
    }

    /** The faces in one photo, left to right, with the names of the people they belong to. */
    fun facesIn(photoKey: String): List<PhotoFace> {
        val out = ArrayList<PhotoFace>()
        photos.readableDatabase.rawQuery(
            """
            SELECT f.id, f.x0, f.y0, f.x1, f.y1, f.person_id, p.name, f.manual
            FROM faces f LEFT JOIN persons p ON p.id = f.person_id
            WHERE f.photo_key = ? AND f.hidden = 0
            ORDER BY f.x0
            """.trimIndent(),
            arrayOf(photoKey),
        ).use { c ->
            while (c.moveToNext()) {
                out += PhotoFace(
                    id = c.getLong(0),
                    box = RectF(c.getFloat(1), c.getFloat(2), c.getFloat(3), c.getFloat(4)),
                    personId = if (c.isNull(5)) null else c.getLong(5),
                    name = c.getString(6),
                    manual = c.getInt(7) != 0,
                )
            }
        }
        return out
    }

    /** Keys of the photos in which any of [personIds] appears. */
    fun photosOf(personIds: Collection<Long>): Set<String> {
        if (personIds.isEmpty()) return emptySet()
        val out = HashSet<String>()
        photos.readableDatabase.rawQuery(
            "SELECT DISTINCT photo_key FROM faces WHERE person_id IN (${personIds.joinToString { "?" }}) AND hidden = 0",
            personIds.map { it.toString() }.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun rename(personId: Long, name: String?) {
        photos.writableDatabase.update(
            "persons",
            ContentValues().apply { put("name", name?.trim()?.ifEmpty { null }) },
            "id = ?",
            arrayOf(personId.toString()),
        )
    }

    /**
     * Puts faces that still have no person into [existing] people (person id to face ids) and
     * into [newPeople], each list of face ids becoming a new unnamed person.
     */
    fun assign(existing: Map<Long, List<Long>>, newPeople: List<List<Long>>) = transaction { db ->
        val all = existing.entries.map { it.key to it.value } + newPeople.map { faceIds ->
            db.insertOrThrow("persons", null, ContentValues().apply { putNull("name") }) to faceIds
        }
        all.forEach { (personId, faceIds) ->
            faceIds.forEach {
                db.execSQL("UPDATE faces SET person_id = ? WHERE id = ? AND person_id IS NULL", arrayOf(personId, it))
            }
        }
        deleteEmptyPeople(db)
    }

    /** Moves every face of [from] into [into]; [into] keeps its name, or takes the first one found. */
    fun merge(from: Collection<Long>, into: Long) = transaction { db ->
        val sources = from.filter { it != into }
        val name = nameOf(db, into) ?: sources.firstNotNullOfOrNull { nameOf(db, it) }
        sources.forEach { source ->
            db.execSQL("UPDATE faces SET person_id = ? WHERE person_id = ?", arrayOf(into, source))
            // A face may have been removed from both people already; keep one such record.
            db.execSQL("UPDATE OR IGNORE face_rejections SET person_id = ? WHERE person_id = ?", arrayOf(into, source))
            db.execSQL("DELETE FROM face_rejections WHERE person_id = ?", arrayOf(source))
            db.execSQL("DELETE FROM persons WHERE id = ?", arrayOf(source))
        }
        // The merged faces are this person now, whatever was said about them before.
        db.execSQL(
            "DELETE FROM face_rejections WHERE person_id = ? AND face_id IN (SELECT id FROM faces WHERE person_id = ?)",
            arrayOf(into, into),
        )
        db.execSQL("UPDATE persons SET name = ? WHERE id = ?", arrayOf(name, into))
    }

    /** Takes faces out of [personId] and remembers that they are not that person. */
    fun remove(personId: Long, faceIds: Collection<Long>) = transaction { db ->
        faceIds.forEach { id ->
            db.execSQL("UPDATE faces SET person_id = NULL WHERE id = ? AND person_id = ?", arrayOf(id, personId))
            db.execSQL("INSERT OR IGNORE INTO face_rejections (face_id, person_id) VALUES (?, ?)", arrayOf(id, personId))
        }
        deleteEmptyPeople(db)
    }

    /** Moves faces to another person, by the user's choice. */
    fun move(faceIds: Collection<Long>, to: Long) = transaction { db ->
        faceIds.forEach { id ->
            db.execSQL("UPDATE faces SET person_id = ?, hidden = 0 WHERE id = ?", arrayOf(to, id))
            db.execSQL("DELETE FROM face_rejections WHERE face_id = ? AND person_id = ?", arrayOf(id, to))
        }
        deleteEmptyPeople(db)
    }

    /** Makes a new person of [faceIds], optionally named; returns its id. */
    fun newPerson(faceIds: Collection<Long>, name: String?): Long {
        var id = 0L
        transaction { db ->
            id = db.insertOrThrow("persons", null, ContentValues().apply { put("name", name?.trim()?.ifEmpty { null }) })
            faceIds.forEach { db.execSQL("UPDATE faces SET person_id = ?, hidden = 0 WHERE id = ?", arrayOf(id, it)) }
            deleteEmptyPeople(db)
        }
        return id
    }

    /** Hides faces that aren't anyone to keep (strangers, statues, posters) from people and unsorted faces. */
    fun hide(faceIds: Collection<Long>) = transaction { db ->
        faceIds.forEach { db.execSQL("UPDATE faces SET person_id = NULL, hidden = 1 WHERE id = ?", arrayOf(it)) }
        deleteEmptyPeople(db)
    }

    /** Deletes thumbnails of faces that no longer exist. */
    fun pruneThumbnails() {
        val ids = HashSet<Long>()
        photos.readableDatabase.rawQuery("SELECT id FROM faces", null).use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        thumbnails.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension.toLongOrNull() !in ids) file.delete()
        }
    }

    fun thumbnailDir(): File = thumbnails.also { it.mkdirs() }

    private fun nameOf(db: SQLiteDatabase, personId: Long): String? =
        db.rawQuery("SELECT name FROM persons WHERE id = ?", arrayOf(personId.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

    private fun deleteEmptyPeople(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM persons WHERE id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id IS NOT NULL)")
    }

    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = photos.writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE persons (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE faces (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    photo_key TEXT NOT NULL,
                    x0 REAL NOT NULL,
                    y0 REAL NOT NULL,
                    x1 REAL NOT NULL,
                    y1 REAL NOT NULL,
                    score REAL NOT NULL,
                    embedding BLOB NOT NULL,
                    person_id INTEGER,
                    hidden INTEGER NOT NULL DEFAULT 0,
                    manual INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX faces_photo ON faces (photo_key)")
            db.execSQL("CREATE INDEX faces_person ON faces (person_id)")
            db.execSQL("CREATE TABLE face_scans (photo_key TEXT PRIMARY KEY, signature TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE face_rejections (face_id INTEGER NOT NULL, person_id INTEGER NOT NULL, " +
                    "PRIMARY KEY (face_id, person_id))",
            )
        }

        /** Brings face tables made by an older version (2 or later) up to date. */
        fun upgrade(db: SQLiteDatabase, oldVersion: Int) {
            if (oldVersion < 3) db.execSQL("ALTER TABLE faces ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            if (oldVersion < 4) db.execSQL("ALTER TABLE faces ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
        }

        /** Forgets a photo's faces, e.g. when it was deleted or changed. */
        fun deleteForPhoto(db: SQLiteDatabase, key: String) {
            db.execSQL(
                "DELETE FROM face_rejections WHERE face_id IN (SELECT id FROM faces WHERE photo_key = ?)",
                arrayOf(key),
            )
            db.delete("faces", "photo_key = ?", arrayOf(key))
            db.delete("face_scans", "photo_key = ?", arrayOf(key))
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
}
