package com.photofind.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Tap, long-press and drag-to-select for a photo grid, handled on the grid itself so one gesture
 * can sweep across many cells.
 *
 * A tap calls [onTap]. A long press toggles the photo under the finger; dragging without lifting
 * then extends that change to every photo between the first one and the one under the finger,
 * scrolling the grid when the finger nears the top or bottom edge. Starting on a selected photo
 * deselects instead. [photoKeys] are the grid's photo keys in display order; other grid items
 * (day headers) are skipped.
 */
@Composable
fun Modifier.dragToSelect(
    gridState: LazyGridState,
    photoKeys: List<String>,
    selectedKeys: Set<String>,
    onTap: (String) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edgeZone = with(density) { 72.dp.toPx() }
    val maxSpeed = with(density) { 20.dp.toPx() }

    val keys by rememberUpdatedState(photoKeys)
    val indexOf = remember(photoKeys) { photoKeys.withIndex().associate { (i, key) -> key to i } }
    val currentIndexOf by rememberUpdatedState(indexOf)
    val selected by rememberUpdatedState(selectedKeys)
    val tap by rememberUpdatedState(onTap)
    val change by rememberUpdatedState(onSelectionChange)

    val drag = remember { DragState() }
    var autoScroll by remember { mutableFloatStateOf(0f) }

    fun photoAt(position: Offset): Int? {
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            position.x >= it.offset.x && position.x < it.offset.x + it.size.width &&
                position.y >= it.offset.y && position.y < it.offset.y + it.size.height
        } ?: return null
        return currentIndexOf[item.key]
    }

    fun extendTo(position: Offset) {
        drag.position = position
        val index = photoAt(position) ?: return
        if (index == drag.last) return
        drag.last = index
        val range = keys.subList(minOf(drag.anchor, index), maxOf(drag.anchor, index) + 1)
        change(if (drag.adding) drag.base + range else drag.base - range.toSet())
    }

    LaunchedEffect(autoScroll != 0f) {
        while (autoScroll != 0f) {
            gridState.scrollBy(autoScroll)
            extendTo(drag.position)
            withFrameNanos { }
        }
    }

    return pointerInput(gridState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var cancelled = false
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation().also { cancelled = it == null }
            }
            if (cancelled) return@awaitEachGesture
            if (up != null) {
                photoAt(up.position)?.let { tap(keys[it]) }
                up.consume()
                return@awaitEachGesture
            }

            // Long press: the grid's own scrolling sees every move consumed from here on.
            val anchor = photoAt(down.position) ?: return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            drag.anchor = anchor
            drag.last = -1
            drag.base = selected
            drag.adding = keys[anchor] !in selected
            extendTo(down.position)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    pointer.consume()
                    if (!pointer.pressed) break
                    extendTo(pointer.position)
                    val y = pointer.position.y
                    autoScroll = when {
                        y < edgeZone -> -maxSpeed * ((edgeZone - y) / edgeZone).coerceAtMost(1f)
                        y > size.height - edgeZone ->
                            maxSpeed * ((y - size.height + edgeZone) / edgeZone).coerceAtMost(1f)
                        else -> 0f
                    }
                }
            } finally {
                autoScroll = 0f
            }
        }
    }
}

private class DragState {
    var anchor = 0
    var last = -1
    var base: Set<String> = emptySet()
    var adding = true
    var position = Offset.Zero
}
