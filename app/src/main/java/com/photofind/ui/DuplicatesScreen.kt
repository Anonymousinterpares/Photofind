package com.photofind.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.photofind.search.IndexedPhoto
import com.photofind.search.PhotoGroup
import java.io.File
import java.text.NumberFormat

/**
 * Groups of identical copies or similar shots. Tapping a photo selects it, a long press shows it
 * full screen; "Keep best" selects everything but the suggested photo, ready to delete.
 */
@Composable
fun DuplicatesScreen(state: UiState, view: DuplicatesView, viewModel: MainViewModel, snackbar: SnackbarHostState) {
    var preview by remember { mutableStateOf<IndexedPhoto?>(null) }
    BackHandler { viewModel.duplicatesBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                TopRow(state, view, viewModel)
                Row(
                    Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DuplicateMode.entries.forEach { mode ->
                        FilterChip(
                            selected = view.mode == mode,
                            onClick = { viewModel.setDuplicateMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                if (view.mode == DuplicateMode.Similar) {
                    Row(
                        Modifier.padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Likeness.entries.forEach { likeness ->
                            FilterChip(
                                selected = view.likeness == likeness,
                                onClick = { viewModel.setLikeness(likeness) },
                                label = { Text(likeness.label) },
                            )
                        }
                    }
                }
                Summary(view)
            }
        },
    ) { padding ->
        val groups = view.groups
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                groups == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                groups.isEmpty() -> Text(
                    "Nothing found",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(groups, key = { it.key }) { group ->
                        GroupCard(
                            group = group,
                            mode = view.mode,
                            selectedKeys = state.selectedKeys,
                            onToggle = viewModel::toggleSelected,
                            onPreview = { preview = it },
                            onKeepBest = { viewModel.selectExtras(group) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    preview?.let { photo ->
        PhotoPreview(
            photo,
            onDelete = {
                preview = null
                viewModel.deletePhotos(listOf(photo))
            },
        ) { preview = null }
    }
}

@Composable
private fun TopRow(state: UiState, view: DuplicatesView, viewModel: MainViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.selecting) {
            IconButton(onClick = viewModel::clearSelected) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
            Text(
                "${state.selectedKeys.size} selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::requestDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
            }
        } else {
            IconButton(onClick = viewModel::closeDuplicates) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Duplicates",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { viewModel.selectExtras() },
                enabled = !view.groups.isNullOrEmpty(),
            ) { Text("Keep best in all") }
        }
    }
}

@Composable
private fun Summary(view: DuplicatesView) {
    val context = LocalContext.current
    val groups = view.groups
    val text = if (groups == null) {
        if (view.mode == DuplicateMode.Copies) "Looking for identical copies…" else "Comparing photos…"
    } else {
        val numbers = NumberFormat.getIntegerInstance()
        val extras = groups.flatMap { it.extras }
        val bytes = Formatter.formatShortFileSize(context, extras.sumOf { it.size })
        "${numbers.format(groups.size)} groups · ${numbers.format(extras.size)} extra photos · $bytes"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    group: PhotoGroup,
    mode: DuplicateMode,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
    onPreview: (IndexedPhoto) -> Unit,
    onKeepBest: () -> Unit,
) {
    val context = LocalContext.current
    val labels = remember(group) { copyLabels(group.photos) }
    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatDay(localDate(group.keep.dateTaken)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append("${group.photos.size} photos")
                        if (mode == DuplicateMode.Similar) append(" · ≥ %.0f%% alike".format(group.similarity * 100))
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onKeepBest) { Text("Keep best") }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            group.photos.forEach { photo ->
                Column(
                    Modifier
                        .width(108.dp)
                        .combinedClickable(
                            onClick = { onToggle(photo.key) },
                            onLongClick = { onPreview(photo) },
                        ),
                ) {
                    Box {
                        Thumbnail(photo, selected = photo.key in selectedKeys, selecting = true)
                        if (photo.key == group.keep.key) {
                            Text(
                                "Best",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(
                        if (mode == DuplicateMode.Copies) labels.getValue(photo.key)
                        else Formatter.formatShortFileSize(context, photo.size),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * What tells copies apart: the file name when they share a folder, otherwise the part of the
 * folder path that differs, e.g. "Android/media/com.whatsapp/…" against "WhatsApp/Media/WhatsApp Images".
 */
private fun copyLabels(photos: List<IndexedPhoto>): Map<String, String> {
    val dirs = photos.associate { photo ->
        photo.key to File(photo.key).parent.orEmpty().removePrefix(STORAGE_ROOT).split('/').filter { it.isNotEmpty() }
    }
    if (dirs.values.distinct().size == 1) return photos.associate { it.key to it.fileName }
    var common = 0
    while (dirs.values.all { it.size > common } && dirs.values.map { it[it.size - 1 - common] }.distinct().size == 1) {
        common++
    }
    return dirs.mapValues { (_, parts) ->
        val distinct = parts.dropLast(common)
        if (distinct.isEmpty()) parts.joinToString("/").ifEmpty { "/" } else distinct.joinToString("/") + "/…"
    }
}

private const val STORAGE_ROOT = "/storage/emulated/0"

/** A photo full screen, zoomable, with its path; used where the main detail view's actions don't fit. */
@Composable
internal fun PhotoPreview(photo: IndexedPhoto, onDelete: (() -> Unit)? = null, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    photo.key,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                    }
                }
            }
            ZoomableImage(
                photo = photo,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            Text(
                "${formatDateTime(photo.dateTaken)} · ${Formatter.formatShortFileSize(LocalContext.current, photo.size)}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
