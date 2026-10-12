package com.photofind.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photofind.index.IndexWorker
import com.photofind.photoFind
import com.photofind.search.Hit
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

data class UiState(
    val hasStorageAccess: Boolean = false,
    val modelPath: String? = null,
    val modelLocations: List<String> = emptyList(),
    val indexedCount: Int = 0,
    val indexing: IndexProgress? = null,
    val indexMessage: String? = null,
    val query: String = "",
    val similarTo: Hit? = null,
    val results: List<Hit> = emptyList(),
    val searching: Boolean = false,
    val searchMessage: String? = null,
)

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
                .collect { infos -> onWork(infos.firstOrNull()) }
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
        refreshCount()
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
        runSearch(similarTo = null) { app.search.searchText(query) }
    }

    fun findSimilar(hit: Hit) = runSearch(similarTo = hit) { app.search.similarTo(hit.photo.key) }

    fun clearSimilar() {
        _state.update { it.copy(similarTo = null, results = emptyList(), searchMessage = null) }
    }

    private fun runSearch(similarTo: Hit?, block: suspend () -> SearchResult) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, similarTo = similarTo, searchMessage = null) }
            val message = try {
                val result = block()
                _state.update { it.copy(results = result.hits) }
                if (result.hits.isEmpty()) "Nothing indexed yet"
                else "Query ${result.embedMillis} ms · ranking ${result.rankMillis} ms"
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            _state.update { it.copy(searching = false, searchMessage = message) }
        }
    }

    private fun onWork(info: WorkInfo?) {
        when (info?.state) {
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                val total = info.progress.getInt(IndexWorker.KEY_TOTAL, 0)
                val done = info.progress.getInt(IndexWorker.KEY_DONE, 0)
                _state.update { it.copy(indexing = IndexProgress(done, total), indexMessage = null) }
                refreshCount()
            }
            WorkInfo.State.SUCCEEDED -> {
                val added = info.outputData.getInt(IndexWorker.KEY_DONE, 0)
                val failed = info.outputData.getInt(IndexWorker.KEY_FAILED, 0)
                val message = when {
                    added == 0 -> "Index is up to date"
                    failed > 0 -> "Indexed $added photos ($failed could not be read)"
                    else -> "Indexed $added photos"
                }
                _state.update { it.copy(indexing = null, indexMessage = message) }
                refreshCount()
            }
            WorkInfo.State.FAILED -> {
                val error = info.outputData.getString(IndexWorker.KEY_ERROR) ?: "Indexing failed"
                _state.update { it.copy(indexing = null, indexMessage = error) }
            }
            else -> _state.update { it.copy(indexing = null) }
        }
    }

    private fun refreshCount() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { app.store.countEmbedded() }
            _state.update { it.copy(indexedCount = count) }
        }
    }
}
