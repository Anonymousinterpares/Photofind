package com.photofind.search

/**
 * Photos that look the same. [keep] is the suggested photo to keep; [similarity] is the lowest
 * cosine similarity of a member to the photo the group was built around.
 */
data class PhotoGroup(val photos: List<IndexedPhoto>, val keep: IndexedPhoto, val similarity: Float) {
    val key: String get() = photos.first().key
    val extras: List<IndexedPhoto> get() = photos.filter { it.key != keep.key }

    /** The group after [deleted] are gone, or null when fewer than two photos are left. */
    fun without(deleted: Set<String>): PhotoGroup? {
        val left = photos.filterNot { it.key in deleted }
        if (left.size < 2) return null
        return copy(photos = left, keep = if (keep.key in deleted) left.maxBy { it.size } else keep)
    }
}

/** Same byte size and an embedding this close means the same image file. */
private const val IDENTICAL = 0.999f

/** Similar shots are only looked for among photos taken this close together. */
private const val SIMILAR_WINDOW_MILLIS = 2L * 24 * 60 * 60 * 1000

private fun Snapshot.dot(a: Int, b: Int): Float {
    val ao = a * dim
    val bo = b * dim
    var sum = 0f
    for (j in 0 until dim) sum += matrix[ao + j] * matrix[bo + j]
    return sum
}

/**
 * Prefers the copy Android can move to the trash, then one in the camera folder or an app's
 * current media folder over legacy and download folders, then the shortest path.
 */
private val copyPreference = compareByDescending<IndexedPhoto> { it.uri.scheme == "content" }
    .thenByDescending { "/DCIM/" in it.key || "/Android/media/" in it.key }
    .thenBy { it.key.length }

/** Rows grouped as identical copies; size buckets keep this linear. */
private fun Snapshot.copyRows(): List<List<Int>> {
    val out = ArrayList<List<Int>>()
    photos.indices.groupBy { photos[it].size }.values.forEach { bucket ->
        var left = bucket
        while (left.size > 1) {
            val first = left.first()
            val (same, rest) = left.drop(1).partition { dot(first, it) >= IDENTICAL }
            if (same.isNotEmpty()) out += listOf(first) + same
            left = rest
        }
    }
    return out
}

fun Snapshot.identicalCopies(): List<PhotoGroup> =
    copyRows()
        .map { rows ->
            val group = rows.map { photos[it] }
            PhotoGroup(group, group.sortedWith(copyPreference).first(), similarity = 1f)
        }
        .sortedByDescending { it.keep.dateTaken }

/**
 * Greedy grouping in date order: each photo not yet grouped starts a group of every later
 * ungrouped photo within [SIMILAR_WINDOW_MILLIS] at least [threshold] similar to it. Comparing
 * against the first photo only (not any member) stops a long burst chaining into one group.
 */
fun Snapshot.similarShots(threshold: Float): List<PhotoGroup> {
    val skip = BooleanArray(size)
    // Identical copies take part once, through the copy that would be kept.
    copyRows().forEach { rows ->
        val keep = rows.minWith(compareBy(copyPreference) { photos[it] })
        rows.forEach { if (it != keep) skip[it] = true }
    }
    val out = ArrayList<PhotoGroup>()
    for (i in 0 until size) {
        if (skip[i]) continue
        val members = arrayListOf(i)
        var lowest = 1f
        var j = i + 1
        // Photos are newest first, so later rows are older.
        while (j < size && photos[i].dateTaken - photos[j].dateTaken <= SIMILAR_WINDOW_MILLIS) {
            if (!skip[j]) {
                val score = dot(i, j)
                if (score >= threshold) {
                    members += j
                    lowest = minOf(lowest, score)
                }
            }
            j++
        }
        if (members.size < 2) continue
        members.forEach { skip[it] = true }
        val group = members.map { photos[it] }
        out += PhotoGroup(group, group.maxBy { it.size }, lowest)
    }
    return out
}
