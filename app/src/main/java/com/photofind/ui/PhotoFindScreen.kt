package com.photofind.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.photofind.embed.Embedder
import com.photofind.search.Hit

@Composable
fun PhotoFindScreen(
    viewModel: MainViewModel,
    onGrantStorage: () -> Unit,
    onOpenPhoto: (Uri) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                placeholder = { Text("Describe the photo… / Opisz zdjęcie…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    viewModel.search()
                }),
            )

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
                else -> IndexStatus(state, onReindex = viewModel::reindex)
            }

            state.similarTo?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = it.photo.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Photos similar to this one", Modifier.weight(1f))
                    IconButton(onClick = viewModel::clearSimilar) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.searching) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                state.searchMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            ResultGrid(
                results = state.results,
                onOpen = { onOpenPhoto(it.photo.uri) },
                onSimilar = viewModel::findSimilar,
            )
        }
    }
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
private fun IndexStatus(state: UiState, onReindex: () -> Unit) {
    val progress = state.indexing
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val text = when {
                progress == null -> "${state.indexedCount} photos indexed"
                progress.total == 0 -> "Scanning folders… (${state.indexedCount} indexed)"
                else -> "Indexing ${progress.done} / ${progress.total} · ${state.indexedCount} searchable"
            }
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (progress == null) TextButton(onClick = onReindex) { Text("Re-scan") }
        }
        if (progress != null) {
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.indexMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultGrid(results: List<Hit>, onOpen: (Hit) -> Unit, onSimilar: (Hit) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results, key = { it.photo.key }) { hit ->
            AsyncImage(
                model = hit.photo.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .combinedClickable(
                        onClick = { onOpen(hit) },
                        onLongClick = { onSimilar(hit) },
                    ),
            )
        }
    }
}
