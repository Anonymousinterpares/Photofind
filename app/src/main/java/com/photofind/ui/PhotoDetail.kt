package com.photofind.ui

import android.net.Uri
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.photofind.search.IndexedPhoto
import java.text.NumberFormat
import kotlin.math.abs

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** One full-screen page, in the same order as the thumbnail grid. [score] is set for a search hit. */
data class DetailPage(val photo: IndexedPhoto, val score: Float?)

@Composable
fun PhotoDetail(
    selection: Selection,
    pages: List<DetailPage>,
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
    onBrowse: (IndexedPhoto, Float?) -> Unit,
    onSimilar: (IndexedPhoto) -> Unit,
    onOpen: (Uri) -> Unit,
    onShare: (Uri) -> Unit,
    onDelete: (IndexedPhoto) -> Unit,
) {
    // The open photo can briefly be missing from the grid (a reload); still show that one picture.
    val safePages = if (pages.any { it.photo.key == selection.photo.key }) {
        pages
    } else {
        listOf(DetailPage(selection.photo, selection.score))
    }
    val initialPage = safePages.indexOfFirst { it.photo.key == selection.photo.key }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { safePages.size }
    val latestPages by rememberUpdatedState(safePages)
    val browse by rememberUpdatedState(onBrowse)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            latestPages.getOrNull(page)?.let { browse(it.photo, it.score) }
        }
    }
    var zoomed by remember { mutableStateOf(false) }
    val settledKey = safePages.getOrNull(pagerState.settledPage)?.photo?.key
    // A newly settled photo starts zoomed out. Don't clear this when the same photo only shifts index.
    LaunchedEffect(settledKey) { zoomed = false }
    val numbers = remember { NumberFormat.getIntegerInstance() }

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
                        zoomed -> "Pinch or double-tap to zoom out"
                        safePages.size > 1 -> "Swipe for the next photo · pinch or double-tap to zoom"
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
                IconButton(onClick = {
                    safePages.getOrNull(pagerState.settledPage)?.let { onDelete(it.photo) }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
                userScrollEnabled = tagging == null && !zoomed,
                key = { index -> safePages.getOrNull(index)?.photo?.key ?: "missing-$index" },
            ) { page ->
                val item = safePages.getOrNull(page) ?: return@HorizontalPager
                val current = page == pagerState.settledPage && item.photo.key == selection.photo.key
                Column(Modifier.fillMaxSize()) {
                    ZoomableImage(
                        photo = item.photo,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        gestures = tagging == null,
                        onZoomedChange = { zoomedNow ->
                            if (page == pagerState.settledPage) zoomed = zoomedNow
                        },
                        overlay = when {
                            !current -> null
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
                        if (safePages.size > 1) {
                            Text(
                                "${numbers.format(page + 1)} / ${numbers.format(safePages.size)}",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Text(
                            formatDateTime(item.photo.dateTaken),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${item.photo.folder} · ${item.photo.fileName}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Names arrive a moment after the photo. The line is always here, so the
                        // picture does not shift up when they appear, or down when a photo has none.
                        Text(
                            if (current && faces.isNotEmpty()) peopleLine(faces) else " ",
                            color = Color.White.copy(alpha = if (current && faces.isNotEmpty()) 0.85f else 0f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.score?.let {
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
                            FilledTonalButton(onClick = { onSimilar(item.photo) }) { Text("Similar") }
                            OutlinedButton(onClick = { onOpen(item.photo.uri) }) { Text("Open") }
                            OutlinedButton(onClick = { onShare(item.photo.uri) }) { Text("Share") }
                        }
                    }
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
    photo: IndexedPhoto,
    modifier: Modifier,
    overlay: (@Composable () -> Unit)? = null,
    /** Pinch and double-tap zoom; off while the overlay takes the gestures (tagging). */
    gestures: Boolean = true,
    /** True once the photo is enlarged, so a parent pager can leave one-finger drags to panning. */
    onZoomedChange: (Boolean) -> Unit = {},
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

    fun changeScale(newScale: Float, newOffset: Offset) {
        val wasZoomed = scale > 1f
        scale = newScale
        offset = newOffset
        if (wasZoomed != (newScale > 1f)) onZoomedChange(newScale > 1f)
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
                            changeScale(1f, Offset.Zero)
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            changeScale(DOUBLE_TAP_ZOOM, clamp((center - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM))
                        }
                    },
                )
            }
            .pointerInput(photo.key, gestures) {
                if (!gestures) return@pointerInput
                detectZoomPan(zoomed = { scale > 1f }) { centroid, pan, zoom ->
                    val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    // Keep the point under the fingers in place while zooming.
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val focus = centroid - center
                    val zoomedOffset = (offset - focus) * (newScale / scale) + focus + pan
                    changeScale(newScale, if (newScale == 1f) Offset.Zero else clamp(zoomedOffset, newScale))
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

/**
 * Pinch to zoom, and pan once the photo is enlarged. A one-finger drag while zoomed out is not
 * consumed, so the parent pager can turn the page. If the pager takes that drag, this gesture ends.
 */
private suspend fun PointerInputScope.detectZoomPan(
    zoomed: () -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        var zoomTotal = 1f
        var panTotal = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val pointers = event.changes.count { it.pressed }
                if (!pastTouchSlop) {
                    zoomTotal *= zoomChange
                    panTotal += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1f - zoomTotal) * centroidSize
                    // Two fingers means a pinch: take it before the pager treats the movement as a swipe.
                    if (pointers >= 2 || zoomMotion > touchSlop || (panTotal.getDistance() > touchSlop && zoomed())) {
                        pastTouchSlop = true
                    }
                }
                if (pastTouchSlop) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        onGesture(centroid, panChange, zoomChange)
                    }
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
            val parentTookTheDrag =
                awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed } && !pastTouchSlop
            if (canceled || parentTookTheDrag || event.changes.none { it.pressed }) break
        }
    }
}
