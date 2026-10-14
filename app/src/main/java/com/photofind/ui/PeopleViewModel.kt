package com.photofind.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photofind.data.Person
import com.photofind.data.PersonFace
import com.photofind.faces.FaceEngine
import com.photofind.faces.FaceWorker
import com.photofind.photoFind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One person opened; [selected] holds face ids (as strings, for the shared grid gestures). */
data class PersonView(
    val person: Person,
    val faces: List<PersonFace> = emptyList(),
    val selected: Set<String> = emptySet(),
)

data class PeopleState(
    val open: Boolean = false,
    val missingModels: List<String> = emptyList(),
    val modelLocations: List<String> = emptyList(),
    val scanning: IndexProgress? = null,
    val scanMessage: String? = null,
    val people: List<Person> = emptyList(),
    val loaded: Boolean = false,
    val selectedPeople: Set<Long> = emptySet(),
    val person: PersonView? = null,
)

/** People found by face recognition: browsing them, naming, merging and correcting. */
class PeopleViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.photoFind
    private val _state = MutableStateFlow(PeopleState())
    val state: StateFlow<PeopleState> = _state.asStateFlow()

    private var lastGroupings = -1

    init {
        viewModelScope.launch {
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkFlow(FaceWorker.WORK_NAME)
                .collect(::onWork)
        }
    }

    fun open() {
        val engine = FaceEngine(getApplication())
        val missing = engine.missingModels()
        _state.update {
            it.copy(
                open = true,
                missingModels = missing,
                modelLocations = engine.modelLocations().map { f -> f.absolutePath },
            )
        }
        reload()
        if (missing.isEmpty()) FaceWorker.start(getApplication())
    }

    fun close() = _state.update { it.copy(open = false, person = null, selectedPeople = emptySet()) }

    /** Clears a selection first, then leaves the person, then the People screen. */
    fun back() {
        val s = _state.value
        when {
            s.person?.selected?.isNotEmpty() == true -> setFaceSelection(emptySet())
            s.person != null -> _state.update { it.copy(person = null) }
            s.selectedPeople.isNotEmpty() -> _state.update { it.copy(selectedPeople = emptySet()) }
            else -> close()
        }
    }

    // People list

    fun togglePerson(id: Long) = _state.update {
        it.copy(selectedPeople = if (id in it.selectedPeople) it.selectedPeople - id else it.selectedPeople + id)
    }

    fun clearPeopleSelection() = _state.update { it.copy(selectedPeople = emptySet()) }

    /** Joins the selected people into one: the named one if any, else the one with most faces. */
    fun mergeSelected() {
        val s = _state.value
        val chosen = s.people.filter { it.id in s.selectedPeople }
        if (chosen.size < 2) return
        val into = chosen.sortedWith(compareByDescending<Person> { it.name != null }.thenByDescending { it.faceCount }).first()
        _state.update { it.copy(selectedPeople = emptySet()) }
        write { app.faces.merge(chosen.map { it.id }, into.id) }
    }

    fun openPerson(person: Person) {
        _state.update { it.copy(person = PersonView(person)) }
        reloadPerson()
    }

    // One person

    fun rename(name: String) {
        val person = _state.value.person?.person ?: return
        write { app.faces.rename(person.id, name) }
    }

    fun toggleFace(id: String) = _state.update { s ->
        val view = s.person ?: return@update s
        s.copy(person = view.copy(selected = if (id in view.selected) view.selected - id else view.selected + id))
    }

    fun setFaceSelection(ids: Set<String>) = _state.update { s ->
        s.copy(person = s.person?.copy(selected = ids))
    }

    fun selectAllFaces() = _state.update { s ->
        s.copy(person = s.person?.let { view -> view.copy(selected = view.faces.mapTo(HashSet()) { it.id.toString() }) })
    }

    /** "Not this person": takes the selected faces out and keeps them out. */
    fun removeSelectedFaces() {
        val view = _state.value.person ?: return
        val ids = view.selected.map { it.toLong() }
        setFaceSelection(emptySet())
        write { app.faces.remove(view.person.id, ids) }
    }

    fun moveSelectedFaces(to: Person) {
        val view = _state.value.person ?: return
        val ids = view.selected.map { it.toLong() }
        setFaceSelection(emptySet())
        write { app.faces.move(ids, to.id) }
    }

    /** Joins the open person into [other], which stays open. */
    fun mergeInto(other: Person) {
        val view = _state.value.person ?: return
        write(then = { openPerson(_state.value.people.firstOrNull { it.id == other.id } ?: other) }) {
            app.faces.merge(listOf(view.person.id), other.id)
        }
    }

    // Loading

    private fun write(then: () -> Unit = {}, block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block() }
            reload()
            reloadPerson()
            then()
        }
    }

    private fun reload() {
        viewModelScope.launch {
            val people = withContext(Dispatchers.IO) { app.faces.people() }
            _state.update { s ->
                val ids = people.mapTo(HashSet()) { it.id }
                s.copy(
                    people = people,
                    loaded = true,
                    selectedPeople = s.selectedPeople.filterTo(HashSet()) { it in ids },
                    // The open person was merged away or emptied: leave it.
                    person = s.person?.let { view ->
                        people.firstOrNull { it.id == view.person.id }?.let { view.copy(person = it) }
                    },
                )
            }
        }
    }

    private fun reloadPerson() {
        val id = _state.value.person?.person?.id ?: return
        viewModelScope.launch {
            val faces = withContext(Dispatchers.IO) { app.faces.facesOf(id) }
            _state.update { s ->
                val view = s.person?.takeIf { it.person.id == id } ?: return@update s
                val ids = faces.mapTo(HashSet()) { it.id.toString() }
                s.copy(person = view.copy(faces = faces, selected = view.selected.filterTo(HashSet()) { it in ids }))
            }
        }
    }

    private fun onWork(infos: List<WorkInfo>) {
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running != null) {
            val total = running.progress.getInt(FaceWorker.KEY_TOTAL, 0)
            val done = running.progress.getInt(FaceWorker.KEY_DONE, 0)
            val groupings = running.progress.getInt(FaceWorker.KEY_GROUPINGS, 0)
            _state.update { it.copy(scanning = IndexProgress(done, total), scanMessage = null) }
            if (groupings != lastGroupings) {
                lastGroupings = groupings
                reload()
            }
            return
        }
        _state.update { it.copy(scanning = null) }
        val finished = infos.lastOrNull { it.state.isFinished } ?: return
        val message = when (finished.state) {
            WorkInfo.State.SUCCEEDED -> {
                val scanned = finished.outputData.getInt(FaceWorker.KEY_DONE, 0)
                val faces = finished.outputData.getInt(FaceWorker.KEY_FACES, 0)
                if (scanned > 0) "Scanned $scanned photos · found $faces faces" else null
            }
            WorkInfo.State.FAILED -> finished.outputData.getString(FaceWorker.KEY_ERROR) ?: "Face scan failed"
            else -> null
        }
        _state.update { it.copy(scanMessage = message) }
        reload()
        reloadPerson()
    }
}
