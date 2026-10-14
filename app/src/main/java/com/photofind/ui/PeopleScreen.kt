package com.photofind.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.photofind.data.Person
import com.photofind.data.PersonFace
import com.photofind.photoFind
import com.photofind.search.IndexedPhoto
import java.text.NumberFormat

/** People found in the photos; a person opens their faces, where wrong ones can be taken out. */
@Composable
fun PeopleScreen(state: PeopleState, viewModel: PeopleViewModel) {
    BackHandler { viewModel.back() }
    val person = state.person
    if (person != null) {
        PersonScreen(state, person, viewModel)
        return
    }

    Scaffold(
        topBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(4.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.selectedPeople.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearPeopleSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                        Text(
                            "${state.selectedPeople.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = viewModel::mergeSelected,
                            enabled = state.selectedPeople.size >= 2,
                        ) { Text("Same person – merge") }
                    } else {
                        IconButton(onClick = viewModel::close) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text("People", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                }
                ScanStatus(state)
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.missingModels.isNotEmpty() -> Card(Modifier.padding(12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Face models missing", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Copy ${state.missingModels.joinToString(" and ")} (InsightFace buffalo_s) to one of:\n" +
                                state.modelLocations.joinToString("\n"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                state.loaded && state.people.isEmpty() -> Text(
                    if (state.scanning != null) "People appear here as faces are found…" else "No people found",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    textAlign = TextAlign.Center,
                )
                else -> PeopleGrid(state, viewModel)
            }
        }
    }
}

@Composable
private fun ScanStatus(state: PeopleState) {
    val progress = state.scanning
    val numbers = NumberFormat.getIntegerInstance()
    val text = when {
        progress == null -> state.scanMessage ?: "${numbers.format(state.people.size)} people"
        progress.total == 0 -> "Preparing face scan…"
        else -> "Looking for faces · ${numbers.format(progress.done)} / ${numbers.format(progress.total)} photos"
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeopleGrid(state: PeopleState, viewModel: PeopleViewModel) {
    val selecting = state.selectedPeople.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(100.dp),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.people, key = { it.id }) { person ->
            Column(
                Modifier
                    .combinedClickable(
                        onClick = { if (selecting) viewModel.togglePerson(person.id) else viewModel.openPerson(person) },
                        onLongClick = { viewModel.togglePerson(person.id) },
                    )
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    FaceImage(person.coverFaceId, Modifier.size(88.dp).clip(CircleShape))
                    if (person.id in state.selectedPeople) {
                        Box(
                            Modifier
                                .size(88.dp)
                                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        )
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(Color.White, CircleShape),
                        )
                    }
                }
                Text(
                    person.name ?: "Unnamed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (person.name == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${person.faceCount} photos", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PersonScreen(state: PeopleState, view: PersonView, viewModel: PeopleViewModel) {
    var renaming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<PickPurpose?>(null) }
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<IndexedPhoto?>(null) }
    val gridState = rememberLazyGridState()
    val selecting = view.selected.isNotEmpty()

    Scaffold(
        topBar = {
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(4.dp)
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    IconButton(onClick = { viewModel.setFaceSelection(emptySet()) }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                    }
                    Text(
                        "${view.selected.size}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::selectAllFaces) { Text("All") }
                    TextButton(onClick = { picking = PickPurpose.Move }) { Text("Move") }
                    TextButton(onClick = viewModel::removeSelectedFaces) { Text("Not them") }
                } else {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { renaming = true },
                    ) {
                        Text(
                            view.person.name ?: "Add a name",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (view.person.name == null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                        Text("${view.faces.size} photos", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Same person as…") },
                                onClick = {
                                    menu = false
                                    picking = PickPurpose.Merge
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        val keys = view.faces.map { it.id.toString() }
        val byKey = view.faces.associateBy { it.id.toString() }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(96.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp)
                .dragToSelect(
                    gridState,
                    keys,
                    view.selected,
                    onTap = { key ->
                        if (selecting) viewModel.toggleFace(key) else preview = byKey.getValue(key).photo
                    },
                    onSelectionChange = viewModel::setFaceSelection,
                ),
        ) {
            // Same keys as dragToSelect's, which finds the cell under the finger by key.
            items(view.faces, key = { it.id.toString() }) { face ->
                FaceTile(face, selected = face.id.toString() in view.selected, selecting = selecting)
            }
        }
    }

    if (renaming) {
        RenameDialog(view.person.name.orEmpty(), onDismiss = { renaming = false }) {
            viewModel.rename(it)
            renaming = false
        }
    }
    picking?.let { purpose ->
        PersonPicker(
            title = if (purpose == PickPurpose.Merge) "Same person as" else "Move ${view.selected.size} to",
            people = state.people.filter { it.id != view.person.id },
            onDismiss = { picking = null },
        ) { other ->
            picking = null
            if (purpose == PickPurpose.Merge) viewModel.mergeInto(other) else viewModel.moveSelectedFaces(other)
        }
    }
    preview?.let { photo -> PhotoPreview(photo) { preview = null } }
}

private enum class PickPurpose { Merge, Move }

@Composable
private fun FaceTile(face: PersonFace, selected: Boolean, selecting: Boolean) {
    Box {
        FaceImage(
            face.id,
            Modifier
                .aspectRatio(1f)
                .then(if (selected) Modifier.padding(8.dp) else Modifier),
        )
        Text(
            formatShortDate(face.photo.dateTaken),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            maxLines = 1,
        )
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.3f), CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
            ) {
                if (selected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun FaceImage(faceId: Long, modifier: Modifier) {
    val context = LocalContext.current
    val file = remember(faceId) { context.photoFind.faces.thumbnail(faceId) }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("e.g. Anna") })
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PersonPicker(title: String, people: List<Person>, onDismiss: () -> Unit, onPick: (Person) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(people, key = { it.id }) { person ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(person) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FaceImage(person.coverFaceId, Modifier.size(44.dp).clip(CircleShape))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(person.name ?: "Unnamed", style = MaterialTheme.typography.bodyLarge)
                            Text("${person.faceCount} photos", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
