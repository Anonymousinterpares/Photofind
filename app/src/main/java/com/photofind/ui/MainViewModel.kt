package com.photofind.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.graphics.RectF
import com.photofind.data.Person
import com.photofind.data.PhotoFace
import com.photofind.faces.ManualTagger
import com.photofind.index.IndexWorker
import com.photofind.photoFind
import com.photofind.search.DateRange
import com.photofind.search.Hit
import com.photofind.search.IndexedPhoto
import com.photofind.search.PeopleQuery
import com.photofind.search.PhotoGroup
import com.photofind.search.parsePeopleQuery
import com.photofind.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class IndexProgress(val done: Int, val total: Int)

enum class SortOrder(val label: String) {
    Relevance("Best match"),
    Newest("Newest first"),
    Oldest("Oldest first"),
}

sealed interface ActiveSearch {
    data class Text(val query: String) : ActiveSearch
    data class Similar(val photo: IndexedPhoto) : ActiveSearch
}

enum class DuplicateMode(val label: String) {
    Copies("Identical copies"),
    Similar("Similar shots"),
}

enum class Likeness(val label: String, val threshold: Float) {
    Strict("Nearly identical", 0.97f),
    Normal("Very similar", 0.93f),
    Loose("Similar", 0.90f),
}

/** The duplicates screen; [groups] is null while they are being found. */
data class DuplicatesView(
    val mode: DuplicateMode = DuplicateMode.Copies,
    val likeness: Likeness = Likeness.Normal,
    val groups: List<PhotoGroup>? = null,
)

/** A photo opened full screen; [score] is set when it came from a search; [faces] once loaded. */
data class Selection(val photo: IndexedPhoto, val score: Float?, val faces: List<PhotoFace> = emptyList())

/** [trash] goes to the system trash; [permanent] (hidden folders) can only be deleted outright. */
data class DeletePlan(val trash: List<IndexedPhoto>, val permanent: List<IndexedPhoto>) {
    val size: Int get() = trash.size + permanent.size
}

/**
 * Tagging people by hand on the open photo: [box] (0..1 photo coordinates) was just drawn, or
 * [faceId] tapped, and waits for a person to be picked; [people] are the choices.
 */
data class Tagging(
    val people: List<Person> = emptyList(),
    val box: RectF? = null,
    val faceId: Long? = null,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val picking: Boolean get() = (box != null || faceId != null) && !saving
}

/** One step of navigation history, restored as-is by [MainViewModel.back]. */
private data class View(
    val activeSearch: ActiveSearch?,
    val query: String,
    val results: List<Hit>,
    val searchMessage: String?,
    val selection: Selection?,
    val resultsScroll: Int,
)

