package com.freelauncher.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.freelauncher.app.data.Container
import com.freelauncher.app.data.ItemType
import com.freelauncher.app.data.LauncherItem
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.ui.common.IconTile
import com.freelauncher.app.ui.common.ItemIcon
import com.freelauncher.app.ui.common.detectLauncherGestures
import com.freelauncher.app.ui.theme.LocalLauncherPalette
import kotlin.math.roundToInt

/** A row shorter than this has no room for an icon and a line of text. */
private const val LABEL_MIN_ROW = 58

/** Below this an icon is a dot, so the grid gives up rows before it goes lower. */
private const val MIN_ICON = 28

/**
 * The pages of icons.
 *
 * A HorizontalPager rather than a hand-rolled scroller: it already does the
 * settling physics, the over-scroll and the accessibility page semantics, and
 * all three are the kind of thing that looks fine until someone flicks hard.
 */
@Composable
fun Workspace(
    pagerState: PagerState,
    items: List<LauncherItem>,
    settings: LauncherSettings,
    ui: HomeUiState,
    widgetHost: LauncherWidgetHost,
    modifier: Modifier = Modifier,
    onLaunch: (LauncherItem, Rect) -> Unit,
    onOpenFolder: (LauncherItem, Rect) -> Unit,
    onLongPress: (LauncherItem, Rect) -> Unit,
    onBlankLongPress: (Int) -> Unit,
    onDragBegin: (DragSession, Offset) -> Unit,
    onResize: (LauncherItem, Int, Int) -> Unit,
    showEmptyHint: Boolean,
    onReplaceWidget: (LauncherItem) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        // A drag means the finger is carrying an icon; the page must only
        // change because the drag reached the edge, never because the carried
        // icon wandered sideways. Resizing is the same argument.
        userScrollEnabled = !ui.isDragging && ui.resizing == null,
        beyondViewportPageCount = 1,
    ) { page ->
        WorkspacePage(
            page = page,
            items = items,
            settings = settings,
            ui = ui,
            widgetHost = widgetHost,
            onLaunch = onLaunch,
            onOpenFolder = onOpenFolder,
            onLongPress = onLongPress,
            onBlankLongPress = onBlankLongPress,
            onDragBegin = onDragBegin,
            onResize = onResize,
            showEmptyHint = showEmptyHint,
            onReplaceWidget = onReplaceWidget,
        )
    }
}

