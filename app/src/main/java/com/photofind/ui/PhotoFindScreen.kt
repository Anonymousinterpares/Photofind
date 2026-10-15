package com.photofind.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.photofind.embed.Embedder
import com.photofind.search.Hit
import com.photofind.search.IndexedPhoto
import java.text.NumberFormat
import java.time.LocalDate

private sealed interface GridEntry {
    val key: String
    val dateTaken: Long

    data class Day(val date: LocalDate, val photos: List<IndexedPhoto>) : GridEntry {
        override val key: String get() = "day-$date"
        override val dateTaken: Long get() = photos.first().dateTaken
    }

    data class Photo(val photo: IndexedPhoto) : GridEntry {
        override val key: String get() = photo.key
        override val dateTaken: Long get() = photo.dateTaken
    }
}

@Composable
fun PhotoFindScreen(
    viewModel: MainViewModel,
    peopleViewModel: PeopleViewModel,
    onGrantStorage: () -> Unit,
    onOpenPhoto: (Uri) -> Unit,
    onSharePhoto: (Uri) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Hoisted so each grid keeps its position while the other one is on screen.
    val libraryGrid = rememberLazyGridState()
    val resultsGrid = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = state.selection == null && (state.canGoBack || state.selecting)) { viewModel.back() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    val people by peopleViewModel.state.collectAsStateWithLifecycle()
    // Names may have changed while People was open.
    LaunchedEffect(people.open) { if (!people.open) viewModel.loadFaces() }
    if (people.open) {
        PeopleScreen(people, peopleViewModel)
        return
    }

    state.duplicates?.let { view ->
        DuplicatesScreen(state, view, viewModel, snackbar)
        state.deleteConfirm?.let { plan -> DeleteConfirmDialog(plan, viewModel) }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.selecting) SelectionBar(state, viewModel) else SearchField(state, viewModel)
                FilterRow(state, viewModel)
                StatusLine(state, viewModel, onPeople = peopleViewModel::open)
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(start = 12.dp, end = 4.dp),
        ) {
            when {
                !state.hasStorageAccess -> SetupCard(
                    title = "Allow access to all files",
                    body = "PhotoFind reads photos in every folder on the phone, including WhatsApp " +
                        "and other apps' media. Nothing leaves the device.",
                    action = "Open settings",
                    onAction = onGrantStorage,
                )
                state.modelPath == null -> SetupCard(
                    title = "Embedding model missing",
                    body = "Copy ${Embedder.MODEL_NAME} to one of:\n" +
                        state.modelLocations.joinToString("\n"),
                    action = "Check again",
                    onAction = viewModel::refreshEnvironment,
                )
                state.activeSearch == null -> LibraryGrid(
                    photos = state.libraryInRange,
                    gridState = libraryGrid,
                    selectedKeys = state.selectedKeys,
                    onTap = { photo ->
                        if (state.selecting) viewModel.toggleSelected(photo.key) else viewModel.open(photo, null)
                    },
                    onSelectionChange = viewModel::setSelected,
                )
                else -> ResultsGrid(
                    results = state.sortedResults,
                    gridState = resultsGrid,
                    scrollTo = state.resultsScroll,
                    scrollRequest = state.scrollRequest,
                    onScrolled = viewModel::onResultsScrolled,
                    selectedKeys = state.selectedKeys,
                    onTap = { hit ->
                        if (state.selecting) viewModel.toggleSelected(hit.photo.key)
                        else viewModel.open(hit.photo, hit.score)
                    },
                    onSelectionChange = viewModel::setSelected,
                )
            }
        }
    }

    state.selection?.let { selection ->
        PhotoDetail(
            selection = selection,
            faces = selection.faces,
            showTags = state.showFaceTags,
            onToggleTags = viewModel::toggleFaceTags,
            onPerson = peopleViewModel::openPerson,
            onClose = viewModel::back,
            onSimilar = { viewModel.findSimilar(selection.photo) },
            onOpen = { onOpenPhoto(selection.photo.uri) },
            onShare = { onSharePhoto(selection.photo.uri) },
            onDelete = { viewModel.deletePhoto(selection.photo) },
        )
    }

    state.deleteConfirm?.let { plan -> DeleteConfirmDialog(plan, viewModel) }
}

