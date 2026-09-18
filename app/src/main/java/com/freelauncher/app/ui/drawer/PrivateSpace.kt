package com.freelauncher.app.ui.drawer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freelauncher.app.data.AppEntry
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.ui.common.AppIcon
import com.freelauncher.app.ui.common.IconTile
import com.freelauncher.app.ui.common.detectLauncherGestures
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Private space.
 *
 * Android 15 gives a phone a second, lockable profile whose apps are meant to
 * be invisible until it is opened. The platform reports it through the same
 * profile list as a work profile, so a launcher that does nothing about it does
 * the worst possible thing: it quietly lists private apps in the drawer beside
 * everything else. [com.freelauncher.app.data.AppRepository] splits them off at
 * the source ; this is the only place they are ever shown.
 *
 * ## Why it is a panel of its own
 *
 * Every other launcher puts private space at the bottom of the app drawer,
 * behind a header you scroll to. That is discoverable, which is exactly the
 * complaint: anyone who scrolls to the end of the drawer learns that a private
 * space exists and that there is something in it. Here there is no row, no
 * header and no gap at the end of the list. The way in is a hold on the
 * drawer's grab handle, which has no affordance to find and no effect at all on
 * a phone that has no private profile -- so the gesture cannot even be used to
 * test whether one is set up.
 *
 * The lock is the system's, not this app's. Unlocking asks the platform to
 * lift quiet mode and the platform raises its own credential prompt; the PIN
 * never comes near this process, and the launcher finds out it worked because
 * the profile broadcasts that it became available.
 */
@Composable
fun PrivateSpaceSheet(
    apps: List<AppEntry>,
    locked: Boolean,
    settings: LauncherSettings,
    onLaunch: (AppEntry, Rect) -> Unit,
    onMenu: (AppEntry, Rect) -> Unit,
    onReorder: (List<String>) -> Unit,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Header(
                locked = locked,
                onLock = onLock,
                onOpenSystemSettings = onOpenSystemSettings,
                onDismiss = onDismiss,
            )

            when {
                locked -> Locked(onUnlock)
                apps.isEmpty() -> Empty(onOpenSystemSettings)
                else -> Grid(apps, settings, onLaunch, onMenu, onReorder)
            }
        }
    }
}

