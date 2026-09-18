package com.freelauncher.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.freelauncher.app.data.Container
import com.freelauncher.app.data.FOLDER_COLUMNS
import com.freelauncher.app.data.ItemType
import com.freelauncher.app.data.LauncherItem
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.ui.common.IconTile
import com.freelauncher.app.ui.common.ItemIcon
import com.freelauncher.app.ui.common.detectLauncherGestures
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Three across, three down, then swipe.
 *
 * Three columns because four only fitted by shrinking every icon and label
 * until the folder became the fiddliest thing on the home screen, which is
 * backwards ; a folder exists to be tapped. Nine to a page because a tenth app
 * should push the panel sideways, not downwards: a folder tall enough to hold
 * twenty apps has stopped being a folder and become a screen.
 */
internal const val FOLDER_GRID_COLS = FOLDER_COLUMNS
private const val FOLDER_ROWS = 3
private const val FOLDER_PER_PAGE = FOLDER_GRID_COLS * FOLDER_ROWS
private const val FOLDER_ICON_DP = 60

/** One cell, at its natural width. Panels narrower than three columns keep it. */
private const val FOLDER_CELL_DP = 92

/** Padding above and below the icon inside its cell, top and bottom combined. */
private const val CELL_PADDING_DP = 16

private const val ROW_GAP_DP = 2

/** Floor on the panel width, so a one-app folder can still show its name. */
private const val FOLDER_MIN_WIDTH_DP = 196

/**
 * An opened folder.
 *
 * Opens over the folder it came from, centred on that icon and clamped to the
 * screen, and scaled out of that same point so a folder in a top corner grows
 * from the top corner. The panel used to sit at one fixed place above the dock,
 * which was steadier to aim at but told the user nothing: every folder on every
 * page produced an identical panel in an identical place, with no thread back
 * to the thing that had been tapped.
 *
 * Clamping is what makes anchoring safe. The panel is placed where the folder
 * is and then pushed back inside the margins, so a folder in the very corner
 * still opens fully on screen ; only its growth origin stays in the corner.
 *
 * Nothing behind it is dimmed. A scrim makes a folder read as a modal step away
 * from the home screen, and a folder is not that: it is a part of the home
 * screen that has been opened up. The panel separates itself with elevation
 * instead, which is what keeps the wallpaper and the surrounding icons looking
 * like they are still there rather than switched off.
 *
 * The name sits under the contents, not above them. A heading across the top is
 * the first thing read on a panel opened to tap the third icon, and most
 * folders are never named at all -- so the row doubles as the place the name is
 * given, showing a prompt when there is nothing to show yet.
 */
