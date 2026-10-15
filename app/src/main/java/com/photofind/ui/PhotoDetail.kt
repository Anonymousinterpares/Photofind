package com.photofind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.TextButton
import android.graphics.RectF
import com.photofind.data.Person
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.photofind.data.PhotoFace
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
fun PhotoDetail(
    selection: Selection,
    faces: List<PhotoFace>,
    showTags: Boolean,
    onToggleTags: () -> Unit,
    onPerson: (Long?) -> Unit,
    tagging: Tagging?,
    onStartTagging: () -> Unit,
    onTagBox: (RectF) -> Unit,
    onTagFace: (Long) -> Unit,
    onCancelPick: () -> Unit,
    onTagAs: (Person?, String?) -> Unit,
    onClose: () -> Unit,
    onSimilar: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val photo = selection.photo
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
                    when {
                        tagging?.saving == true -> "Saving…"
                        tagging?.message != null -> tagging.message
                        tagging != null -> "Drag over a face to tag it, or tap a frame to change who it is"
                        else -> "Pinch or double-tap to zoom"
                    },
                    color = Color.White.copy(alpha = if (tagging != null) 0.9f else 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                if (tagging != null) {
                    TextButton(onClick = onClose) { Text("Done") }
                    return@Row
                }
                IconButton(onClick = onStartTagging) {
                    Icon(Icons.Default.Add, contentDescription = "Tag a person", tint = Color.White)
                }
                if (faces.isNotEmpty()) {
                    IconButton(onClick = onToggleTags) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = if (showTags) "Hide people" else "Show people",
                            tint = if (showTags) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                }
            }
            ZoomableImage(
                photo = photo,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                gestures = tagging == null,
                overlay = when {
                    tagging != null -> {
                        { TagLayer(faces, tagging.box, onTagBox, onTagFace) }
                    }
                    showTags -> {
                        { FaceTags(faces, onPerson) }
                    }
                    else -> null
                },
            )
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(formatDateTime(photo.dateTaken), color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${photo.folder} · ${photo.fileName}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (faces.isNotEmpty()) {
                    Text(
                        peopleLine(faces),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                selection.score?.let {
                    Text(
                        "Match %.3f".format(it),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = onSimilar) { Text("Similar") }
                    OutlinedButton(onClick = onOpen) { Text("Open") }
                    OutlinedButton(onClick = onShare) { Text("Share") }
                }
            }
        }
    }

    var naming by remember { mutableStateOf(false) }
    if (tagging?.picking == true && !naming) {
        PersonPicker(
            title = "Who is this?",
            people = tagging.people,
            onDismiss = onCancelPick,
            onNewPerson = { naming = true },
        ) { onTagAs(it, null) }
    }
    if (naming) {
        RenameDialog("", onDismiss = { naming = false }) {
            naming = false
            onTagAs(null, it)
        }
    }
}

/**
 * Tag mode over the photo: the faces' frames, tappable to change who they are, and a box drawn
 * by dragging, which [onBox] receives in 0..1 photo coordinates. [pending] is a drawn box
 * waiting for a person.
 */