data class UiState(
    val hasStorageAccess: Boolean = false,
    val modelPath: String? = null,
    val modelLocations: List<String> = emptyList(),
    val indexedCount: Int = 0,
    val indexing: IndexProgress? = null,
    val indexMessage: String? = null,
    val library: List<IndexedPhoto> = emptyList(),
    val query: String = "",
    val activeSearch: ActiveSearch? = null,
    val results: List<Hit> = emptyList(),
    val sort: SortOrder = SortOrder.Relevance,
    val dateRange: DateRange? = null,
    val searching: Boolean = false,
    val searchMessage: String? = null,
    val selection: Selection? = null,
    val canGoBack: Boolean = false,
    /** First visible result; the results grid scrolls here whenever [scrollRequest] changes. */
    val resultsScroll: Int = 0,
    val scrollRequest: Int = 0,
    val selectedKeys: Set<String> = emptySet(),
    val deleteConfirm: DeletePlan? = null,
    val trashRequest: List<Uri>? = null,
    val notice: String? = null,
    val duplicates: DuplicatesView? = null,
    /** Name tags over faces in the full-screen photo; remembered between runs. */
    val showFaceTags: Boolean = true,
    val tagging: Tagging? = null,
    /** Counts finished deletions, so other screens (People) know to reload. */
    val deletions: Int = 0,
) {
    val selecting: Boolean get() = selectedKeys.isNotEmpty()

    val libraryInRange: List<IndexedPhoto>
        get() = dateRange?.let { range -> library.filter { it.dateTaken in range } } ?: library

    val sortedResults: List<Hit>
        get() = when (sort) {
            SortOrder.Relevance -> results
            SortOrder.Newest -> results.sortedByDescending { it.photo.dateTaken }
            SortOrder.Oldest -> results.sortedBy { it.photo.dateTaken }
        }

    /** Keys of the photos currently on screen, for "select all". */
    val visibleKeys: List<String>
        get() = if (activeSearch == null) libraryInRange.map { it.key } else sortedResults.map { it.photo.key }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.photoFind
    private val prefs = application.getSharedPreferences("photofind", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(showFaceTags = prefs.getBoolean(PREF_FACE_TAGS, true)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val history = ArrayDeque<View>()
    private val deletedKeys = HashSet<String>()
    private var pendingDelete: DeletePlan? = null
    private var deletingFromDetail = false
    private var searchJob: Job? = null
    private var duplicatesJob: Job? = null
    private var autoIndexed = false

    init {
        viewModelScope.launch {
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkFlow(IndexWorker.WORK_NAME)
                .collect(::onWork)
        }
    }

    fun refreshEnvironment() {
        val access = Environment.isExternalStorageManager()
        val model = app.embedder.modelFile()
        _state.update {
            it.copy(
                hasStorageAccess = access,
                modelPath = model?.absolutePath,
                modelLocations = app.embedder.modelLocations().map { f -> f.absolutePath },
            )
        }
        refreshLibrary()
        if (access && model != null && !autoIndexed) {
            autoIndexed = true
            IndexWorker.start(getApplication())
        }
    }

    fun reindex() = IndexWorker.start(getApplication(), restart = true)

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    // Navigation

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        pushHistory()
        _state.update { it.copy(selection = null) }
        runSearch(ActiveSearch.Text(query))
    }

    fun findSimilar(photo: IndexedPhoto) {
        pushHistory()
        _state.update { it.copy(selection = null) }
        runSearch(ActiveSearch.Similar(photo))
    }

    fun open(photo: IndexedPhoto, score: Float?) {
        pushHistory()
        _state.update { it.copy(selection = Selection(photo, score), tagging = null) }
        loadFaces()
    }

    /**
     * Moves to another photo already on screen. Unlike [open], this does not add a back step,
     * so Back still returns to the grid after swiping through several photos.
     */
    fun browseTo(photo: IndexedPhoto, score: Float?) {
        val current = _state.value.selection ?: return
        if (current.photo.key == photo.key) return
        // Drop the previous photo's face boxes immediately; they would be drawn on the wrong picture.
        _state.update { it.copy(selection = Selection(photo, score), tagging = null) }
        loadFaces()
    }

    // Tagging people by hand

    fun startTagging() {
        _state.update { it.copy(tagging = Tagging()) }
        viewModelScope.launch {
            val people = withContext(Dispatchers.IO) { app.faces.people() }
            _state.update { s -> s.copy(tagging = s.tagging?.copy(people = people)) }
        }
    }

    fun stopTagging() = _state.update { it.copy(tagging = null) }

    fun tagBox(box: RectF) = _state.update { s -> s.copy(tagging = s.tagging?.copy(box = box, faceId = null, message = null)) }

    fun tagFace(faceId: Long) = _state.update { s -> s.copy(tagging = s.tagging?.copy(faceId = faceId, box = null, message = null)) }

    fun cancelPick() = _state.update { s -> s.copy(tagging = s.tagging?.copy(box = null, faceId = null)) }

    /** Tags the drawn box or tapped face as [person], or as a new person named [newName]. */
    fun tagAs(person: Person?, newName: String? = null) {
        val tagging = _state.value.tagging ?: return
        val photo = _state.value.selection?.photo ?: return
        _state.update { it.copy(tagging = tagging.copy(saving = true)) }
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val faceId = tagging.faceId
                    val box = tagging.box
                    val name = person?.name ?: newName?.trim()?.ifEmpty { null } ?: "an unnamed person"
                    when {
                        faceId != null -> {
                            if (person != null) app.faces.move(listOf(faceId), person.id) else app.faces.newPerson(listOf(faceId), newName)
                            "Tagged $name"
                        }
                        box != null -> {
                            val recognised = ManualTagger.tag(getApplication(), photo, box, person?.id, newName)
                            if (recognised) "Tagged $name · face recognised, it helps find more photos"
                            else "Tagged $name · no face found in the box, saved as a tag only"
                        }
                        else -> null
                    }
                } catch (e: Exception) {
                    "Could not tag: ${e.message ?: e}"
                }
            }
            val people = withContext(Dispatchers.IO) { app.faces.people() }
            _state.update { s ->
                s.copy(tagging = s.tagging?.copy(people = people, box = null, faceId = null, saving = false, message = message))
            }
            loadFaces()
        }
    }

    fun toggleFaceTags() {
        val show = !_state.value.showFaceTags
        prefs.edit().putBoolean(PREF_FACE_TAGS, show).apply()
        _state.update { it.copy(showFaceTags = show) }
    }

    /** (Re)loads the faces of the open photo, e.g. after people were renamed. */
    fun loadFaces() {
        val key = _state.value.selection?.photo?.key ?: return
        viewModelScope.launch {
            val faces = withContext(Dispatchers.IO) { app.faces.facesIn(key) }
            _state.update { s ->
                s.copy(selection = s.selection?.takeIf { it.photo.key == key }?.copy(faces = faces) ?: s.selection)
            }
        }
    }

    /** Undoes one step: clears a selection first, then walks back through the history. */
    fun back() {
        if (_state.value.selecting) {
            clearSelected()
            return
        }
        val previous = history.removeLastOrNull() ?: return
        searchJob?.cancel()
        _state.update {
            it.copy(
                activeSearch = previous.activeSearch,
                query = previous.query,
                results = previous.results.filterNot { hit -> hit.photo.key in deletedKeys },
                searchMessage = previous.searchMessage,
                selection = previous.selection?.takeUnless { s -> s.photo.key in deletedKeys },
                resultsScroll = previous.resultsScroll,
                scrollRequest = it.scrollRequest + 1,
                searching = false,
                canGoBack = history.isNotEmpty(),
            )
        }
    }

    fun goHome() {
        searchJob?.cancel()
        history.clear()
        _state.update {
            it.copy(
                activeSearch = null,
                results = emptyList(),
                query = "",
                searching = false,
                searchMessage = null,
                selection = null,
                selectedKeys = emptySet(),
                canGoBack = false,
            )
        }
    }

    fun onResultsScrolled(firstVisible: Int) = _state.update { it.copy(resultsScroll = firstVisible) }

    private fun pushHistory() {
        val s = _state.value
        history.addLast(View(s.activeSearch, s.query, s.results, s.searchMessage, s.selection, s.resultsScroll))
        if (history.size > MAX_HISTORY) history.removeFirst()
        _state.update { it.copy(canGoBack = true) }
    }

    // Filters

    fun setSort(sort: SortOrder) = _state.update {
        it.copy(sort = sort, resultsScroll = 0, scrollRequest = it.scrollRequest + 1)
    }

    fun setDateRange(range: DateRange?) {
        _state.update { it.copy(dateRange = range) }
        _state.value.activeSearch?.let(::runSearch)
    }

    // Selection and deletion

    fun toggleSelected(key: String) = _state.update {
        it.copy(selectedKeys = if (key in it.selectedKeys) it.selectedKeys - key else it.selectedKeys + key)
    }

    fun setSelected(keys: Set<String>) = _state.update { it.copy(selectedKeys = keys) }

    fun selectAll() = _state.update { it.copy(selectedKeys = it.visibleKeys.toSet()) }

    fun clearSelected() = _state.update { it.copy(selectedKeys = emptySet()) }

    fun deletePhoto(photo: IndexedPhoto) {
        deletingFromDetail = true
        _state.update { it.copy(selectedKeys = setOf(photo.key)) }
        requestDelete()
    }

    /** Deletes [photos] chosen outside the main grids (People, Duplicates), via the same trash flow. */
    fun deletePhotos(photos: List<IndexedPhoto>) {
        if (photos.isEmpty()) return
        _state.update { it.copy(selectedKeys = emptySet()) }
        val (trash, permanent) = photos.distinctBy { it.key }.partition { it.uri.scheme == "content" }
        val plan = DeletePlan(trash, permanent)
        if (permanent.isEmpty()) startDelete(plan) else _state.update { it.copy(deleteConfirm = plan) }
    }

    fun requestDelete() {
        val s = _state.value
        val byKey = s.library.associateBy { it.key }
        val photos = s.selectedKeys.mapNotNull { byKey[it] }
        if (photos.isEmpty()) return
        val (trash, permanent) = photos.partition { it.uri.scheme == "content" }
        val plan = DeletePlan(trash, permanent)
        if (permanent.isEmpty()) startDelete(plan) else _state.update { it.copy(deleteConfirm = plan) }
    }

    fun confirmDelete() {
        val plan = _state.value.deleteConfirm ?: return
        _state.update { it.copy(deleteConfirm = null) }
        startDelete(plan)
    }

    fun cancelDelete() {
        _state.update { it.copy(deleteConfirm = null) }
        endDetailDelete()
    }

    /** A delete started from the full-screen photo shouldn't leave that photo selected. */
    private fun endDetailDelete() {
        if (deletingFromDetail) clearSelected()
        deletingFromDetail = false
    }

    private fun startDelete(plan: DeletePlan) {
        pendingDelete = plan
        if (plan.trash.isEmpty()) {
            onTrashResult(confirmed = true)
        } else {
            _state.update { it.copy(trashRequest = plan.trash.map { p -> p.uri }) }
        }
    }

    fun onTrashLaunched() = _state.update { it.copy(trashRequest = null) }

    /** Called after Android's own "Move to trash?" dialog; cancelling there aborts the whole delete. */
    fun onTrashResult(confirmed: Boolean) {
        val plan = pendingDelete ?: return
        pendingDelete = null
        _state.update { it.copy(trashRequest = null) }
        if (!confirmed) {
            endDetailDelete()
            return
        }
        deletingFromDetail = false
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                val deletedFiles = plan.permanent.filter { File(it.key).delete() }
                val keys = (plan.trash + deletedFiles).map { it.key }
                app.store.delete(keys)
                keys
            }
            app.search.invalidate()
            deletedKeys += removed
            val failed = plan.size - removed.size
            _state.update { s ->
                s.copy(
                    library = s.library.filterNot { it.key in deletedKeys },
                    indexedCount = s.indexedCount - removed.size,
                    results = s.results.filterNot { it.photo.key in deletedKeys },
                    selection = s.selection?.takeUnless { it.photo.key in deletedKeys },
                    duplicates = s.duplicates?.let { d ->
                        d.copy(groups = d.groups?.mapNotNull { it.without(deletedKeys) })
                    },
                    selectedKeys = emptySet(),
                    deletions = s.deletions + 1,
                    notice = buildString {
                        if (plan.trash.isNotEmpty()) append("Moved ${plan.trash.size} to trash")
                        val permanent = removed.size - plan.trash.size
                        if (permanent > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("Deleted $permanent permanently")
                        }
                        if (failed > 0) append(" · $failed could not be deleted")
                    },
                )
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // Duplicates

    fun openDuplicates() {
        _state.update { it.copy(duplicates = DuplicatesView(), selectedKeys = emptySet()) }
        findDuplicates()
    }

    fun closeDuplicates() {
        duplicatesJob?.cancel()
        _state.update { it.copy(duplicates = null, selectedKeys = emptySet()) }
    }

    /** Back on the duplicates screen: clears a selection first, then closes the screen. */
    fun duplicatesBack() = if (_state.value.selecting) clearSelected() else closeDuplicates()

    fun setDuplicateMode(mode: DuplicateMode) {
        _state.update { it.copy(duplicates = it.duplicates?.copy(mode = mode), selectedKeys = emptySet()) }
        findDuplicates()
    }

    fun setLikeness(likeness: Likeness) {
        _state.update { it.copy(duplicates = it.duplicates?.copy(likeness = likeness), selectedKeys = emptySet()) }
        findDuplicates()
    }

    /** Selects every photo except the suggested one to keep, in [group] or in all groups. */
    fun selectExtras(group: PhotoGroup? = null) = _state.update { s ->
        val groups = group?.let(::listOf) ?: s.duplicates?.groups.orEmpty()
        val keep = groups.mapTo(HashSet()) { it.keep.key }
        val extras = groups.flatMap { g -> g.extras.map { it.key } }
        s.copy(selectedKeys = s.selectedKeys - keep + extras)
    }

    private fun findDuplicates() {
        val view = _state.value.duplicates ?: return
        duplicatesJob?.cancel()
        _state.update { it.copy(duplicates = view.copy(groups = null)) }
        duplicatesJob = viewModelScope.launch {
            val groups = when (view.mode) {
                DuplicateMode.Copies -> app.search.identicalCopies()
                DuplicateMode.Similar -> app.search.similarShots(view.likeness.threshold)
            }.mapNotNull { it.without(deletedKeys) }
            _state.update { it.copy(duplicates = it.duplicates?.copy(groups = groups)) }
        }
    }

    // Search and indexing

    private fun runSearch(search: ActiveSearch) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, activeSearch = search, searchMessage = null) }
            val range = _state.value.dateRange
            val message = try {
                val people = if (search is ActiveSearch.Text) peopleIn(search.query) else null
                val result: SearchResult = when {
                    search is ActiveSearch.Similar -> app.search.similarTo(search.photo.key, range)
                    people == null -> app.search.searchText((search as ActiveSearch.Text).query, range)
                    people.query.rest.isBlank() -> app.search.photos(people.photoKeys, range)
                    else -> app.search.searchText(people.query.rest, range, only = people.photoKeys)
                }
                _state.update {
                    it.copy(results = result.hits, resultsScroll = 0, scrollRequest = it.scrollRequest + 1)
                }
                val names = people?.query?.names?.joinToString(" & ")
                when {
                    result.hits.isEmpty() -> if (names != null) "No photos of $names match" else "No photos match"
                    search is ActiveSearch.Similar -> "${result.hits.size} similar photos"
                    people != null && people.query.rest.isBlank() -> "${result.hits.size} photos of $names"
                    people != null -> "${result.hits.size} best matches with $names"
                    else -> "${result.hits.size} best matches · ${result.embedMillis + result.rankMillis} ms"
                }
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            _state.update { it.copy(searching = false, searchMessage = message) }
        }
    }

    private class PeopleSearch(val query: PeopleQuery, val photoKeys: Set<String>)

    /** The named people a search mentions and the photos showing all of them; null if it names nobody. */
    private suspend fun peopleIn(query: String): PeopleSearch? = withContext(Dispatchers.IO) {
        val named = app.faces.people().filter { it.name != null }
        val parsed = parsePeopleQuery(query, named)
        if (parsed.people.isEmpty()) return@withContext null
        // Photos showing every name; a name shared by several (unmerged) people means any of them.
        val keys = parsed.people.map { sameName -> app.faces.photosOf(sameName.map { it.id }) }
            .reduce { all, next -> all intersect next }
        PeopleSearch(parsed, keys)
    }

    private fun onWork(infos: List<WorkInfo>) {
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running != null) {
            val total = running.progress.getInt(IndexWorker.KEY_TOTAL, 0)
            val done = running.progress.getInt(IndexWorker.KEY_DONE, 0)
            _state.update { it.copy(indexing = IndexProgress(done, total), indexMessage = null) }
            refreshCount()
            return
        }
        _state.update { it.copy(indexing = null) }
        val finished = infos.lastOrNull { it.state.isFinished } ?: return
        when (finished.state) {
            WorkInfo.State.SUCCEEDED -> {
                val added = finished.outputData.getInt(IndexWorker.KEY_DONE, 0)
                val failed = finished.outputData.getInt(IndexWorker.KEY_FAILED, 0)
                val message = when {
                    added == 0 -> null
                    failed > 0 -> "Added $added new photos ($failed could not be read)"
                    else -> "Added $added new photos"
                }
                _state.update { it.copy(indexMessage = message) }
                refreshLibrary()
            }
            WorkInfo.State.FAILED -> {
                val error = finished.outputData.getString(IndexWorker.KEY_ERROR) ?: "Indexing failed"
                _state.update { it.copy(indexMessage = error) }
            }
            else -> Unit
        }
    }

    private fun refreshLibrary() {
        viewModelScope.launch {
            val library = app.search.library()
            _state.update { it.copy(library = library, indexedCount = library.size) }
        }
    }

    private fun refreshCount() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { app.store.countEmbedded() }
            _state.update { it.copy(indexedCount = count) }
        }
    }

    companion object {
        private const val MAX_HISTORY = 50
        private const val PREF_FACE_TAGS = "face_tags"
    }
}
