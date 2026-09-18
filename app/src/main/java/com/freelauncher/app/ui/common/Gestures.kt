package com.freelauncher.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.withTimeoutOrNull

private enum class Outcome { CLICK, GIVE_UP }

/**
 * Tap, long-press, and the *start* of a drag, on a launcher icon.
 *
 * Written by hand rather than composed out of `clickable` and
 * `detectDragGesturesAfterLongPress` because those two cannot share a pointer.
 * Both are real pointer input nodes on the same element and both see the same
 * events; whichever settles first consumes, and the other then sees a consumed
 * change and cancels itself. The failure is intermittent and direction
 * dependent -- icons that launch when you meant to drag them, or drags that die
 * the instant they start -- which is a miserable thing to chase later.
 *
 * The other reason it is hand-written is what happens *before* the long press.
 * A short swipe that begins on an icon must belong to the page or to the app
 * drawer, not to the icon. So movement past the touch slop before the long
 * press timeout deliberately abandons the gesture without consuming anything,
 * leaving the pager and the drawer's vertical drag free to pick it up. That is
 * what makes swiping up from an icon open the drawer instead of doing nothing.
 *
 * ## Why this stops at the start of the drag
 *
 * It reports that a drag has begun and then gets out of the way. Following the
 * finger is the root's job -- see the drag tracker on the home screen.
 *
 * It has to be that way round, because this element does not survive the drag.
 * Carrying an item to the edge of the last page creates a page and scrolls to
 * it, and once the page the drag started on falls outside the pager's composed
 * window this element is disposed and the coroutine running this gesture is
 * cancelled. When the tracking lived here, that left the preview glued to the
 * finger with no way to put it down. The root is an ancestor of every icon, so
 * it is in the hit path for the whole gesture and is never disposed part way
 * through it.
 *
 * Nothing here consumes the drag, so those events still reach the root.
 */
suspend fun PointerInputScope.detectLauncherGestures(
    onClick: () -> Unit,
    onLongPress: (Offset) -> Unit,
    onDragStart: (Offset) -> Unit = {},
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val slop = viewConfiguration.touchSlop
    val timeout = viewConfiguration.longPressTimeoutMillis

    // A null result means the timeout elapsed with the finger still down and
    // still: that, and only that, is a long press.
    val outcome: Outcome? = withTimeoutOrNull(timeout) {
        var result = Outcome.GIVE_UP
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) break

            // Somebody else has claimed this pointer -- the app drawer's grid
            // scrolling under the finger, or the pager turning a page.
            // Whatever the gesture becomes, it is no longer a tap on this icon.
            //
            // Without this test, a downward swipe in the drawer that the grid
            // consumes still arrives here as press-then-release inside the
            // touch slop, and launches whatever app the finger happened to
            // start on. That is the single most annoying thing a launcher can
            // do, because the recovery is to leave the app you did not want.
            if (change.isConsumed) break

            if (!change.pressed) {
                result = Outcome.CLICK
                break
            }
            if ((change.position - down.position).getDistance() > slop) break
        }
        result
    }

    when (outcome) {
        Outcome.CLICK -> {
            onClick()
            return@awaitEachGesture
        }
        // Moved, or the pointer vanished. Left unconsumed on purpose so the
        // pager or the drawer gesture can claim it.
        Outcome.GIVE_UP -> return@awaitEachGesture

        null -> Unit
    }

    onLongPress(down.position)

    // Held. From here the only thing still to decide is whether it becomes a
    // drag, and the moment it does this gesture is finished.
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
        if (!change.pressed) return@awaitEachGesture
        if ((change.position - down.position).getDistance() > slop) {
            onDragStart(change.position)
            return@awaitEachGesture
        }
    }
}