@Composable
private fun TagLayer(faces: List<PhotoFace>, pending: RectF?, onBox: (RectF) -> Unit, onFace: (Long) -> Unit) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        fun normalised(a: Offset, b: Offset) = RectF(
            (minOf(a.x, b.x) / width).coerceIn(0f, 1f),
            (minOf(a.y, b.y) / height).coerceIn(0f, 1f),
            (maxOf(a.x, b.x) / width).coerceIn(0f, 1f),
            (maxOf(a.y, b.y) / height).coerceIn(0f, 1f),
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(faces) {
                    detectTapGestures { tap ->
                        faces.lastOrNull {
                            tap.x in it.box.left * width..it.box.right * width &&
                                tap.y in it.box.top * height..it.box.bottom * height
                        }?.let { onFace(it.id) }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            start = it
                            end = it
                        },
                        onDrag = { change, _ -> end = change.position },
                        onDragEnd = {
                            val a = start
                            val b = end
                            start = null
                            end = null
                            if (a != null && b != null) {
                                val box = normalised(a, b)
                                // Ignore slips; a face is rarely under 2% of the photo.
                                if (box.width() > 0.02f && box.height() > 0.02f) onBox(box)
                            }
                        },
                        onDragCancel = {
                            start = null
                            end = null
                        },
                    )
                },
        )

        @Composable
        fun frame(box: RectF, color: Color, label: String?) = with(density) {
            Box(
                Modifier
                    .offset { IntOffset((box.left * width).roundToInt(), (box.top * height).roundToInt()) }
                    .size((box.width() * width).toDp(), (box.height() * height).toDp())
                    .border(2.dp, color, RoundedCornerShape(4.dp)),
            )
            if (label != null) {
                Text(
                    label,
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier
                        .offset { IntOffset((box.left * width).roundToInt(), (box.bottom * height).roundToInt() + 2) }
                        .background(color, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        faces.forEach { face -> frame(face.box, tagColor(face), tagLabel(face)) }
        pending?.let { frame(it, Color.White, null) }
        val a = start
        val b = end
        if (a != null && b != null) frame(normalised(a, b), Color.White, null)
    }
}

private fun tagColor(face: PhotoFace): Color = when {
    face.name != null -> Color(0xFF4CAF50)
    face.personId != null -> Color(0xFFFFB300)
    else -> Color(0xFFBDBDBD)
}

private fun tagLabel(face: PhotoFace): String = face.name ?: if (face.personId != null) "Unnamed" else "Unknown"

/** "Anna, Piotr · 1 unnamed · 2 unknown" */
private fun peopleLine(faces: List<PhotoFace>): String {
    val names = faces.mapNotNull { it.name }.distinct()
    val unnamed = faces.count { it.personId != null && it.name == null }
    val unknown = faces.count { it.personId == null }
    return buildList {
        if (names.isNotEmpty()) add(names.joinToString(", "))
        if (unnamed > 0) add("$unnamed unnamed")
        if (unknown > 0) add("$unknown unknown")
    }.joinToString(" · ")
}

/**
 * A frame and label on each face: green with the name when the person is named, amber for a
 * person without a name yet, grey for a face that belongs to nobody. Tapping a label opens
 * that person (or the unsorted faces). Drawn over the photo's own area.
 */
@Composable
private fun FaceTags(faces: List<PhotoFace>, onPerson: (Long?) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        faces.forEach { face ->
            val color = tagColor(face)
            val left = face.box.left * width
            val top = face.box.top * height
            val boxWidth = (face.box.right - face.box.left) * width
            val boxHeight = (face.box.bottom - face.box.top) * height
            with(density) {
                Box(
                    Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(boxWidth.toDp(), boxHeight.toDp())
                        .border(2.dp, color, RoundedCornerShape(4.dp)),
                )
                Text(
                    tagLabel(face),
                    color = Color.Black,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier
                        .offset { IntOffset(left.roundToInt(), (top + boxHeight).roundToInt() + 2) }
                        .widthIn(max = maxOf(boxWidth.toDp(), 72.dp))
                        .background(color, RoundedCornerShape(4.dp))
                        .clickable { onPerson(face.personId) }
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** [overlay] is drawn over the photo's own area and zooms with it. */
@Composable
internal fun ZoomableImage(
    photo: com.photofind.search.IndexedPhoto,
    modifier: Modifier,
    overlay: (@Composable () -> Unit)? = null,
    /** Pinch and double-tap zoom; off while the overlay takes the gestures (tagging). */
    gestures: Boolean = true,
) {
    val context = LocalContext.current
    var scale by remember(photo.key) { mutableFloatStateOf(1f) }
    var offset by remember(photo.key) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(photo.key) { mutableStateOf<IntSize?>(null) }

    fun clamp(candidate: Offset, atScale: Float): Offset {
        val maxX = size.width * (atScale - 1f) / 2f
        val maxY = size.height * (atScale - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(photo.key, gestures) {
                if (!gestures) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = DOUBLE_TAP_ZOOM
                            offset = clamp((center - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                        }
                    },
                )
            }
            .pointerInput(photo.key, gestures) {
                if (!gestures) return@pointerInput
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    // Keep the point under the fingers in place while zooming.
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val focus = centroid - center
                    val zoomed = (offset - focus) * (newScale / scale) + focus + pan
                    scale = newScale
                    offset = if (newScale == 1f) Offset.Zero else clamp(zoomed, newScale)
                }
            },
    ) {
        val zoom = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        AsyncImage(
            model = ImageRequest.Builder(context).data(photo.uri).size(2560).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onSuccess = { imageSize = IntSize(it.result.image.width, it.result.image.height) },
            modifier = Modifier
                .fillMaxSize()
                .then(zoom),
        )
        val image = imageSize
        if (overlay != null && image != null && size.width > 0 && image.width > 0) {
            // Where ContentScale.Fit puts the photo inside this box.
            val fit = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
            val shown = IntSize((image.width * fit).roundToInt(), (image.height * fit).roundToInt())
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().then(zoom)) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(with(density) { shown.width.toDp() }, with(density) { shown.height.toDp() }),
                ) { overlay() }
            }
        }
    }
}