@Composable
private fun Header(
    locked: Boolean,
    onLock: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Private space",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )

        // Only once it is open. A lock button on a locked space is a button
        // that does nothing, and it is also the one control here that would
        // give the game away if anyone ever saw this screen locked.
        if (!locked) {
            IconButton(onClick = onLock) {
                Icon(Icons.Rounded.Lock, contentDescription = "Lock private space")
            }
        }
        IconButton(onClick = onOpenSystemSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "Private space settings")
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun Locked(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Private space is locked",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Unlocking asks Android for your screen lock. FreeLauncher never sees it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

@Composable
private fun Empty(onOpenSystemSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Text(
                "Nothing installed here yet",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Apps are added to private space from Android's own settings, not from a launcher.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenSystemSettings) { Text("Open private space settings") }
        }
    }
}

/**
 * The apps, in the order the user put them in.
 *
 * ## Why this is not a LazyVerticalGrid
 *
 * It was one, and a lazy grid is the right answer for the drawer: two hundred
 * apps, alphabetical, scrolled fast. A private space is a handful of apps that
 * the user arranged on purpose, and arranging them needs the one thing a lazy
 * grid makes awkward -- knowing, from a finger position alone, which slot is
 * under it. Laying the rows out directly makes every cell the same size and the
 * arithmetic exact, which is the same reason an open folder is built this way.
 *
 * ## The gesture
 *
 * Identical to a folder's, deliberately: hold an app to lift it, move to
 * rearrange, or let go without moving for its menu. Learning it once should be
 * enough, and there is no third thing a hold could mean here -- an app cannot
 * be dragged out of private space, because which profile it lives in is
 * Android's business and not this launcher's.
 */
@Composable
private fun Grid(
    apps: List<AppEntry>,
    settings: LauncherSettings,
    onLaunch: (AppEntry, Rect) -> Unit,
    onMenu: (AppEntry, Rect) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val haptics = LocalHapticFeedback.current
    val portraitWidth = minOf(config.screenWidthDp, config.screenHeightDp).dp
    val scroll = rememberScrollState()

    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragAnchor by remember { mutableStateOf(Rect.Zero) }
    var dragMoved by remember { mutableStateOf(false) }
    var gridRect by remember { mutableStateOf(Rect.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Sized the way the drawer sizes its cells, so an icon is the same size
        // in both places. A phone held sideways gets more columns of that size
        // rather than the same number of very wide ones.
        // Two dp under an exact fit, for the same reason the drawer takes them
        // off: the division below truncates, and a cell sized to fit exactly
        // n times lands on n - 0.999 as readily as on n.
        val cellW = ((portraitWidth - 16.dp) / settings.drawerCols - 2.dp)
            .coerceAtLeast(48.dp)
        val columns = ((maxWidth - 16.dp) / cellW).toInt().coerceAtLeast(1)
        val iconSize = (52.dp * settings.iconScale).coerceIn(32.dp, 84.dp)
        val labelHeight = with(density) { (13f * settings.labelScale).sp.toDp() }
        val cellH = iconSize + labelHeight + 26.dp
        val rows = ceil(apps.size / columns.toFloat()).toInt()

        // Which slot the finger is over.
        val hoverIndex: Int? = remember(dragKey, dragPos, gridRect, columns, cellH, apps.size) {
            if (dragKey == null || gridRect.width <= 0f) return@remember null
            if (!gridRect.contains(dragPos)) return@remember null
            val w = gridRect.width / columns
            val h = with(density) { cellH.toPx() }
            if (h <= 0f) return@remember null
            val column = ((dragPos.x - gridRect.left) / w).toInt().coerceIn(0, columns - 1)
            val row = ((dragPos.y - gridRect.top) / h).toInt().coerceAtLeast(0)
            (row * columns + column).coerceIn(0, apps.size - 1)
        }

        // What the grid draws mid-drag: the real order with the carried app
        // lifted out and put back where the finger is, so the gap being looked
        // at is the gap it will land in.
        val displayed: List<AppEntry> = remember(apps, dragKey, hoverIndex) {
            val carried = dragKey ?: return@remember apps
            val target = hoverIndex ?: return@remember apps
            val working = apps.toMutableList()
            val from = working.indexOfFirst { it.key == carried }
            if (from < 0) return@remember apps
            val item = working.removeAt(from)
            working.add(target.coerceIn(0, working.size), item)
            working
        }

        // Held through rememberUpdatedState so the tracker below, which is
        // created once, reads the current hover rather than the composition it
        // happened to be built in -- the same trap an open folder fell into.
        val finishDrag by rememberUpdatedState<() -> Unit> {
            val carried = dragKey
            if (carried != null) {
                dragKey = null
                val entry = apps.firstOrNull { it.key == carried }
                if (entry != null) {
                    when {
                        !dragMoved -> onMenu(entry, dragAnchor)
                        hoverIndex != null -> onReorder(displayed.map { it.key })
                        else -> Unit
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                // Declared out here rather than on the scrolling column, so it
                // survives the rows reflowing underneath the finger.
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var carried = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (dragKey != null) {
                                carried = true
                                dragPos += change.positionChange()
                                if (!dragMoved &&
                                    (dragPos - dragAnchor.center).getDistance() > slop
                                ) {
                                    dragMoved = true
                                }
                                change.consume()
                            }
                        }
                        if (carried || dragKey != null) finishDrag()
                    }
                }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // Scrolling is off while an app is being carried. A scroll
                    // container consumes the vertical part of a drag, and a
                    // consumed change reports as no movement at all, which
                    // would leave the tracker above believing the finger had
                    // never left the icon.
                    .verticalScroll(scroll, enabled = dragKey == null)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    // Position and size, not boundsInWindow: that one is
                    // clipped to its parents, so a scrolled grid would report a
                    // shorter rect than it has and the row arithmetic would
                    // drift as it scrolled.
                    .onGloballyPositioned {
                        val topLeft = it.positionInWindow()
                        gridRect = Rect(
                            topLeft.x,
                            topLeft.y,
                            topLeft.x + it.size.width,
                            topLeft.y + it.size.height,
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (row in 0 until rows) {
                    Row(Modifier.width(cellW * columns)) {
                        for (column in 0 until columns) {
                            val entry = displayed.getOrNull(row * columns + column)
                            if (entry == null) {
                                Spacer(Modifier.size(cellW, cellH))
                            } else {
                                PrivateCell(
                                    entry = entry,
                                    settings = settings,
                                    iconSize = iconSize,
                                    dragging = dragKey == entry.key,
                                    modifier = Modifier.size(cellW, cellH),
                                    onLaunch = onLaunch,
                                    onPickUp = { cell ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragPos = cell.center
                                        dragAnchor = cell
                                        dragMoved = false
                                        dragKey = entry.key
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // The app under the finger, drawn last so it is above the grid.
            dragKey?.let { carried ->
                val entry = apps.firstOrNull { it.key == carried }
                if (entry != null) {
                    val preview = iconSize * 1.15f
                    val half = with(density) { preview.toPx() / 2f }
                    Box(
                        Modifier
                            .offset {
                                androidx.compose.ui.unit.IntOffset(
                                    (dragPos.x - half).roundToInt(),
                                    (dragPos.y - half).roundToInt(),
                                )
                            }
                            .graphicsLayer { alpha = 0.9f }
                    ) {
                        AppIcon(entry, settings.iconShape, preview)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateCell(
    entry: AppEntry,
    settings: LauncherSettings,
    iconSize: androidx.compose.ui.unit.Dp,
    dragging: Boolean,
    modifier: Modifier = Modifier,
    onLaunch: (AppEntry, Rect) -> Unit,
    onPickUp: (Rect) -> Unit,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            // The carried icon is drawn by the overlay following the finger;
            // leaving it drawn here as well would show two of it.
            .graphicsLayer { alpha = if (dragging) 0f else 1f }
            .semantics(mergeDescendants = true) {
                contentDescription = entry.label
                role = Role.Button
                onClick(label = "Open") {
                    onLaunch(entry, bounds)
                    true
                }
                onLongClick(label = "Show options") {
                    onPickUp(bounds)
                    true
                }
            }
            .pointerInput(entry.key) {
                detectLauncherGestures(
                    onClick = { onLaunch(entry, bounds) },
                    onLongPress = { onPickUp(bounds) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        IconTile(
            label = entry.label,
            showLabel = settings.drawerShowLabels,
            labelScale = settings.labelScale,
            onWallpaper = false,
        ) {
            AppIcon(entry, settings.iconShape, iconSize)
        }
    }
}
