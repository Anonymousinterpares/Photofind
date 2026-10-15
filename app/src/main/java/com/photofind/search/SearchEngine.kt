package com.photofind.search

import android.net.Uri
import com.photofind.data.PhotoStore
import com.photofind.embed.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** [size] is the file size in bytes. */
data class IndexedPhoto(val key: String, val uri: Uri, val dateTaken: Long, val size: Long) {
    val fileName: String get() = File(key).name
    val folder: String get() = File(key).parentFile?.name.orEmpty()
}

data class Hit(val photo: IndexedPhoto, val score: Float)

/** Inclusive range of epoch milliseconds. */
data class DateRange(val startMillis: Long, val endMillis: Long) {
    operator fun contains(millis: Long): Boolean = millis in startMillis..endMillis
}

/** All photo embeddings as one row-major matrix: row i belongs to photos[i]. Newest first. */
class Snapshot(val photos: List<IndexedPhoto>, val dim: Int, val matrix: FloatArray) {
    val size: Int get() = photos.size

    fun row(index: Int): FloatArray = matrix.copyOfRange(index * dim, (index + 1) * dim)
}

data class SearchResult(val hits: List<Hit>, val embedMillis: Long, val rankMillis: Long)

/**
 * Exhaustive cosine search over the in-memory snapshot. Embeddings are L2-normalised, so the
 * dot product is the cosine similarity; tens of thousands of photos rank in milliseconds.
 */
class SearchEngine(private val store: PhotoStore, private val embedder: Embedder) {

    @Volatile
    private var snapshot: Snapshot? = null

    fun invalidate() {
        snapshot = null
    }

    /** Every searchable photo, newest first. */
    suspend fun library(): List<IndexedPhoto> = snapshot().photos

    /** Best matches for [query]; [only], when given, limits them to those photos (e.g. of certain people). */
    suspend fun searchText(
        query: String,
        range: DateRange?,
        only: Set<String>? = null,
        limit: Int = DEFAULT_LIMIT,
    ): SearchResult {
        val embedStart = System.nanoTime()
        val vector = embedder.embedText(Embedder.QUERY_PREFIX + query)
        val embedMillis = (System.nanoTime() - embedStart) / 1_000_000
        return rank(vector, excludeKey = null, range = range, limit = limit, embedMillis = embedMillis, only = only)
    }

    /** Every photo among [keys], newest first, e.g. all photos of a person. */
    suspend fun photos(keys: Set<String>, range: DateRange?): SearchResult {
        val hits = snapshot().photos
            .filter { it.key in keys && (range == null || it.dateTaken in range) }
            .map { Hit(it, 1f) }
        return SearchResult(hits, 0, 0)
    }

    suspend fun similarTo(key: String, range: DateRange?, limit: Int = DEFAULT_LIMIT): SearchResult {
        val current = snapshot()
        val index = current.photos.indexOfFirst { it.key == key }
        if (index < 0) return SearchResult(emptyList(), 0, 0)
        return rank(current.row(index), excludeKey = key, range = range, limit = limit, embedMillis = 0)
    }

    /** Groups of byte-identical copies of one photo, e.g. the same WhatsApp image in two folders. */
    suspend fun identicalCopies(): List<PhotoGroup> {
        val current = snapshot()
        return withContext(Dispatchers.Default) { current.identicalCopies() }
    }

    /** Groups of near-identical shots (bursts, re-sent or edited copies); copies count once. */
    suspend fun similarShots(threshold: Float): List<PhotoGroup> {
        val current = snapshot()
        return withContext(Dispatchers.Default) { current.similarShots(threshold) }
    }

    private suspend fun snapshot(): Snapshot =
        snapshot ?: withContext(Dispatchers.IO) { store.loadSnapshot() }.also { snapshot = it }

    private suspend fun rank(
        query: FloatArray,
        excludeKey: String?,
        range: DateRange?,
        limit: Int,
        embedMillis: Long,
        only: Set<String>? = null,
    ): SearchResult {
        val current = snapshot()
        return withContext(Dispatchers.Default) {
            val start = System.nanoTime()
            val dim = minOf(current.dim, query.size)
            val scores = FloatArray(current.size) { row ->
                val offset = row * current.dim
                var dot = 0f
                for (j in 0 until dim) dot += current.matrix[offset + j] * query[j]
                dot
            }
            val hits = current.photos.indices
                .filter { i ->
                    val photo = current.photos[i]
                    photo.key != excludeKey && (range == null || photo.dateTaken in range) &&
                        (only == null || photo.key in only)
                }
                .sortedByDescending { scores[it] }
                .take(limit)
                .map { Hit(current.photos[it], scores[it]) }
            SearchResult(hits, embedMillis, (System.nanoTime() - start) / 1_000_000)
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}
