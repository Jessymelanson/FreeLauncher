package com.freelauncher.app.ui.shells

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.freelauncher.app.launcher
import com.freelauncher.app.ui.common.detectLauncherGestures
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Rect as UiRect

/**
 * Pieces both alternative shells need.
 *
 * Neither shell has an app drawer, which is where the classic shell keeps the
 * way in to a private profile. Both therefore need their own route to it, and
 * the route has to stay out of sight: a private space that advertises itself
 * from the home screen is not private. [ShellClock] is that route. A long press
 * on the clock opens it, and nothing on screen says so.
 */

/** Launch bounds, which the system uses to animate the app opening from its icon. */
fun UiRect.toLaunchBounds(): Rect =
    Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

/**
 * The clock, and the way in to private space.
 *
 * A long press opens the private space sheet when the device has a private
 * profile and does nothing at all when it does not, so on a phone without one
 * the gesture is not merely refused, there is nothing there to refuse. Nothing
 * on screen hints at it, which is the point: the classic shell can afford a
 * labelled button inside the drawer because the drawer is already a deliberate
 * place to go, and a home screen is not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShellClock(
    color: Color,
    onSecret: () -> Unit,
    modifier: Modifier = Modifier,
    // Sized for a header by default. The Windows 11 shell puts it in a footer
    // beside two other controls, where the header size swamped the row.
    timeSize: androidx.compose.ui.unit.TextUnit = 34.sp,
    dateSize: androidx.compose.ui.unit.TextUnit = 12.sp,
) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(20_000)
        }
    }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val day = remember(now) { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now) }

    Column(
        modifier = modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = onSecret,
        ),
        horizontalAlignment = Alignment.End,
    ) {
        Text(time, color = color, fontSize = timeSize, fontWeight = FontWeight.Light)
        Text(day, color = color.copy(alpha = 0.75f), fontSize = dateSize)
    }
}

/** Opens the system settings for this launcher's own private profile. */
fun openPrivateSettings(context: Context) {
    val sender = context.launcher.apps.privateSpaceSettingsIntent() ?: return
    runCatching { (context as? Activity)?.startIntentSender(sender, null, 0, 0, 0) }
}

// ---- drag to reorder -------------------------------------------------------
//
// The same model as the classic workspace, on purpose. The gesture that carries
// a tile lives on the container, above every tile, so it is in the hit path from
// the first touch and survives the tile being re-parented mid-drag - a small tile
// leaving its cube, an app crossing to the next page. Each tile only decides what
// a touch on it means, using the same detector the classic icons use: a tap, a
// long press, or a long press that then moves, which is a drag.

/**
 * Where every tile is and which one is being carried.
 *
 * Targets are found by asking each tile where it is, in window coordinates,
 * rather than reading a lazy layout's own item list. That list gives one box per
 * grid item, which holds only while every item is one tile: the Metro wall packs
 * four small tiles into a single grid item, so reading the layout gave those four
 * no box at all. Tiles reporting themselves costs nothing for nesting, and the
 * spacers simply never register.
 */
class ReorderState internal constructor(
    private val order: () -> List<String>,
    private val onOrder: (List<String>) -> Unit,
    private val onCommit: () -> Unit,
    private val onGrab: () -> Unit,
) {
    /** The item currently being carried, if any. */
    var dragKey by mutableStateOf<String?>(null)
        private set

    /** Where the finger is, in window coordinates. */
    private var pointer by mutableStateOf(Offset.Zero)

    val pointerX: Float get() = pointer.x
    val pointerY: Float get() = pointer.y

    /** The scrolling area's own bounds, for deciding when to pull at the edges. */
    var viewport by mutableStateOf(UiRect.Zero)
        private set

    /**
     * Each tile's last reported bounds, with the instance that reported them.
     *
     * The owner matters when a tile moves between parents: the new instance
     * reports itself and the old one is disposed, in whichever order Compose
     * gets to them. Forgetting only what the departing instance itself put here
     * means a late disposal cannot wipe out the new instance's entry.
     */
    private val boxes = HashMap<String, Pair<Any, UiRect>>()

    /** The tile last swapped with, so one swap cannot immediately undo itself. */
    private var lastOver: String? = null

    internal fun place(key: String, owner: Any, bounds: UiRect) {
        boxes[key] = owner to bounds
    }

    /**
     * A tile that has left the screen - scrolled off, paged away - stops being a
     * target.
     *
     * Without this its last position lingered. A lazy list disposes a tile that
     * scrolls out of view, and whatever it last reported stayed in the table,
     * sitting exactly where some other tile now is. A drag passing over that
     * spot was matched to the absent tile instead, and the carried tile jumped
     * somewhere nobody pointed at, or went nowhere.
     */
    internal fun forget(key: String, owner: Any) {
        if (boxes[key]?.first === owner) boxes.remove(key)
    }

    internal fun placeViewport(bounds: UiRect) {
        viewport = bounds
    }

    internal fun begin(key: String, localStart: Offset) {
        onGrab()
        dragKey = key
        lastOver = null
        val me = boxes[key]?.second
        pointer = if (me == null) localStart else me.topLeft + localStart
    }

    /**
     * Follows the finger and moves the carried tile into the slot under it.
     *
     * The carried tile takes the position of the tile it is over, in the order
     * as it stands, and everything between shifts along one. An earlier version
     * removed the carried tile first and then looked the target up in the
     * shortened list, which is one place short whenever the move is forwards: a
     * drag onto the very next tile put it straight back where it started, and a
     * longer drag always stopped one before the finger. Backwards happened to
     * work, which is what made it look random.
     */
    internal fun drag(delta: Offset) {
        val key = dragKey ?: return
        pointer += delta

        val current = order()
        val from = current.indexOf(key)
        if (from < 0) return

        val over = boxes.entries.firstOrNull { (k, v) ->
            k != key && k in current && v.second.contains(pointer)
        }?.key

        // Moving off every tile, or onto a new one, re-arms swapping. Staying on
        // the tile just swapped with does not: with tiles of different sizes the
        // swap can leave the finger over that same tile, and swapping again would
        // put it straight back, then again, for as long as the finger stayed.
        if (over == null || over != lastOver) lastOver = null
        if (over == null || over == lastOver) return

        val to = current.indexOf(over)
        if (to < 0 || to == from) return
        val next = current.toMutableList()
        next.removeAt(from)
        next.add(to, key)
        lastOver = over
        onOrder(next)
    }

    internal fun finish(commit: Boolean) {
        dragKey = null
        pointer = Offset.Zero
        lastOver = null
        if (commit) onCommit()
    }
}

