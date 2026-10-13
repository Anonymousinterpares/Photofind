package com.photofind.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MIN_ITEMS = 40

/**
 * Draggable thumb on the right edge of a grid. While scrolling or dragging it shows a bubble
 * with [label] for the item at the top of the screen (e.g. "Październik 2026").
 */
@Composable
fun BoxScope.FastScroller(state: LazyGridState, itemCount: Int, label: (Int) -> String) {
    if (itemCount < MIN_ITEMS) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbHeight = 52.dp
    val thumbPx = with(density) { thumbHeight.toPx() }
    val bubbleGap = with(density) { 36.dp.roundToPx() }

    var trackHeight by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val scrollFraction by remember(itemCount) {
        derivedStateOf {
            val onScreen = state.layoutInfo.visibleItemsInfo.size
            val lastFirst = (itemCount - onScreen).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / lastFirst).coerceIn(0f, 1f)
        }
    }
    val active = state.isScrollInProgress || dragFraction != null
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            visible = true
        } else {
            delay(1500)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "fastScroller")

    fun jumpTo(y: Float) {
        val usable = (trackHeight - thumbPx).coerceAtLeast(1f)
        val fraction = ((y - thumbPx / 2) / usable).coerceIn(0f, 1f)
        dragFraction = fraction
        scope.launch { state.scrollToItem((fraction * (itemCount - 1)).roundToInt()) }
    }

    val fraction = dragFraction ?: scrollFraction
    val thumbY = (fraction * (trackHeight - thumbPx).coerceAtLeast(0f)).roundToInt()

    Box(
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .width(22.dp)
            .onSizeChanged { trackHeight = it.height }
            .pointerInput(itemCount) {
                detectTapGestures(
                    onPress = { offset ->
                        jumpTo(offset.y)
                        tryAwaitRelease()
                        dragFraction = null
                    },
                )
            }
            .pointerInput(itemCount) {
                detectVerticalDragGestures(
                    onDragStart = { jumpTo(it.y) },
                    onDragEnd = { dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    jumpTo(change.position.y)
                }
            }
            .graphicsLayer { this.alpha = alpha },
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY) }
                .padding(end = 2.dp)
                .size(width = 6.dp, height = thumbHeight)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }

    if (alpha > 0f) {
        val index = dragFraction?.let { (it * (itemCount - 1)).roundToInt() } ?: state.firstVisibleItemIndex
        Text(
            label(index.coerceIn(0, itemCount - 1)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-bubbleGap, thumbY + (thumbPx / 4).roundToInt()) }
                .graphicsLayer { this.alpha = alpha }
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