@Composable
private fun SearchField(state: UiState, viewModel: MainViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = state.query,
        onValueChange = viewModel::onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Describe a photo or name people…") },
        singleLine = true,
        leadingIcon = {
            if (state.canGoBack) {
                IconButton(onClick = viewModel::back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
            }
        },
        trailingIcon = {
            Row {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
                if (state.activeSearch != null) {
                    IconButton(onClick = viewModel::goHome) {
                        Icon(Icons.Default.Home, contentDescription = "All photos")
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            viewModel.search()
        }),
    )
}

@Composable
private fun SelectionBar(state: UiState, viewModel: MainViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = viewModel::clearSelected) {
            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
        }
        Text(
            "${state.selectedKeys.size} selected",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = viewModel::selectAll) { Text("Select all") }
        IconButton(onClick = viewModel::requestDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(state: UiState, viewModel: MainViewModel) {
    var sortMenu by remember { mutableStateOf(false) }
    var periodMenu by remember { mutableStateOf(false) }
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    val years = remember(state.library) {
        state.library.map { localDate(it.dateTaken).year }.distinct().sortedDescending()
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val range = state.dateRange
        Box {
            FilterChip(
                selected = range != null,
                onClick = { periodMenu = true },
                label = { Text(range?.let(::formatRange) ?: "Any date") },
                leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, Modifier.size(18.dp)) },
                trailingIcon = range?.let {
                    {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear dates",
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { viewModel.setDateRange(null) },
                        )
                    }
                },
            )
            DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Any date") },
                    onClick = {
                        viewModel.setDateRange(null)
                        periodMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Custom range…") },
                    onClick = {
                        periodMenu = false
                        pickingDates = true
                    },
                )
                HorizontalDivider()
                quickPeriods(years).forEach { period ->
                    DropdownMenuItem(
                        text = { Text(period.label) },
                        onClick = {
                            viewModel.setDateRange(period.range)
                            periodMenu = false
                        },
                    )
                }
            }
        }
        if (state.activeSearch != null) {
            Box {
                FilterChip(
                    selected = state.sort != SortOrder.Relevance,
                    onClick = { sortMenu = true },
                    label = { Text(state.sort.label) },
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.label) },
                            onClick = {
                                viewModel.setSort(order)
                                sortMenu = false
                            },
                        )
                    }
                }
            }
        }
    }

    if (pickingDates) {
        val initial = state.dateRange?.let(::rangeToPicker)
        val picker = rememberDateRangePickerState(
            initialSelectedStartDateMillis = initial?.first,
            initialSelectedEndDateMillis = initial?.second,
        )
        DatePickerDialog(
            onDismissRequest = { pickingDates = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedStartDateMillis != null,
                    onClick = {
                        val start = checkNotNull(picker.selectedStartDateMillis)
                        viewModel.setDateRange(pickerToRange(start, picker.selectedEndDateMillis ?: start))
                        pickingDates = false
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { pickingDates = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = picker, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusLine(state: UiState, viewModel: MainViewModel, onPeople: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val progress = state.indexing
    val count = NumberFormat.getIntegerInstance().format(state.indexedCount)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.searching) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            val text = when {
                state.activeSearch is ActiveSearch.Similar -> state.searchMessage ?: "Finding similar photos…"
                state.activeSearch != null -> state.searchMessage ?: "Searching…"
                progress == null -> libraryOverview(state, count)
                progress.total == 0 -> "$count photos · checking for new photos…"
                else -> "$count photos · indexing new ${progress.done} / ${progress.total}"
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Check for new photos") },
                        enabled = progress == null,
                        onClick = {
                            viewModel.reindex()
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("People") },
                        enabled = state.indexedCount > 0,
                        onClick = {
                            onPeople()
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicates & similar") },
                        enabled = state.indexedCount > 0,
                        onClick = {
                            viewModel.openDuplicates()
                            menu = false
                        },
                    )
                }
            }
        }
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (progress == null && state.activeSearch == null) {
            state.indexMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun libraryOverview(state: UiState, count: String): String {
    val photos = state.libraryInRange
    if (photos.isEmpty()) return if (state.dateRange != null) "No photos in these dates" else "$count photos"
    val newest = localDate(photos.first().dateTaken).year
    val oldest = localDate(photos.last().dateTaken).year
    val years = if (newest == oldest) "$newest" else "$oldest–$newest"
    val shown = NumberFormat.getIntegerInstance().format(photos.size)
    return if (state.dateRange != null) "$shown of $count photos" else "$count photos · $years"
}

@Composable
private fun SetupCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(plan: DeletePlan, viewModel: MainViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelDelete,
        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
        title = { Text("Delete ${plan.size} photos?") },
        text = {
            val hidden = plan.permanent.size
            Text(
                buildString {
                    append("$hidden ")
                    append(if (hidden == 1) "photo is" else "photos are")
                    append(" in a hidden folder (for example WhatsApp “Sent”), so Android can't move ")
                    append(if (hidden == 1) "it" else "them")
                    append(" to the trash. ")
                    append(if (hidden == 1) "It" else "They")
                    append(" will be deleted permanently.")
                    if (plan.trash.isNotEmpty()) {
                        append("\n\nThe other ${plan.trash.size} will go to the trash; Android asks to confirm next.")
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = viewModel::confirmDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") } },
    )
}

@Composable
private fun LibraryGrid(
    photos: List<IndexedPhoto>,
    gridState: LazyGridState,
    selectedKeys: Set<String>,
    onTap: (IndexedPhoto) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
) {
    val entries: List<GridEntry> = remember(photos) {
        photos.groupBy { localDate(it.dateTaken) }.flatMap { (day, dayPhotos) ->
            listOf(GridEntry.Day(day, dayPhotos)) + dayPhotos.map { GridEntry.Photo(it) }
        }
    }
    val photoKeys = remember(entries) { entries.filterIsInstance<GridEntry.Photo>().map { it.key } }
    val byKey = remember(photos) { photos.associateBy { it.key } }
    // A reload keeps the grid anchored to the item on screen; stay on the newest day instead.
    LaunchedEffect(photos.firstOrNull()?.key) {
        if (gridState.firstVisibleItemIndex < 3) gridState.scrollToItem(0)
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(96.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 18.dp)
                .dragToSelect(gridState, photoKeys, selectedKeys, { onTap(byKey.getValue(it)) }, onSelectionChange),
        ) {
            items(
                entries,
                key = { it.key },
                span = { if (it is GridEntry.Day) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            ) { entry ->
                when (entry) {
                    is GridEntry.Day -> Text(
                        formatDay(entry.date),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    is GridEntry.Photo ->
                        Thumbnail(entry.photo, selected = entry.photo.key in selectedKeys, selecting = selectedKeys.isNotEmpty())
                }
            }
        }
        FastScroller(gridState, entries.size) { formatMonthYear(entries[it].dateTaken) }
    }
}

@Composable
private fun ResultsGrid(
    results: List<Hit>,
    gridState: LazyGridState,
    scrollTo: Int,
    scrollRequest: Int,
    onScrolled: (Int) -> Unit,
    selectedKeys: Set<String>,
    onTap: (Hit) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
) {
    val photoKeys = remember(results) { results.map { it.photo.key } }
    val byKey = remember(results) { results.associateBy { it.photo.key } }
    LaunchedEffect(scrollRequest) { gridState.scrollToItem(scrollTo) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect(onScrolled)
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(110.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 18.dp)
                .dragToSelect(gridState, photoKeys, selectedKeys, { onTap(byKey.getValue(it)) }, onSelectionChange),
        ) {
            items(results, key = { it.photo.key }) { hit ->
                Box {
                    Thumbnail(hit.photo, selected = hit.photo.key in selectedKeys, selecting = selectedKeys.isNotEmpty())
                    Text(
                        formatShortDate(hit.photo.dateTaken),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        }
        FastScroller(gridState, results.size) { formatMonthYear(results[it].photo.dateTaken) }
    }
}

@Composable
internal fun Thumbnail(photo: IndexedPhoto, selected: Boolean, selecting: Boolean) {
    Box {
        AsyncImage(
            model = photo.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (selected) Modifier.padding(8.dp) else Modifier),
        )
        if (selecting) {
            val tint = if (selected) MaterialTheme.colorScheme.primary else Color.White
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .background(if (selected) Color.White else Color.Black.copy(alpha = 0.3f), CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
            ) {
                if (selected) Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = tint)
            }
        }
    }
}