@Composable
private fun WorkspacePage(
    page: Int,
    items: List<LauncherItem>,
    settings: LauncherSettings,
    ui: HomeUiState,
    widgetHost: LauncherWidgetHost,
    onLaunch: (LauncherItem, Rect) -> Unit,
    onOpenFolder: (LauncherItem, Rect) -> Unit,
    onLongPress: (LauncherItem, Rect) -> Unit,
    onBlankLongPress: (Int) -> Unit,
    onDragBegin: (DragSession, Offset) -> Unit,
    onResize: (LauncherItem, Int, Int) -> Unit,
    showEmptyHint: Boolean,
    onReplaceWidget: (LauncherItem) -> Unit,
) {
    val cols = settings.desktopCols
    val rows = settings.desktopRows
    val onPage = remember(items, page) {
        items.filter { it.container == Container.DESKTOP && it.screen == page }
    }

    // The width this screen would have in portrait, whichever way it is held.
    //
    // Cells are sized against that rather than against the width actually
    // available, so turning the phone sideways does not make every cell twice
    // as wide. The grid keeps its portrait proportions and the extra width
    // becomes margin either side -- which is the only thing it can become,
    // since the number of columns is the user's setting and an item's position
    // is stored as a cell in that grid.
    val config = LocalConfiguration.current
    val portraitWidth = minOf(config.screenWidthDp, config.screenHeightDp).dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cellW: Dp = minOf(maxWidth, portraitWidth) / cols
        val cellH: Dp = maxHeight / rows

        // Icons are sized from the cell, not from a fixed dp value, so a 4x4
        // grid gets large icons and an 8x8 grid gets small ones without the
        // user having to adjust the icon size to match every grid change.
        //
        // Driven by the cell's *width*, with the height only used as a ceiling
        // to leave room for the label. Taking the smaller of the two outright,
        // as this did first, makes width win twice on a phone -- cells are much
        // taller than they are wide, so a six-column grid produced 38dp icons
        // in a 68dp-wide cell, with a third of the cell as dead margin.
        // A label needs a row tall enough to put a line of text under an icon,
        // and on a short screen there is not room for both.
        //
        // Turned sideways, the same number of rows has to fit in a third of the
        // height, so a row came out around 47dp: an icon held at its 32dp floor
        // with a label drawn under it, the pair of them taller than the row, and
        // the next row's icons drawn straight over the text. Dropping the label
        // gives the row back to the icon, which then comes out larger than it
        // was rather than smaller.
        val roomForLabel = cellH >= LABEL_MIN_ROW.dp
        val showLabels = settings.showDesktopLabels && roomForLabel
        val labelRoom = if (showLabels) 20.dp else 4.dp

        // What is actually left for the icon, and a hard ceiling rather than a
        // suggestion. The old floor of 32dp was applied last and so could raise
        // the icon back above the space available, which is what let it overrun
        // the row at all.
        val fits = (cellH - labelRoom).coerceAtLeast(MIN_ICON.dp)
        val roomiest = minOf(cellW * 0.96f, fits)
        val iconSize = (minOf(cellW * 0.74f, fits) * settings.iconScale)
            // The scale slider may make icons bigger, but never wider than the
            // cell that holds them: past that, neighbouring icons overlap and
            // the grid stops looking like a grid.
            .coerceAtMost(roomiest)
            .coerceIn(MIN_ICON.dp, 96.dp)

        // Blank-space gestures, as a sibling underneath the icons rather than
        // as a modifier on the page itself.
        //
        // A parent and its child both receive the same pointer, and both of
        // these detectors fire their long press at the same timeout -- so with
        // this on the page, pressing an icon opened the icon's menu and then
        // immediately had it overwritten by the wallpaper's. Overlapping
        // siblings deliver to the topmost only, so as a sibling it sees exactly
        // the presses that missed every icon, which is what it is for.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(page) {
                    detectLauncherGestures(
                        // A tap on bare wallpaper leaves any resize mode. There
                        // is no other obvious way out of it, and a mode you
                        // cannot see how to leave is worse than no mode at all.
                        onClick = { ui.resizing = null },
                        onLongPress = { onBlankLongPress(page) },
                        onDragStart = {},
                    )
                }
        )

        // The grid itself, centred in whatever width there is.
        //
        // A box of its own rather than an inset added to every offset, because
        // the drop maths reads pageBounds and divides by the column count. Give
        // it the full width and every drop in landscape lands a column or two
        // off; give it the grid's own bounds and the same arithmetic is right
        // in both orientations with nothing to keep in step.
        Box(
            Modifier
                .width(cellW * cols)
                .fillMaxHeight()
                .align(Alignment.Center)
                .onGloballyPositioned {
                    // Only the page the user is on can be a drop target, and
                    // only its bounds are worth recording. Pages either side
                    // are composed too (beyondViewportPageCount), and letting
                    // them write here would leave the drop maths pointing at
                    // whichever one happened to measure last.
                    if (page == ui.currentPage) ui.pageBounds = it.boundsInWindow()
                },
        ) {
            DropHighlight(ui, page, cellW, cellH, Container.DESKTOP)

            // Only while the whole layout is empty, not merely this page. An
            // empty page three of four is a deliberate spacer and does not need
            // telling the user how a launcher works.
            if (showEmptyHint && onPage.isEmpty()) {
                EmptyHint(Modifier.align(Alignment.Center))
            }

            for (item in onPage) {
                key(item.id) {
                    val cellModifier = Modifier
                        .offset(x = cellW * item.cellX, y = cellH * item.cellY)
                        .size(cellW * item.spanX, cellH * item.spanY)

                    if (item.type == ItemType.WIDGET) {
                        WidgetHolder(
                            item = item,
                            ui = ui,
                            widgetHost = widgetHost,
                            cellW = cellW,
                            cellH = cellH,
                            cols = cols,
                            rows = rows,
                            padding = settings.widgetPadding.dp,
                            modifier = cellModifier,
                            onLongPress = onLongPress,
                            onDragBegin = onDragBegin,
                            onResize = onResize,
                            onReplaceWidget = onReplaceWidget,
                        )
                    } else {
                        WorkspaceCell(
                            item = item,
                            items = items,
                            settings = settings,
                            ui = ui,
                            iconSize = iconSize,
                            // showLabels, not the raw setting.
                            //
                            // The two differ on a short row, where there is no
                            // space for a line of text under an icon. The room
                            // was worked out above and spent on the icon, and
                            // then the label was drawn anyway -- so turned
                            // sideways, every row's labels were drawn over the
                            // icons of the row beneath.
                            showLabel = showLabels,
                            modifier = cellModifier,
                            onLaunch = onLaunch,
                            onOpenFolder = onOpenFolder,
                            onLongPress = onLongPress,
                            onDragBegin = onDragBegin,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One icon in one cell.
 *
 * The cell is the touch target, not the icon: a 48dp icon in a 72dp cell leaves
 * a ring of dead space that reads as the launcher ignoring taps.
 */
@Composable
fun WorkspaceCell(
    item: LauncherItem,
    items: List<LauncherItem>,
    settings: LauncherSettings,
    ui: HomeUiState,
    iconSize: Dp,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
    onLaunch: (LauncherItem, Rect) -> Unit,
    onOpenFolder: (LauncherItem, Rect) -> Unit,
    onLongPress: (LauncherItem, Rect) -> Unit,
    onDragBegin: (DragSession, Offset) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val children = remember(items, item.id) {
        if (item.type == ItemType.FOLDER) items.filter { it.container == item.id } else emptyList()
    }

    // The item as it is now, not as it was when the detector was built.
    //
    // pointerInput is keyed on the id, so its block is created once and kept
    // for as long as this cell holds the same item -- and the lambdas inside it
    // capture whatever `item` was at that moment. Everything else about the
    // item can change underneath that: its title, its spans, the icon somebody
    // just chose for it. The record is replaced, the id is not, so the detector
    // is never rebuilt and goes on handing out the old copy.
    //
    // It showed up as a long-press menu that had not noticed a change made
    // seconds earlier -- an icon with a custom picture on it whose menu still
    // offered no way to put the original back, because the item the menu was
    // given still said there was no custom picture.
    val current by rememberUpdatedState(item)

    // The icon being carried is drawn by the overlay, following the finger.
    // Leaving it drawn here as well would show two of it.
    val carried = ui.drag?.item?.id == item.id
    val alpha by animateFloatAsState(if (carried) 0f else 1f, label = "carried")

    // Spoken, and operable, by a screen reader.
    //
    // Every gesture in this launcher is hand-written pointer input, for reasons
    // the gesture detector explains at length -- and pointer input carries no
    // semantics at all. So a screen reader found a label and nothing else: no
    // role, no actions, and on a home screen with labels switched off, nothing
    // whatsoever. The launcher was the one app on the phone that could not be
    // operated without sight, which for a home screen means the phone could not
    // be operated at all.
    //
    // Declared as semantics rather than by adding `clickable`, which would put
    // a second pointer-input node on the same element -- the exact conflict
    // detectLauncherGestures exists to avoid. These actions are what TalkBack
    // invokes; the pointer path is untouched.
    val spoken = when {
        item.type == ItemType.FOLDER -> "Folder: ${item.title.ifEmpty { "unnamed" }}"
        item.title.isNotEmpty() -> item.title
        else -> "Unlabelled icon"
    }

    Box(
        modifier
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .alpha(alpha)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                role = Role.Button
                onClick(label = if (item.type == ItemType.FOLDER) "Open folder" else "Open") {
                    if (current.type == ItemType.FOLDER) {
                        onOpenFolder(current, bounds)
                    } else {
                        onLaunch(current, bounds)
                    }
                    true
                }
                onLongClick(label = "Show options") {
                    onLongPress(current, bounds)
                    true
                }
            }
            .pointerInput(item.id) {
                detectLauncherGestures(
                    onClick = {
                        if (current.type == ItemType.FOLDER) {
                            onOpenFolder(current, bounds)
                        } else {
                            onLaunch(current, bounds)
                        }
                    },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(current, bounds)
                    },
                    onDragStart = { local ->
                        onDragBegin(
                            DragSession(current),
                            bounds.topLeft + local,
                        )
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        IconTile(
            label = if (item.type == ItemType.FOLDER) item.title.ifEmpty { "Folder" } else item.title,
            showLabel = showLabel,
            labelScale = settings.labelScale,
            onWallpaper = true,
        ) {
            ItemIcon(
                item = item,
                shape = settings.iconShape,
                size = iconSize,
                folderChildren = children,
            )
        }
    }
}

/**
 * A widget in its cells, with the resize frame when that mode is on.
 *
 * Separate from [WorkspaceCell] because a widget is a live view from another
 * process and must not be wrapped in a gesture detector that eats its taps --
 * the content underneath is interactive, and a widget whose buttons do nothing
 * is worse than no widget. So only a long press is intercepted here, and the
 * rest of the pointer stream passes straight through to the hosted view.
 */
@Composable
private fun WidgetHolder(
    item: LauncherItem,
    ui: HomeUiState,
    widgetHost: LauncherWidgetHost,
    cellW: Dp,
    cellH: Dp,
    cols: Int,
    rows: Int,
    padding: Dp,
    modifier: Modifier = Modifier,
    onLongPress: (LauncherItem, Rect) -> Unit,
    onDragBegin: (DragSession, Offset) -> Unit,
    onResize: (LauncherItem, Int, Int) -> Unit,
    onReplaceWidget: (LauncherItem) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val carried = ui.drag?.item?.id == item.id
    val resizing = ui.resizing == item.id

    // See WorkspaceCell. It matters more here: resizing a widget replaces the
    // record several times while the finger is still down, so a detector built
    // on the first version would carry the original spans for the rest of the
    // gesture and drag a five-cell widget as a one-cell one.
    val current by rememberUpdatedState(item)

    // A widget whose binding no longer resolves draws as a placeholder, and a
    // tap on that should offer to place a new one. A live widget handles its
    // own taps, so anything reaching here is a tap on its background and must
    // be ignored.
    val widgetInfo = remember(item.widgetId) {
        runCatching {
            android.appwidget.AppWidgetManager.getInstance(context).getAppWidgetInfo(item.widgetId)
        }.getOrNull()
    }
    val missing = widgetInfo == null

    // What the widget allows. A widget that declares it cannot be resized in a
    // direction gets no handle for it, and none can be shrunk below the size its
    // author said it needs - below that it does not get smaller, it clips.
    val density = LocalDensity.current.density
    val resizeMode = widgetInfo?.resizeMode ?: android.appwidget.AppWidgetProviderInfo.RESIZE_BOTH
    val (minSpanX, minSpanY) = remember(widgetInfo, cellW, cellH, density) {
        widgetInfo?.let { widgetMinSpan(it, cellW, cellH, density) } ?: (1 to 1)
    }

    Box(
        modifier
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .alpha(if (carried) 0f else 1f)
            // Only the long press is declared. A widget is another app's views
            // and brings its own semantics with it; claiming the click here
            // would hide the widget's own buttons from a screen reader, which
            // is the one thing on a home screen that has any.
            .semantics {
                onLongClick(label = "Show widget options") {
                    onLongPress(current, bounds)
                    true
                }
            }
            .pointerInput(item.id, missing) {
                detectLauncherGestures(
                    onClick = { if (missing) onReplaceWidget(current) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(current, bounds)
                    },
                    onDragStart = { local ->
                        onDragBegin(
                            DragSession(current),
                            bounds.topLeft + local,
                        )
                    },
                )
            }
    ) {
        WidgetCell(
            host = widgetHost,
            widgetId = item.widgetId,
            provider = item.widgetProvider,
            // The size the widget is actually given, after the margin. Telling
            // it the whole cell block while drawing it inside a smaller one made
            // it lay out for space it did not have, and its edges were cut off.
            widthDp = (cellW * item.spanX - padding * 2).coerceAtLeast(1.dp),
            heightDp = (cellH * item.spanY - padding * 2).coerceAtLeast(1.dp),
            // The same margin on every widget, from the setting.
            modifier = Modifier.fillMaxSize().padding(padding),
            onReplace = { onReplaceWidget(current) },
        )

        if (resizing) {
            ResizeFrame(
                item = item,
                cellW = cellW,
                cellH = cellH,
                cols = cols,
                rows = rows,
                minSpanX = minSpanX,
                minSpanY = minSpanY,
                horizontal = resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
                vertical = resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
                onResize = onResize,
            )
        }
    }
}

/**
 * Drag handles on the right and bottom edges of a widget.
 *
 * Two handles rather than four. Growing up or left means moving the widget's
 * origin as well as its size, and doing both from one drag makes it very easy
 * to shove a widget off the top of the page; the same result is reachable by
 * moving the widget first and then growing it, which is harder to do by
 * accident.
 *
 * A handle only appears for a direction the widget allows, and a widget never
 * shrinks below [minSpanX] by [minSpanY].
 */
@Composable
private fun BoxScope.ResizeFrame(
    item: LauncherItem,
    cellW: Dp,
    cellH: Dp,
    cols: Int,
    rows: Int,
    minSpanX: Int,
    minSpanY: Int,
    horizontal: Boolean,
    vertical: Boolean,
    onResize: (LauncherItem, Int, Int) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val cellWpx = with(density) { cellW.toPx() }
    val cellHpx = with(density) { cellH.toPx() }

    // The widget as it is now, read at each step of a drag. The handles'
    // gestures are keyed only on which widget this is: keying them on its span,
    // as they once were, restarted the gesture the moment the first step changed
    // the span, so every drag grew or shrank the widget by one cell and stopped.
    val current by rememberUpdatedState(item)

    Box(
        Modifier
            .fillMaxSize()
            .padding(2.dp)
            .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
    )

    // Accumulated in pixels and converted to whole cells as it crosses each
    // boundary, rather than recomputed from the start position. Accumulating
    // is what makes a slow drag across two cells produce two steps instead of
    // rounding back to one.
    var accX by remember(item.id) { mutableFloatStateOf(0f) }
    var accY by remember(item.id) { mutableFloatStateOf(0f) }

    // Inside the widget's bounds, not hanging off its edge. A touch outside a
    // parent's bounds never reaches the child, so the outer part of a handle
    // that overhung the edge was drawn but could not be grabbed.
    if (horizontal) {
        ResizeHandle(Modifier.align(Alignment.CenterEnd), onEnd = { accX = 0f }) { amount ->
            accX += amount.x
            val steps = (accX / cellWpx).roundToInt()
            if (steps != 0) {
                val now = current
                val maxX = cols - now.cellX
                val next = (now.spanX + steps).coerceIn(minSpanX.coerceAtMost(maxX), maxX)
                if (next != now.spanX) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onResize(now, next - now.spanX, 0)
                }
                accX -= steps * cellWpx
            }
        }
    }

    if (vertical) {
        ResizeHandle(Modifier.align(Alignment.BottomCenter), onEnd = { accY = 0f }) { amount ->
            accY += amount.y
            val steps = (accY / cellHpx).roundToInt()
            if (steps != 0) {
                val now = current
                val maxY = rows - now.cellY
                val next = (now.spanY + steps).coerceIn(minSpanY.coerceAtMost(maxY), maxY)
                if (next != now.spanY) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onResize(now, 0, next - now.spanY)
                }
                accY -= steps * cellHpx
            }
        }
    }
}

/** A round grab handle with a finger-sized target around it. */
@Composable
private fun ResizeHandle(modifier: Modifier, onEnd: () -> Unit, onDrag: (Offset) -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    // A drag that ends resets the leftover fraction, so the next one starts
    // clean rather than half a cell along.
    val end by rememberUpdatedState(onEnd)
    Box(
        modifier
            .size(44.dp)
            // Claimed from the system's edge gestures. A widget in the right-hand
            // column puts this handle inside the back-swipe zone, and with gesture
            // navigation a drag on it was taken as Back: resize mode closed under
            // the finger and the widget could not be made narrower at all.
            .systemGestureExclusion()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { change, amount ->
                    change.consume()
                    drag(amount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape),
        )
    }
}

/**
 * The outline showing where a dragged icon would land.
 *
 * Drawn under the icons so a drop onto an occupied cell still shows what is
 * already there -- that occupant is about to become the other half of a new
 * folder, and hiding it would make the outcome a surprise.
 */
@Composable
private fun DropHighlight(
    ui: HomeUiState,
    page: Int,
    cellW: Dp,
    cellH: Dp,
    container: Long,
) {
    val hint = ui.dropHint
    val palette = LocalLauncherPalette.current
    if (hint !is DropTarget.Cell) return
    if (hint.container != container || hint.screen != page) return

    Box(
        Modifier
            .offset(x = cellW * hint.x, y = cellH * hint.y)
            .size(cellW, cellH)
            .padding(4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.glass.copy(alpha = 0.22f))
            .border(1.dp, palette.glassBorder, RoundedCornerShape(16.dp))
    )
}

/**
 * The one piece of instruction in the whole launcher.
 *
 * Shown on a home screen that has nothing on it yet, and gone the moment the
 * first icon is placed, so it can never become clutter. Two lines, because
 * these are the only two gestures that are not obvious by looking: everything
 * else the launcher does is reached from one of them.
 */
@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Swipe up for all your apps",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Hold anywhere for wallpaper, widgets and settings",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/** The dots under the workspace. */
@Composable
fun PageIndicator(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 1) return
    Row(
        modifier
            .padding(vertical = 6.dp)
            // One announcement for the row, rather than a screen reader
            // stopping on each of four anonymous dots.
            .semantics(mergeDescendants = true) {
                contentDescription = "Page ${current + 1} of $count"
                onClick(label = "Manage pages") { false }
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val active = i == current
            val size by animateFloatAsState(if (active) 7f else 5f, label = "dot")
            Box(
                Modifier
                    .size(size.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (active) 0.95f else 0.4f))
            )
        }
    }
}
