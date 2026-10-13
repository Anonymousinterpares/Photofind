package com.photofind.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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

@Composable
fun PhotoFindScreen(
    viewModel: MainViewModel,
    onGrantStorage: () -> Unit,
    onOpenPhoto: (Uri) -> Unit,
    onSharePhoto: (Uri) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.activeSearch != null && state.selection == null) { viewModel.goHome() }

    Scaffold(
        topBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SearchField(state, viewModel)
                FilterRow(state, viewModel)
                StatusLine(state, viewModel)
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
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
                state.activeSearch == null -> LibraryGrid(state.libraryInRange) { viewModel.select(it, null) }
                else -> ResultsGrid(
                    results = state.sortedResults,
                    onOpen = { viewModel.select(it.photo, it.score) },
                    onSimilar = { viewModel.findSimilar(it.photo) },
                )
            }
        }
    }

    state.selection?.let { selection ->
        PhotoDetail(
            selection = selection,
            onClose = viewModel::closeSelection,
            onSimilar = { viewModel.findSimilar(selection.photo) },
            onOpen = { onOpenPhoto(selection.photo.uri) },
            onShare = { onSharePhoto(selection.photo.uri) },
        )
    }
}

@Composable
private fun SearchField(state: UiState, viewModel: MainViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val inSearch = state.activeSearch != null
    OutlinedTextField(
        value = state.query,
        onValueChange = viewModel::onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Describe the photo… / Opisz zdjęcie…") },
        singleLine = true,
        leadingIcon = {
            if (inSearch) {
                IconButton(onClick = viewModel::goHome) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All photos")
                }
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
            }
        },
        trailingIcon = {
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { viewModel.onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(state: UiState, viewModel: MainViewModel) {
    var sortMenu by remember { mutableStateOf(false) }
    var pickingDates by rememberSaveable { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val range = state.dateRange
        FilterChip(
            selected = range != null,
            onClick = { pickingDates = true },
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
private fun StatusLine(state: UiState, viewModel: MainViewModel) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGrid(photos: List<IndexedPhoto>, onOpen: (IndexedPhoto) -> Unit) {
    val days: List<Pair<LocalDate, List<IndexedPhoto>>> = remember(photos) {
        photos.groupBy { localDate(it.dateTaken) }.toList()
    }
    val gridState = rememberLazyGridState()
    // A reload keeps the grid anchored to the item on screen; stay on the newest day instead.
    LaunchedEffect(photos.firstOrNull()?.key) {
        if (gridState.firstVisibleItemIndex < 3) gridState.scrollToItem(0)
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(96.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        days.forEach { (day, dayPhotos) ->
            item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    formatDay(day),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(dayPhotos, key = { it.key }) { photo ->
                Thumbnail(photo, modifier = Modifier.combinedClickable(onClick = { onOpen(photo) }))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultsGrid(results: List<Hit>, onOpen: (Hit) -> Unit, onSimilar: (Hit) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results, key = { it.photo.key }) { hit ->
            Box(
                Modifier.combinedClickable(
                    onClick = { onOpen(hit) },
                    onLongClick = { onSimilar(hit) },
                ),
            ) {
                Thumbnail(hit.photo)
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
}

@Composable
private fun Thumbnail(photo: IndexedPhoto, modifier: Modifier = Modifier) {
    AsyncImage(
        model = photo.uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
