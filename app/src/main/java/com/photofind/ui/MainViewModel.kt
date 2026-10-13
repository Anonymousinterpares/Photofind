package com.photofind.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photofind.index.IndexWorker
import com.photofind.photoFind
import com.photofind.search.DateRange
import com.photofind.search.Hit
import com.photofind.search.IndexedPhoto
import com.photofind.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** A photo opened full screen; [score] is set when it came from a search. */
data class Selection(val photo: IndexedPhoto, val score: Float?)

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
) {
    val libraryInRange: List<IndexedPhoto>
        get() = dateRange?.let { range -> library.filter { it.dateTaken in range } } ?: library

    val sortedResults: List<Hit>
        get() = when (sort) {
            SortOrder.Relevance -> results
            SortOrder.Newest -> results.sortedByDescending { it.photo.dateTaken }
            SortOrder.Oldest -> results.sortedBy { it.photo.dateTaken }
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.photoFind
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
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

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        runSearch(ActiveSearch.Text(query))
    }

    fun findSimilar(photo: IndexedPhoto) {
        _state.update { it.copy(selection = null) }
        runSearch(ActiveSearch.Similar(photo))
    }

    fun goHome() {
        searchJob?.cancel()
        _state.update {
            it.copy(activeSearch = null, results = emptyList(), query = "", searching = false, searchMessage = null)
        }
    }

    fun setSort(sort: SortOrder) = _state.update { it.copy(sort = sort) }

    fun setDateRange(range: DateRange?) {
        _state.update { it.copy(dateRange = range) }
        _state.value.activeSearch?.let(::runSearch)
    }

    fun select(photo: IndexedPhoto, score: Float?) = _state.update { it.copy(selection = Selection(photo, score)) }

    fun closeSelection() = _state.update { it.copy(selection = null) }

    private fun runSearch(search: ActiveSearch) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, activeSearch = search, searchMessage = null) }
            val range = _state.value.dateRange
            val message = try {
                val result: SearchResult = when (search) {
                    is ActiveSearch.Text -> app.search.searchText(search.query, range)
                    is ActiveSearch.Similar -> app.search.similarTo(search.photo.key, range)
                }
                _state.update { it.copy(results = result.hits) }
                when {
                    result.hits.isEmpty() -> "No photos match"
                    search is ActiveSearch.Similar -> "${result.hits.size} similar photos"
                    else -> "${result.hits.size} best matches · ${result.embedMillis + result.rankMillis} ms"
                }
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            _state.update { it.copy(searching = false, searchMessage = message) }
        }
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
}