/**
 * [order] is read live, [onOrder] receives each step of a drag, [onCommit] runs
 * once when the finger lifts, and [onBegin] when a tile is picked up - which is
 * where a shell closes a menu the long press opened.
 */
@Composable
fun rememberReorder(
    order: () -> List<String>,
    onOrder: (List<String>) -> Unit,
    onCommit: () -> Unit,
    onBegin: () -> Unit = {},
): ReorderState {
    val haptics = LocalHapticFeedback.current
    // Current values, not the first composition's. The state object is made
    // once and outlives every recomposition, so capturing the lambdas directly
    // would keep calling whatever the shell passed the very first time.
    val currentOrder by rememberUpdatedState(order)
    val currentOnOrder by rememberUpdatedState(onOrder)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnBegin by rememberUpdatedState(onBegin)
    val currentHaptics by rememberUpdatedState(haptics)
    return remember {
        ReorderState(
            order = { currentOrder() },
            onOrder = { currentOnOrder(it) },
            onCommit = { currentOnCommit() },
            // A tile that has been picked up looks identical to one that has not
            // until the finger moves, so without a tick the gesture reads as
            // "nothing happened" and people let go.
            onGrab = {
                currentHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnBegin()
            },
        )
    }
}

/**
 * Put this on the area the tiles live in - the pager, grid or list.
 *
 * It owns the carrying, the way the classic workspace does. Nothing is consumed
 * unless a tile is actually being carried, so ordinary taps, page swipes and
 * scrolling pass through untouched.
 */
fun Modifier.reorderableContainer(state: ReorderState): Modifier =
    this
        .onGloballyPositioned { state.placeViewport(it.boundsInWindow()) }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var carried = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (state.dragKey != null) {
                        carried = true
                        // The tile's own detector may have claimed this change
                        // already; this is the node that decides where it goes.
                        state.drag(change.positionChangeIgnoreConsumed())
                        change.consume()
                    }
                }
                if (carried || state.dragKey != null) state.finish(commit = true)
            }
        }

/**
 * Everything a touch on one tile can mean.
 *
 * The classic icons' detector: a tap is [onClick], a still press is [onLongPress],
 * and a long press that then moves picks the tile up. A swipe that the pager or
 * list claims is given up unconsumed, so pages still turn and lists still scroll.
 * This works the same in and out of rearrange mode - moving a tile never needs
 * the mode switched on first.
 */
@Composable
fun Modifier.homeTile(
    state: ReorderState,
    key: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val owner = remember { Any() }
    DisposableEffect(key) {
        onDispose { state.forget(key, owner) }
    }
    // Read at the moment of the gesture, so a detector that outlives a
    // recomposition still acts on the tile's current meaning - a tap selects in
    // rearrange mode and launches outside it.
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)
    return this
        .onGloballyPositioned { state.place(key, owner, it.boundsInWindow()) }
        .pointerInput(key) {
            detectLauncherGestures(
                onClick = { click() },
                onLongPress = { longPress() },
                onDragStart = { pos -> state.begin(key, pos) },
            )
        }
}

/**
 * Pulls the list along when a carried tile reaches the edge, so a tile can be
 * carried to a place that is not on screen yet.
 */
@Composable
fun ReorderAutoScroll(state: ReorderState, scroll: ScrollableState) {
    val dragging = state.dragKey
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        while (true) {
            val view = state.viewport
            if (view.height > 0f) {
                // A band a sixth of the way in at each end, pulling harder the
                // closer to the edge the finger gets.
                val band = view.height * 0.16f
                val y = state.pointerY
                val push = when {
                    y < view.top + band -> -(1f - (y - view.top) / band)
                    y > view.bottom - band -> 1f - (view.bottom - y) / band
                    else -> 0f
                }
                if (push != 0f) scroll.scrollBy(push.coerceIn(-1f, 1f) * 20f)
            }
            withFrameNanos { }
        }
    }
}

/**
 * Turns the page when a carried tile is held against the side.
 *
 * Timed rather than per-frame, as the classic workspace does it: the finger has
 * to rest near the edge for a moment to turn a page, so crossing the edge on the
 * way somewhere else does not flick through the whole set.
 */
@Composable
fun ReorderPageTurn(state: ReorderState, pager: PagerState, pages: Int) {
    val dragging = state.dragKey
    LaunchedEffect(dragging) {
        if (dragging == null || pages <= 1) return@LaunchedEffect
        while (true) {
            delay(550)
            if (state.dragKey == null) break
            val view = state.viewport
            if (view.width <= 0f) continue
            val band = view.width * 0.12f
            val x = state.pointerX
            val to = when {
                x < view.left + band -> pager.currentPage - 1
                x > view.right - band -> pager.currentPage + 1
                else -> continue
            }
            if (to in 0 until pages) runCatching { pager.animateScrollToPage(to) }
        }
    }
}