@Composable
fun FolderOverlay(
    folder: LauncherItem,
    children: List<LauncherItem>,
    settings: LauncherSettings,
    anchor: Rect,
    onDismiss: () -> Unit,
    onLaunch: (LauncherItem, Rect) -> Unit,
    onRename: (String) -> Unit,
    onTakeOut: (LauncherItem) -> Unit,
    onReorder: (List<Long>) -> Unit,
    onItemMenu: (LauncherItem, Rect) -> Unit,
    onSort: () -> Unit,
    onSelectApps: () -> Unit,
) {
    var title by remember(folder.id) { mutableStateOf(folder.title) }
    var editing by remember(folder.id) { mutableStateOf(false) }
    var menuOpen by remember(folder.id) { mutableStateOf(false) }

    // Rearranging, tracked here rather than in the cell that started it.
    //
    // The cell is the wrong owner: reordering moves cells, so the one holding
    // the gesture is disposed and recreated the moment the grid reflows under
    // the finger, and the drag dies with it. The same reasoning put the
    // workspace's drag tracking on the home screen root.
    var dragId by remember(folder.id) { mutableStateOf<Long?>(null) }
    var dragPos by remember(folder.id) { mutableStateOf(Offset.Zero) }
    var gridRect by remember(folder.id) { mutableStateOf(Rect.Zero) }
    var panelRect by remember(folder.id) { mutableStateOf(Rect.Zero) }

    // Where the held app sits, and whether the finger has left it.
    //
    // Holding an icon has to mean two things at once, because there is only one
    // gesture to spend: hold and move rearranges, hold and let go asks about
    // the app. The pick-up happens either way -- it has to, or a rearrange
    // would not start until the finger had already moved past the icon it was
    // meant to lift -- and which of the two it was is decided at the release,
    // from whether the finger ever travelled.
    //
    // Without this a hold inside a folder had no outcome at all: no way to take
    // an app out except by dragging it onto the desktop, and no way to reach
    // App info or Uninstall for anything filed in a folder.
    var dragAnchor by remember(folder.id) { mutableStateOf(Rect.Zero) }
    var dragMoved by remember(folder.id) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    // The rename is committed on the way out. A folder name has no obvious
    // confirm button, and asking for one would be a dialog inside a dialog.
    fun close() {
        if (title != folder.title) onRename(title)
        onDismiss()
    }

    val pageCount = max(1, ceil(children.size / FOLDER_PER_PAGE.toFloat()).toInt())
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // The panel is sized to what is in it, in both directions.
    //
    // Three columns is the maximum, not the width every folder is built to. A
    // folder holding two apps laid out on a three-column panel is two icons and
    // a third of a panel of nothing, and that dead column is most of what makes
    // an open folder look oversized.
    val columns = children.size.coerceIn(1, FOLDER_GRID_COLS)

    // Rows on the tallest page, rather than always three. Once a folder pages,
    // every page reserves the full three rows so the panel does not change
    // height under the finger mid-swipe; a folder that never pages has no
    // reason to reserve a row it will not use.
    val rows = if (pageCount > 1) {
        FOLDER_ROWS
    } else {
        ceil(children.size / columns.toFloat()).toInt().coerceIn(1, FOLDER_ROWS)
    }

    // Labels are in sp, so they grow with the launcher's own label setting and
    // again with the system font size. Deriving the row height from the same
    // number is what keeps the last line of text inside its cell at either
    // extreme, instead of clipping only for the people who enlarged the text
    // because they needed it larger.
    val labelHeight = with(density) { (14f * settings.labelScale).sp.toDp() }
    val cellHeight = FOLDER_ICON_DP.dp + labelHeight + CELL_PADDING_DP.dp

    // Which slot the finger is over, as an index into the page under it.
    //
    // Null while nothing is being carried, and null once the finger has left
    // the panel -- which is what turns the same gesture into "take this out"
    // without a second control to aim at.
    val pageStart = pagerState.currentPage * FOLDER_PER_PAGE
    val pageCountOnPage = (children.size - pageStart).coerceIn(0, FOLDER_PER_PAGE)
    val hoverIndex: Int? = remember(dragId, dragPos, gridRect, panelRect, columns, rows, pageStart) {
        if (dragId == null || gridRect.width <= 0f) return@remember null
        if (!panelRect.contains(dragPos)) return@remember null
        val cellW = gridRect.width / columns
        val cellH = gridRect.height / rows
        val column = ((dragPos.x - gridRect.left) / cellW).toInt().coerceIn(0, columns - 1)
        val row = ((dragPos.y - gridRect.top) / cellH).toInt().coerceIn(0, rows - 1)
        (row * columns + column).coerceIn(0, (pageCountOnPage - 1).coerceAtLeast(0))
    }

    // What the grid draws while a drag is in flight: the real order with the
    // carried app lifted out and put back at the slot under the finger. The
    // cells shift as the finger moves, so the gap the app will land in is
    // always the gap being looked at, and the drop needs no separate preview.
    val displayed: List<LauncherItem> = remember(children, dragId, hoverIndex, pageStart) {
        val carried = dragId ?: return@remember children
        val target = hoverIndex ?: return@remember children
        val working = children.toMutableList()
        val from = working.indexOfFirst { it.id == carried }
        if (from < 0) return@remember children
        val item = working.removeAt(from)
        working.add((pageStart + target).coerceIn(0, working.size), item)
        working
    }

    // Held through rememberUpdatedState, not captured directly.
    //
    // The tracker below lives in a pointerInput keyed on the folder, so its
    // block is created once and runs for the life of the panel. A plain local
    // function captures the composition that made it -- which is the one where
    // nothing was being dragged, so hoverIndex was null and every drop was read
    // as "dragged off the panel" and turned the app out onto the desktop.
    val finishDrag by rememberUpdatedState<() -> Unit> {
        val carried = dragId
        if (carried != null) {
            dragId = null
            val item = children.firstOrNull { it.id == carried }
            if (item != null) {
                when {
                    // Held and released without going anywhere. Nothing has
                    // moved, so nothing is written -- the hold was a question
                    // about the app, and the menu is the answer.
                    !dragMoved -> onItemMenu(item, dragAnchor)

                    // Off the panel means out of the folder. Anywhere on it
                    // means the order the grid has been showing all along.
                    hoverIndex == null -> onTakeOut(item)

                    else -> onReorder(displayed.map { it.id })
                }
            }
        }
    }

    // Measured, not counted.
    //
    // ItemMenu below computes its own height from its row count, because its
    // rows are decided a few lines above the arithmetic and counting them is
    // exact. A folder cannot do that: the name row grows an underline while it
    // is being edited, and the grid height moves with two independent scale
    // settings. So the panel is measured and then placed -- and the reveal is
    // held until it has been, which costs one frame that nobody sees because
    // the whole overlay starts at zero alpha anyway.
    var panelSize by remember(folder.id) { mutableStateOf(IntSize.Zero) }
    var shown by remember(folder.id) { mutableStateOf(false) }
    LaunchedEffect(folder.id, panelSize) { if (panelSize != IntSize.Zero) shown = true }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "folder",
    )

    // Drawn inside the launcher's own window rather than in a Dialog.
    //
    // A Dialog gets a window of its own, and that window is inset below the
    // status bar however it is configured -- so window coordinates inside it do
    // not line up with the boundsInWindow the folder's icon reported, which is
    // the one measurement this whole layout is built on. Staying in the home
    // screen's window keeps both in the same space.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear }
            // Catches the tap that closes the folder. No colour: the wallpaper
            // and the icons around the panel stay exactly as they were.
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = ::close,
            )
            // Carries the drag once a cell has handed it over. Declared on the
            // full-size box so it survives the grid reflowing underneath, and
            // consumes while carrying so the tap-to-close above never fires on
            // the release that ends a rearrange.
            .pointerInput(folder.id) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var carried = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (dragId != null) {
                            carried = true
                            dragPos += change.positionChange()

                            // Measured against where the app was picked up, not
                            // summed frame by frame. A finger resting on glass
                            // reports a steady trickle of sub-pixel movement,
                            // and a running total of that crosses any threshold
                            // eventually -- so a long hold would decide, after a
                            // second of holding perfectly still, that it had
                            // been a drag all along.
                            if (!dragMoved &&
                                (dragPos - dragAnchor.center).getDistance() > slop
                            ) {
                                dragMoved = true
                            }
                        }

                        // Absorb whatever the panel's own children did not
                        // take. The folder has no scrim to swallow gestures, so
                        // without this a swipe across an open folder reached
                        // the home screen behind it and pulled the app drawer
                        // up underneath the panel.
                        if (!change.isConsumed) change.consume()
                    }
                    if (carried || dragId != null) finishDrag()
                }
            },
    ) {
        val margin = 14.dp
        val gridPadding = 8.dp

        // Wide enough for the icons it holds, with a floor.
        //
        // The floor is for the name row, not the grid: a one-app folder sized
        // purely to its single icon is about 100dp across, and a name like
        // "Work" ellipsises inside it. Below three columns the icons are
        // centred in the panel rather than stretched to fill it, so the floor
        // costs symmetry and not a stretched-out grid.
        val ideal = FOLDER_CELL_DP.dp * columns + gridPadding * 2
        val panelWidth = minOf(maxOf(ideal, FOLDER_MIN_WIDTH_DP.dp), maxWidth - margin * 2)
        val cellWidth = minOf(FOLDER_CELL_DP.dp, (panelWidth - gridPadding * 2) / columns)

        // The window's own size, straight from the constraints, because this
        // Box fills it. LocalConfiguration's screen size is the display's and
        // disagrees with it on exactly the devices where it matters.
        val windowW = constraints.maxWidth.toFloat()
        val windowH = constraints.maxHeight.toFloat()
        val marginPx = with(density) { margin.toPx() }
        val topInset = WindowInsets.statusBars.getTop(density).toFloat()

        // The keyboard, when a folder is being renamed, and the gesture bar the
        // rest of the time. Taking the larger of the two is what lifts the
        // panel clear of the keyboard without a second layout pass.
        val bottomInset = max(
            WindowInsets.navigationBars.getBottom(density),
            WindowInsets.ime.getBottom(density),
        ).toFloat()

        // A folder opened from somewhere with no recorded bounds -- restored
        // state, or a folder opened before its cell had been positioned -- has
        // nowhere to grow from, so it falls back to low and centred, which is
        // where the panel used to live.
        val focus = if (anchor.width <= 0f || anchor.height <= 0f) {
            Offset(windowW / 2f, windowH * 0.62f)
        } else {
            anchor.center
        }

        val panelW = panelSize.width.toFloat()
        val panelH = panelSize.height.toFloat()
        val x = placeWithin(focus.x - panelW / 2f, marginPx, windowW - panelW - marginPx)
        val y = placeWithin(
            focus.y - panelH / 2f,
            topInset + marginPx,
            windowH - panelH - bottomInset - marginPx,
        )

        // Where the panel grows from, in its own coordinates. Clamped because
        // the panel may have been pushed away from the folder to stay on
        // screen, and an origin outside the panel sends it flying in from off
        // screen instead of expanding.
        val originX = if (panelW > 0f) ((focus.x - x) / panelW).coerceIn(0f, 1f) else 0.5f
        val originY = if (panelH > 0f) ((focus.y - y) / panelH).coerceIn(0f, 1f) else 0.5f

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            // Carries the whole separation now that nothing behind is dimmed,
            // so it is deeper than it was when a scrim was doing half the work.
            shadowElevation = 28.dp,
            modifier = Modifier
                .width(panelWidth)
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .onSizeChanged { panelSize = it }
                .onGloballyPositioned { panelRect = it.boundsInWindow() }
                .graphicsLayer {
                    scaleX = 0.84f + 0.16f * appear
                    scaleY = 0.84f + 0.16f * appear
                    transformOrigin = TransformOrigin(originX, originY)
                }
                // Swallows taps on the panel itself, which would otherwise fall
                // through to the dismiss handler behind and close the folder the
                // moment the user aimed at a gap between two icons.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                Modifier.padding(top = 14.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (children.isEmpty()) {
                    Text(
                        "This folder is empty.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                } else {
                    // A pager of fixed grids, not a scrolling grid.
                    //
                    // A scroller would be less code, but it gives a folder no
                    // resting shape: the contents stop half way through a row,
                    // and the app the user is reaching for is at a different
                    // height every time the folder is opened. Whole pages mean
                    // an app keeps its position for as long as the folder is
                    // left alone.
                    // Page swiping is switched off for as long as an app is
                    // being carried, and this is not a nicety.
                    //
                    // A pager is a scrollable, and a scrollable *consumes* the
                    // horizontal part of every drag it is offered -- including
                    // on a one-page folder, where it has nowhere to scroll to.
                    // Compose reports a consumed change as having moved by
                    // zero, so the tracker below saw a sideways drag as a
                    // finger that had not moved at all.
                    //
                    // The effect was that dragging an app sideways -- the most
                    // natural way to swap two icons in a row -- reordered
                    // nothing, silently. Vertical drags were unaffected, which
                    // is what made it look like an occasional glitch rather
                    // than one axis being swallowed whole.
                    HorizontalPager(
                        state = pagerState,
                        verticalAlignment = Alignment.Top,
                        userScrollEnabled = dragId == null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(cellHeight * rows + ROW_GAP_DP.dp * (rows - 1)),
                    ) { page ->
                        val pageItems = displayed
                            .drop(page * FOLDER_PER_PAGE)
                            .take(FOLDER_PER_PAGE)

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = gridPadding)
                                .onGloballyPositioned {
                                    if (page == pagerState.currentPage) {
                                        gridRect = it.boundsInWindow()
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
                        ) {
                            for (row in 0 until rows) {
                                // Exactly as wide as its cells, then centred.
                                // Filling the width instead would spread two
                                // icons to the panel's edges and reintroduce
                                // the gap the narrower panel exists to remove.
                                Row(Modifier.width(cellWidth * columns)) {
                                    for (column in 0 until columns) {
                                        val child = pageItems.getOrNull(row * columns + column)

                                        // The gap is held open rather than left
                                        // out. A last row of two icons belongs
                                        // under the first two of the row above,
                                        // not centred under three -- otherwise
                                        // every app shifts sideways the moment
                                        // one is removed.
                                        if (child == null) {
                                            Spacer(Modifier.size(cellWidth, cellHeight))
                                        } else {
                                            FolderChild(
                                                child = child,
                                                settings = settings,
                                                dragging = dragId == child.id,
                                                modifier = Modifier.size(cellWidth, cellHeight),
                                                onLaunch = onLaunch,
                                                onPickUp = { cell ->
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                    dragPos = cell.center
                                                    dragAnchor = cell
                                                    dragMoved = false
                                                    dragId = child.id
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (pageCount > 1) {
                        Spacer(Modifier.height(8.dp))
                        FolderDots(pageCount = pageCount, state = pagerState)
                    }
                }

                Spacer(Modifier.height(4.dp))
                FolderNameRow(
                    title = title,
                    editing = editing,
                    focusRequester = focusRequester,
                    menuOpen = menuOpen,
                    onChange = { title = it },
                    onStartEditing = { editing = true },
                    onToggleMenu = { menuOpen = !menuOpen },
                    onDismissMenu = { menuOpen = false },
                    onSort = {
                        menuOpen = false
                        onSort()
                    },
                    onSelectApps = {
                        menuOpen = false
                        // The rename in flight is committed first. Picking apps
                        // replaces the folder's contents from the outside, and
                        // an uncommitted name would be thrown away with the
                        // composition when the picker takes over.
                        if (title != folder.title) onRename(title)
                        onSelectApps()
                    },
                )
            }
        }

        // The app under the finger, drawn last so it is over the panel.
        //
        // Slightly larger than it sits in the grid, and faded, which is the
        // same language the workspace uses for a carried icon -- it reads as
        // lifted rather than as a copy left behind.
        dragId?.let { carried ->
            val item = children.firstOrNull { it.id == carried }
            if (item != null) {
                val preview = FOLDER_ICON_DP.dp * 1.15f
                val half = with(density) { preview.toPx() / 2f }
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (dragPos.x - half).roundToInt(),
                                (dragPos.y - half).roundToInt(),
                            )
                        }
                        .graphicsLayer { alpha = 0.9f }
                ) {
                    ItemIcon(item, settings.iconShape, preview)
                }
            }
        }
    }
}

/**
 * Clamp, with the degenerate case spelled out.
 *
 * `coerceIn` throws when the upper bound is below the lower one, and that is
 * reachable here: a panel wider than the window leaves no legal x at all. A
 * crash is the wrong answer to "this does not fit" -- pinning it to the near
 * edge and letting it overhang the far one is at least usable.
 */
private fun placeWithin(want: Float, low: Float, high: Float): Float =
    if (high <= low) low else want.coerceIn(low, high)

/**
 * Which page of a folder is showing.
 *
 * Takes the pager state rather than an Int so that the page number is read
 * here, inside the dots, and not in the panel body. Reading it up there would
 * recompose the name, the grid and every icon in it on each swipe, to move
 * two dots.
 */
@Composable
private fun FolderDots(pageCount: Int, state: PagerState) {
    val current = state.currentPage
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val active = index == current
            Box(
                Modifier
                    .size(if (active) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(onSurface.copy(alpha = if (active) 0.85f else 0.28f))
            )
        }
    }
}

/**
 * The folder's name, and everything else it can be asked to do.
 *
 * One row under the grid rather than a heading over it. The name is plain text
 * until it is tapped: a field that is always a field puts a filled container
 * and an underline across the panel, which reads as a form, and nine times out
 * of ten the folder was opened to launch something.
 *
 * An unnamed folder shows a prompt in its place. Most folders are never named,
 * and without the prompt there is nothing at all to tap -- the ability to name
 * one would exist but be invisible.
 */
@Composable
private fun FolderNameRow(
    title: String,
    editing: Boolean,
    focusRequester: FocusRequester,
    menuOpen: Boolean,
    onChange: (String) -> Unit,
    onStartEditing: () -> Unit,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onSort: () -> Unit,
    onSelectApps: () -> Unit,
) {
    val style = MaterialTheme.typography.titleSmall.copy(textAlign = TextAlign.Center)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (editing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicTextField(
                    value = title,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .padding(horizontal = 36.dp)
                        .fillMaxWidth(),
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .width(120.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            }
        } else {
            Text(
                text = title.ifEmpty { "Edit Name" },
                style = style,
                color = if (title.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onStartEditing)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Box(Modifier.align(Alignment.CenterEnd)) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "Folder options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onToggleMenu)
                    .padding(8.dp)
                    .size(18.dp),
            )

            if (menuOpen) {
                // Anchored to the button and hung below it, which is where the
                // finger already is. Declared inside the button's own Box so
                // the popup follows it rather than the panel, and the panel is
                // free to sit anywhere on screen.
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(0, 0),
                    onDismissRequest = onDismissMenu,
                    properties = PopupProperties(focusable = true),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 3.dp,
                        shadowElevation = 12.dp,
                        // A fixed width, not a minimum. Popup content is handed
                        // the whole window to measure against, so a minimum
                        // leaves the Surface free to fill the screen -- which is
                        // exactly what it did, turning a two-item menu into a
                        // full-width band across the wallpaper.
                        modifier = Modifier.width(190.dp),
                    ) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            FolderMenuRow("Select apps", onSelectApps)
                            FolderMenuRow("Sort", onSort)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderMenuRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/**
 * One app inside an open folder.
 *
 * The whole cell is the target, not the icon: a 60dp icon inside a 104dp cell
 * leaves a ring of dead space, and a folder is exactly where a user is least
 * willing to tap twice.
 *
 * Sized by the caller rather than filling its width, because the grid is laid
 * out as fixed cells: an app has to keep its column whether the row it is in
 * holds one app or three.
 */
@Composable
private fun FolderChild(
    child: LauncherItem,
    settings: LauncherSettings,
    dragging: Boolean,
    modifier: Modifier = Modifier,
    onLaunch: (LauncherItem, Rect) -> Unit,
    onPickUp: (Rect) -> Unit,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }

    // The icon being carried is drawn by the overlay, following the finger.
    // Leaving it drawn here as well would show two of it.
    val alpha by animateFloatAsState(if (dragging) 0f else 1f, label = "carried")

    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .graphicsLayer { this.alpha = alpha }
            .semantics(mergeDescendants = true) {
                contentDescription = child.title.ifEmpty { "Unlabelled icon" }
                role = Role.Button
                onClick(label = "Open") {
                    onLaunch(child, bounds)
                    true
                }
                onLongClick(label = "Show options") {
                    onPickUp(bounds)
                    true
                }
            }
            .pointerInput(child.id) {
                detectLauncherGestures(
                    onClick = { onLaunch(child, bounds) },
                    // A hold picks the app up. What that turns into is decided
                    // by where the finger goes next: somewhere else in the grid
                    // reorders, off the panel entirely puts it back on the
                    // desktop, and not moving at all opens the app's own menu.
                    // Holding used to do the second of those immediately, which
                    // made rearranging a folder impossible and emptied it by
                    // accident.
                    //
                    // The whole cell goes over rather than its centre, because
                    // the menu has to be anchored to the icon that was pressed
                    // and a point is not enough to place it against.
                    onLongPress = { onPickUp(bounds) },
                )
            }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconTile(
            label = child.title,
            showLabel = true,
            labelScale = settings.labelScale,
            onWallpaper = false,
        ) {
            ItemIcon(child, settings.iconShape, FOLDER_ICON_DP.dp)
        }
    }
}

/**
 * One of an app's own shortcuts, ready to draw.
 *
 * The icon is rasterised with the list rather than fetched per row: both are
 * binder calls into the publishing app, and doing them together means the menu
 * opens once, complete, instead of filling in under the finger.
 */
class AppShortcut(
    val id: String,
    val label: String,
    val icon: ImageBitmap?,
    val start: (Rect) -> Unit,
    val pin: () -> Unit,
)

@Composable
fun ItemMenu(
    target: MenuTarget,
    canUninstall: Boolean,
    canResize: Boolean,
    shortcuts: List<AppShortcut> = emptyList(),
    canChangeIcon: Boolean = false,
    canRename: Boolean = false,
    hasChosenIcon: Boolean = false,
    onDismiss: () -> Unit,
    onAppInfo: () -> Unit,
    onRemove: () -> Unit,
    onUninstall: () -> Unit,
    onHide: () -> Unit,
    onAddToHome: () -> Unit,
    onResize: () -> Unit,
    onChangeIcon: () -> Unit = {},
    onRename: () -> Unit = {},
    onResetIcon: () -> Unit = {},
    onWallpaper: () -> Unit,
    onWidgets: () -> Unit,
    onEditPages: () -> Unit,
    onSettings: () -> Unit,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenW = with(density) { config.screenWidthDp.dp.toPx() }
    val screenH = with(density) { config.screenHeightDp.dp.toPx() }
    val menuW = with(density) { 232.dp.toPx() }
    val margin = with(density) { 12.dp.toPx() }

    // The menu's height, from the rows that are actually going to be drawn.
    //
    // This was a fixed 260dp, which is roughly a full five-row menu. A folder's
    // menu has one row. So for a folder near the bottom of the screen the
    // "does it fit below?" test failed against a height four times the real
    // one, the menu flipped above by 260dp, and then drew its single 58dp row
    // at the top of that space -- leaving the Remove button floating most of a
    // screen away from the folder it belonged to, connected to nothing.
    //
    // Counted rather than measured: measuring means drawing the popup once to
    // find out how big it is and then moving it, which the user sees as a jump.
    // The rows are decided right here, so counting them is exact.
    val rows = when (target) {
        is MenuTarget.Placed -> {
            val item = target.item
            var n = 1 // Remove, always
            if (canResize) n++
            if (item.type != ItemType.FOLDER && item.type != ItemType.WIDGET) n++
            if (canUninstall && item.type != ItemType.WIDGET) n++
            if (canChangeIcon) n++
            if (canRename) n++
            if (hasChosenIcon) n++
            n
        }

        is MenuTarget.Drawer -> if (canUninstall) 4 else 3
        is MenuTarget.Private -> 2
        is MenuTarget.Blank -> 4
    }

    // The app's own shortcuts sit above the rest, with a hairline between, and
    // they are counted here for the same reason every other row is: the whole
    // placement below is arithmetic on the row count, and a row the sum does
    // not know about is a menu that opens in the wrong place.
    val shortcutRows = shortcuts.size
    val dividerPx = with(density) { (if (shortcutRows > 0) DIVIDER_GAP_DP else 0).dp.toPx() }
    val wanted = with(density) {
        (ROW_HEIGHT_DP * (rows + shortcutRows) + LIST_PADDING_DP).dp.toPx()
    } + dividerPx

    // Clamped to what there is, and the list scrolls if it does not fit.
    //
    // A menu was at most five rows when this was written; an app publishing
    // four shortcuts can now make it ten, which on a short screen is taller
    // than the space between the status bar and the dock. Growing past the
    // display would put the last row -- Uninstall -- off the bottom edge where
    // it cannot be reached or dismissed.
    val bottomSafePx = with(density) { 56.dp.toPx() }
    val roomPx = (screenH - bottomSafePx - margin * 2).coerceAtLeast(with(density) { 120.dp.toPx() })
    val menuH = minOf(wanted, roomPx)
    val scrolls = wanted > roomPx

    val anchor = when (target) {
        is MenuTarget.Placed -> target.anchor
        is MenuTarget.Drawer -> target.anchor
        is MenuTarget.Private -> target.anchor
        // A point at the middle of the screen. A zero-width rect built from
        // left/top alone reports a nonsense centre, which is what the
        // horizontal placement below reads.
        is MenuTarget.Blank -> Rect(screenW / 2f, screenH / 2f, screenW / 2f, screenH / 2f)
    }

    // Centred on the icon rather than aligned to its left edge, so the menu
    // reads as belonging to the thing that was pressed.
    val x = (anchor.center.x - menuW / 2f)
        .coerceIn(margin, (screenW - menuW - margin).coerceAtLeast(margin))

    // Opens away from the nearer edge: above for anything in the bottom half,
    // below for anything in the top half.
    //
    // Purely "below unless it does not fit" put a bottom-row icon's menu on top
    // of the dock and down over the gesture bar -- technically on screen, and
    // covering the two things the user is most likely to hit next. Falls back
    // to the other side, and then to being pinned clear of the bottom, so the
    // menu is always whole and always reachable.
    //
    // A long press on empty space has no icon to sit beside, so that one is
    // simply centred.
    val bottomSafe = bottomSafePx
    val above = anchor.top - menuH - margin
    val below = anchor.bottom + margin
    val fitsAbove = above >= margin
    val fitsBelow = below + menuH <= screenH - bottomSafe

    val y = when {
        target is MenuTarget.Blank -> (screenH / 2f - menuH / 2f).coerceAtLeast(margin)
        anchor.center.y > screenH / 2f && fitsAbove -> above
        fitsBelow -> below
        fitsAbove -> above
        else -> (screenH - menuH - bottomSafe).coerceAtLeast(margin)
    }

    Popup(
        offset = IntOffset(x.roundToInt(), y.roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.width(232.dp),
            ) {
                Column(
                    Modifier
                        .padding(vertical = (LIST_PADDING_DP / 2).dp)
                        .then(
                            if (scrolls) Modifier.verticalScroll(rememberScrollState())
                            else Modifier
                        )
                ) {
                    // An app's own shortcuts, above its own menu.
                    //
                    // This is the thing every other launcher does on a long
                    // press and this one did not, although the query for it has
                    // been sitting in the repository the whole time. They go
                    // first because they are what the gesture is usually for --
                    // "new message", "new incognito tab" -- while the rows
                    // underneath are about the icon rather than the app.
                    if (shortcuts.isNotEmpty()) {
                        for (shortcut in shortcuts) {
                            ShortcutRow(shortcut, onDismiss)
                        }
                        Spacer(
                            Modifier
                                .padding(horizontal = 18.dp, vertical = (DIVIDER_GAP_DP / 2).dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }

                    when (target) {
                        is MenuTarget.Placed -> {
                            val item = target.item
                            if (canResize) {
                                MenuRow("Resize", Icons.Rounded.OpenInFull, onResize)
                            }
                            if (canRename) {
                                MenuRow("Rename", Icons.Rounded.DriveFileRenameOutline, onRename)
                            }
                            if (canChangeIcon) {
                                MenuRow("Change icon", Icons.Rounded.Image, onChangeIcon)
                            }
                            if (hasChosenIcon) {
                                MenuRow("Use the original icon", Icons.Rounded.Refresh, onResetIcon)
                            }
                            if (item.type != ItemType.FOLDER && item.type != ItemType.WIDGET) {
                                MenuRow("App info", Icons.Rounded.Info, onAppInfo)
                            }
                            // Named for where the icon actually is. "Remove" on
                            // a folder's child reads as removing the folder,
                            // which is the one thing it does not do -- the app
                            // comes out of the folder and everything else stays.
                            val inFolder = item.container != Container.DESKTOP &&
                                item.container != Container.DOCK
                            MenuRow(
                                if (inFolder) "Remove from folder" else "Remove",
                                Icons.Rounded.Delete,
                                onRemove,
                            )
                            if (canUninstall && item.type != ItemType.WIDGET) {
                                MenuRow("Uninstall", Icons.Rounded.Delete, onUninstall, destructive = true)
                            }
                        }

                        is MenuTarget.Drawer -> {
                            MenuRow("Add to home screen", Icons.Rounded.Home, onAddToHome)
                            MenuRow("App info", Icons.Rounded.Info, onAppInfo)
                            MenuRow("Hide from drawer", Icons.Rounded.VisibilityOff, onHide)
                            if (canUninstall) {
                                MenuRow("Uninstall", Icons.Rounded.Delete, onUninstall, destructive = true)
                            }
                        }

                        is MenuTarget.Private -> {
                            MenuRow("Add to home screen", Icons.Rounded.Home, onAddToHome)
                            MenuRow("App info", Icons.Rounded.Info, onAppInfo)
                        }

                        is MenuTarget.Blank -> {
                            MenuRow("Wallpaper", Icons.Rounded.Wallpaper, onWallpaper)
                            MenuRow("Widgets", Icons.Rounded.Widgets, onWidgets)
                            MenuRow("Edit pages", Icons.Rounded.Dashboard, onEditPages)
                            MenuRow("Launcher settings", Icons.Rounded.Settings, onSettings)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One row's height, and the padding above and below the list.
 *
 * Constants, and the row is given this height rather than being allowed to wrap
 * its content, because [ItemMenu] works out where to put itself by counting
 * rows and multiplying. A padding tweak here that the arithmetic there did not
 * know about is what put a folder's Remove button most of a screen away from
 * the folder.
 */
private const val ROW_HEIGHT_DP = 46
private const val LIST_PADDING_DP = 12

/** Space taken by the hairline under the shortcut rows, gap included. */
private const val DIVIDER_GAP_DP = 9

/**
 * One app shortcut.
 *
 * Tapping it opens the shortcut. Holding it puts it on the home screen, which
 * is the other half of what these are for and has nowhere else to live -- a
 * launcher cannot offer to pin something the publisher has not asked it to pin,
 * so this gesture is the only route to a "new message" icon of one's own.
 *
 * The bounds handed to the launch are the row's own, so the opening animation
 * grows out of the thing that was tapped rather than from the middle of the
 * screen.
 */
@Composable
private fun ShortcutRow(shortcut: AppShortcut, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_DP.dp)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .semantics(mergeDescendants = true) {
                contentDescription = shortcut.label
                role = Role.Button
                onClick(label = "Open") {
                    shortcut.start(bounds)
                    onDismiss()
                    true
                }
                onLongClick(label = "Add to home screen") {
                    shortcut.pin()
                    onDismiss()
                    true
                }
            }
            .pointerInput(shortcut.id) {
                detectLauncherGestures(
                    onClick = {
                        shortcut.start(bounds)
                        onDismiss()
                    },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        shortcut.pin()
                        onDismiss()
                    },
                )
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = shortcut.icon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                Icons.Rounded.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            shortcut.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_DP.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

/**
 * A yes-or-no, in front of everything.
 *
 * Uninstall needs one because of where it sits: at the bottom of a menu that
 * opens under the finger that opened it, one row below Remove, on a surface
 * people are already holding and dragging things around on. The system asks
 * again afterwards, but by then the launcher has already thrown the user into
 * a system screen they did not ask for, and "just dismiss it" is not much of a
 * safety net for the one action here that cannot be undone.
 *
 * Deliberately plain: a title, a line of prose, and two buttons, with the
 * destructive one coloured and *not* the default position. Nothing here is
 * worth a custom layout.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    neutralLabel: String? = null,
    onNeutral: () -> Unit = {},
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            Row {
                if (neutralLabel != null) {
                    TextButton(onClick = onNeutral) { Text(neutralLabel) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        shape = RoundedCornerShape(28.dp),
    )
}

/**
 * Give an icon a name of your own.
 *
 * An app's label is the app's, and it is often not what the person who put it
 * on their home screen would call it -- three things all called Messages, a
 * bank whose app is named after a product nobody uses, an app in a language
 * they do not read. Folders could already be renamed and nothing else could,
 * which made the ability look like an accident of folders rather than something
 * the launcher offers.
 *
 * Empty means "use the app's own name again", so the way out is the same
 * gesture as the way in rather than a separate reset row.
 */
@Composable
fun RenameDialog(
    current: String,
    original: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(current) { mutableStateOf(current) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(original) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Leave it empty to go back to \"$original\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp),
    )
}

/**
 * The strip at the top of the screen that a drag can be dropped onto.
 *
 * Only exists while something is being carried. A permanent target would be a
 * delete button sitting under the status bar at all times, which is both clutter
 * and an accident waiting to happen.
 */
@Composable
fun RemoveTarget(
    active: Boolean,
    ui: HomeUiState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = ui.isDragging,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val highlighted = active
        Row(
            Modifier
                .padding(top = 12.dp)
                .height(46.dp)
                .widthIn(min = 150.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(
                    if (highlighted) MaterialTheme.colorScheme.error.copy(alpha = 0.92f)
                    else Color.Black.copy(alpha = 0.45f)
                )
                .border(
                    1.dp,
                    Color.White.copy(alpha = if (highlighted) 0.6f else 0.22f),
                    RoundedCornerShape(23.dp),
                )
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("Remove", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A widget being carried: an outline of the space it will take.
 *
 * Deliberately not the widget itself. Re-hosting a live AppWidgetHostView under
 * the finger means a second view bound to the same widget id, and the host only
 * tracks one view per id, so the copy left behind on the page stops updating.
 * An outline of the right size is what the user actually needs anyway, which is
 * to see whether the thing will fit where they are aiming.
 */
@Composable
fun WidgetGhost(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
) {
    Box(
        Modifier
            .size(width, height)
            .padding(4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Widgets,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(28.dp),
        )
    }
}

/** The icon under the finger during a drag. */
@Composable
fun DragPreview(
    session: DragSession,
    children: List<LauncherItem>,
    settings: LauncherSettings,
    sizeDp: androidx.compose.ui.unit.Dp,
) {
    Box(Modifier.size(sizeDp * 1.1f), contentAlignment = Alignment.Center) {
        ItemIcon(
            item = session.item,
            shape = settings.iconShape,
            size = sizeDp * 1.1f,
            folderChildren = children,
        )
    }
}
