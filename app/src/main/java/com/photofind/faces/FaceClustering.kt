package com.photofind.faces

import com.photofind.data.FaceStore
import com.photofind.data.StoredFace
import kotlin.math.sqrt

/** Cosine similarity at which a face counts as the same person as a group's average face. */
private const val SAME_PERSON = 0.40f

/** Groups whose average faces are this similar are one person seen differently (glasses, age). */
private const val MERGE_GROUPS = 0.50f

/** A new group becomes a proposed person once it has this many faces. */
private const val MIN_FACES = 3

/**
 * Places faces that have no person yet. Each goes to the existing person whose average face it
 * matches best (unless the user removed it from that person); the rest are grouped among
 * themselves, and groups big enough become new, unnamed people. Faces the user removed from a
 * person never start a new one, so "Not them" on a statue or a stranger doesn't bring it back
 * as a new person. Faces already in a person are never moved, so the user's merges and
 * corrections stay as they are.
 */
fun clusterFaces(store: FaceStore) {
    val faces = store.allFaces()
    if (faces.isEmpty()) return
    val rejected = store.rejections()

    val people = faces.filter { it.personId != null }.groupBy { it.personId!! }
    val centroids = people.mapValues { (_, members) -> Centroid(members.first().embedding.size).apply { members.forEach { add(it) } } }

    val assignments = HashMap<Long, MutableList<Long>>()
    val leftovers = ArrayList<StoredFace>()
    for (face in faces) {
        if (face.personId != null) continue
        val refused = rejected[face.id].orEmpty()
        var best: Long? = null
        var bestScore = SAME_PERSON
        centroids.forEach { (personId, centroid) ->
            if (personId in refused) return@forEach
            val score = centroid.similarity(face.embedding)
            if (score >= bestScore) {
                best = personId
                bestScore = score
            }
        }
        val personId = best
        when {
            personId != null -> assignments.getOrPut(personId) { ArrayList() } += face.id
            refused.isEmpty() -> leftovers += face
        }
    }

    val newPeople = groupFaces(leftovers).filter { it.size >= MIN_FACES }.map { group -> group.map { it.id } }
    if (assignments.isNotEmpty() || newPeople.isNotEmpty()) store.assign(assignments, newPeople)
}

/**
 * Leader clustering: each face joins the most similar group so far if close enough to its
 * average face, else starts a new one. Groups whose averages end up close are then joined.
 */
private fun groupFaces(faces: List<StoredFace>): List<List<StoredFace>> {
    if (faces.isEmpty()) return emptyList()
    val dim = faces.first().embedding.size
    val centroids = ArrayList<Centroid>()
    val groups = ArrayList<MutableList<StoredFace>>()
    for (face in faces) {
        var best = -1
        var bestScore = SAME_PERSON
        for (g in centroids.indices) {
            val score = centroids[g].similarity(face.embedding)
            if (score >= bestScore) {
                best = g
                bestScore = score
            }
        }
        if (best >= 0) {
            centroids[best].add(face)
            groups[best] += face
        } else {
            centroids += Centroid(dim).apply { add(face) }
            groups += mutableListOf(face)
        }
    }

    // Join groups (of two or more faces) whose averages are close; union-find over the pairs.
    val candidates = groups.indices.filter { groups[it].size >= 2 }
    val parent = IntArray(groups.size) { it }
    fun root(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        return r
    }
    for (x in candidates.indices) {
        for (y in x + 1 until candidates.size) {
            val a = candidates[x]
            val b = candidates[y]
            if (centroids[a].similarity(centroids[b]) >= MERGE_GROUPS) parent[root(b)] = root(a)
        }
    }
    return groups.indices.groupBy(::root).values.map { members -> members.flatMap { groups[it] } }
}

/** Running sum of embeddings; similarity is against their normalised average. */
private class Centroid(dim: Int) {
    val sum = FloatArray(dim)
    private var norm = 0f

    fun add(face: StoredFace) {
        for (i in sum.indices) sum[i] += face.embedding[i]
        norm = sqrt(sum.fold(0f) { acc, v -> acc + v * v })
    }

    fun similarity(vector: FloatArray): Float {
        var dot = 0f
        for (i in sum.indices) dot += sum[i] * vector[i]
        return dot / norm
    }

    fun similarity(other: Centroid): Float = similarity(other.sum) / other.norm
}
