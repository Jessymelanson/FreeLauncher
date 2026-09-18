package com.freelauncher.app.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.freelauncher.app.data.AppEntry
import com.freelauncher.app.data.DrawerStyle
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.ui.common.AppIcon
import com.freelauncher.app.ui.common.IconTile
import com.freelauncher.app.ui.common.detectLauncherGestures
import com.freelauncher.app.ui.theme.LocalLauncherPalette
import kotlinx.coroutines.launch

/**
 * The app drawer.
 *
 * Its own composable rather than part of the home screen because it is the one
 * surface here with a scrolling list and a text field, and both need to own the
 * gestures inside their own bounds -- the vertical drag that opens the drawer
 * has to stop applying the moment the drawer is open, or scrolling the list
 * would close it.
 */
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    settings: LauncherSettings,
    isOpen: Boolean,
    nestedScroll: NestedScrollConnection,
    modifier: Modifier = Modifier,
    onLaunch: (AppEntry, Rect) -> Unit,
    onLongPress: (AppEntry, Rect) -> Unit,

    /**
     * An app being carried out of the drawer onto the home screen.
     *
     * The second argument is where the finger is, in window coordinates, so the
     * home screen can pick the drag up exactly where the drawer let go of it.
     */
    onDragOut: (AppEntry, Offset) -> Unit,

    /**
     * Whether something is being carried right now.
     *
     * Switches the list's own scrolling off, and that is not a nicety. A
     * scrollable consumes the vertical part of every drag it is offered, and a
     * consumed change reports as no movement at all -- so the drag tracker on
     * the home screen would watch a finger that appeared to be standing still.
     * The same rule an open folder's pager follows, for the same reason.
     */
    dragging: Boolean,

    onDismiss: () -> Unit,
    /**
     * The way into private space, or null on a phone that has none.
     *
     * Null rather than a no-op, and passed all the way down to the grab handle,
     * because the gesture has to be genuinely absent when there is nothing
     * behind it. A handle that buzzes under a long press on a phone with no
     * private profile has just announced that FreeLauncher has a private space
     * feature and that this is where it lives.
     */
    onPrivateSpace: (() -> Unit)? = null,
) {
    val palette = LocalLauncherPalette.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val visible = remember(apps, settings.hiddenApps) {
        apps.filterNot { it.key in settings.hiddenApps }
    }
    val results = remember(visible, query) { search(visible, query) }

    // Closing the drawer has to clear the search as well. Leaving the previous
    // query in place means the next swipe up opens on a filtered list, which
    // reads as most of the phone's apps having disappeared.
    LaunchedEffect(isOpen) {
        if (!isOpen) {
            query = ""
            focusManager.clearFocus()
            keyboard?.hide()
        } else if (settings.drawerSearchEnabled && settings.drawerAutoKeyboard) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // Surface rather than a coloured Column, for the content colour it carries:
    // an unstyled Text resolves against LocalContentColor, which defaults to
    // black outside a Surface. Everything below happens to set its own colour
    // today, and the first one that forgets would be invisible.
    Surface(
        color = palette.drawerBackground.copy(alpha = settings.drawerOpacity),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // Everything below, including the grid, reports its unused
                // scroll up to this. That is what turns "the list is already at
                // the top" into "close the drawer".
                .nestedScroll(nestedScroll)
                .statusBarsPadding()
                .imePadding(),
        ) {
            GrabHandle(onDismiss, onPrivateSpace)

            if (settings.drawerSearchEnabled) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    focusRequester = focusRequester,
                    onSearch = {
                        // Enter on the keyboard launches the only match. With a
                        // list of one this is what the user is reaching for, and it
                        // turns the drawer into a keyboard launcher.
                        results.firstOrNull()?.let { onLaunch(it, Rect.Zero) }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }

            if (results.isEmpty()) {
                EmptyState(query)
                return@Column
            }

            // Where each letter starts, in whichever list is about to be drawn.
            //
            // Computed out here rather than inside each mode, because the rail
            // and the list have to agree on what index a letter means and the
            // two modes number their rows differently -- a list interleaves
            // headers, a grid does not. One calculation, handed to both.
            //
            // Only when nothing has been typed. An A-Z rail beside four search
            // results is pointing at an alphabet that is not there.
            val sections = remember(results, query, settings.drawerStyle) {
                if (query.isNotEmpty()) emptyList() else sectionsOf(results, settings.drawerStyle)
            }

            // Width given up to the rail, and zero when there is no rail.
            //
            // It has to be a number the grid knows about rather than something
            // drawn over the top. The drawer sizes its cells to produce the
            // column count the user asked for, and a rail laid over the last
            // column either covers icons or -- if the grid is told to keep clear
            // of it without being told to shrink its cells -- silently drops a
            // column. Four became three, which is the sort of change nobody
            // asks for and everybody notices.
            val showRail = sections.size >= MIN_SECTIONS_FOR_RAIL
            val railReserve = if (showRail) RAIL_WIDTH else 0.dp

            Box(Modifier.weight(1f)) {
                when (settings.drawerStyle) {
                    DrawerStyle.GRID -> DrawerGrid(
                        results, settings, dragging, gridState, railReserve,
                        onLaunch, onLongPress, onDragOut,
                    )

                    DrawerStyle.LIST -> DrawerList(
                        results, settings, query.isEmpty(), dragging, listState, railReserve,
                        onLaunch, onLongPress, onDragOut,
                    )
                }

                if (showRail) {
                    FastScrollRail(
                        sections = sections,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .navigationBarsPadding(),
                        onPick = { index ->
                            scope.launch {
                                runCatching {
                                    when (settings.drawerStyle) {
                                        DrawerStyle.GRID -> gridState.scrollToItem(index)
                                        DrawerStyle.LIST -> listState.scrollToItem(index)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Below this many letters the rail is not worth the width it takes.
 *
 * A phone with a dozen apps has three or four sections, and a rail of four
 * letters down the side of the screen is decoration rather than navigation --
 * the whole list is one flick away regardless.
 */
private const val MIN_SECTIONS_FOR_RAIL = 6

/** How much width the rail takes out of the list beside it. */
private val RAIL_WIDTH = 30.dp

/** Tallest slice a single letter is given before the rail stops growing. */
private const val RAIL_LETTER_MAX_DP = 26

/** A letter, and the row index it starts at. */
private class Section(val letter: Char, val index: Int)

/**
 * Where each letter begins.
 *
 * The index is a row number in the list that is actually drawn, which is why
 * the mode matters: the list interleaves a header row before each letter, so
 * every app after the first letter sits one row further down than its position
 * in the app list would suggest. Getting this wrong does not fail, it just
 * scrolls somewhere slightly wrong -- the sort of thing nobody reports and
 * everybody notices.
 */
private fun sectionsOf(apps: List<AppEntry>, style: DrawerStyle): List<Section> {
    val out = ArrayList<Section>(27)
    var letter: Char? = null
    var row = 0
    for (entry in apps) {
        val first = entry.label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
        if (first != letter) {
            letter = first
            // In list mode the header is the row to land on, and it is about to
            // be emitted at this index; in grid mode there is no header and the
            // app itself is the target.
            out += Section(first, row)
            if (style == DrawerStyle.LIST) row++
        }
        row++
    }
    return out
}

/**
 * The A-Z strip down the right-hand edge.
 *
 * Drag it rather than tap it: a rail of twenty-six letters on a phone gives
 * each one about twenty pixels, which is half a finger, so tapping accurately
 * is not a reasonable thing to ask. Dragging means the finger can land
 * anywhere and correct itself, which is how every fast scroller that people
 * actually use behaves.
 *
 * The letter under the finger is shown in a bubble to the left of it, because
 * the finger is covering the one thing the user is trying to read.
 */
@Composable
private fun FastScrollRail(
    sections: List<Section>,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var active by remember { mutableStateOf(-1) }
    var railHeight by remember { mutableFloatStateOf(0f) }

    // Held through rememberUpdatedState: the gesture block below is created
    // once and would otherwise keep the first composition's list for ever --
    // the same trap the home screen's cells fell into.
    val current by rememberUpdatedState(sections)

    fun pick(y: Float) {
        val list = current
        if (list.isEmpty() || railHeight <= 0f) return
        val slot = ((y / railHeight) * list.size).toInt().coerceIn(0, list.size - 1)
        if (slot == active) return
        active = slot
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onPick(list[slot].index)
    }

    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .width(RAIL_WIDTH)
                // As tall as its letters want, up to the height available.
                //
                // Both halves are needed. A full alphabet plus the '#' bucket
                // is twenty-seven rows, and at a large system font size that is
                // taller than a phone, so the column has to be able to compress
                // -- otherwise it runs off the bottom and takes the last few
                // letters with it, on exactly the phones where a fast scroller
                // is most wanted. But filling the height unconditionally is
                // silly for eleven letters: they end up spread the length of
                // the screen with finger-widths of nothing between them, which
                // reads as a rendering fault rather than as an index.
                //
                // The cap goes BEFORE fillMaxHeight, and that is not a matter
                // of taste. Constraints flow outwards in: fillMaxHeight fixes
                // the height to the parent's, and a heightIn placed after it is
                // asked to shrink something whose minimum is already the full
                // height, which it cannot do. Written the other way round it
                // compiles, runs, and does nothing whatsoever.
                .heightIn(max = (sections.size * RAIL_LETTER_MAX_DP).dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .onSizeChanged { railHeight = it.height.toFloat() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pick(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(change.position.y)
                        }
                        active = -1
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            sections.forEachIndexed { index, section ->
                Box(
                    // An equal slice each, which is also what the maths above
                    // assumes: pick() turns a y into an index by dividing the
                    // rail's height by the number of letters, so letters of
                    // unequal height would make the finger and the highlight
                    // disagree towards the ends.
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = section.letter.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = if (index == active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        },
                    )
                }
            }
        }

        if (active in sections.indices) {
            Box(
                Modifier
                    .offset(x = (-44).dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = sections[active].letter.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * The pill at the top of the drawer, and the way into private space.
 *
 * Its job is to say "this sheet comes down", which nothing else on the screen
 * does: the drawer fills the display, so there is no visible edge to suggest it
 * can be dismissed. Tapping it closes the drawer too, because a target that
 * looks draggable and ignores a tap is worse than no target.
 *
 * ## The hold
 *
 * Holding it opens private space. Nothing about the pill says so, and that is
 * the requirement rather than an oversight: the point of a private space is
 * that its existence is not advertised, so the door cannot be a labelled one.
 * The handle was picked over the alternatives because it is the one control on
 * this surface that already has a tap and no hold, so nothing had to be given
 * up for it, and because reaching it takes two deliberate steps -- open the
 * drawer, then hold the one thing at the very top of it.
 *
 * On a phone with no private profile the callback is null and the hold is not
 * wired at all: no buzz, no panel, no hint that the gesture exists.
 */
@Composable
private fun GrabHandle(onDismiss: () -> Unit, onPrivateSpace: (() -> Unit)?) {
    val haptics = LocalHapticFeedback.current

    // Typed, so the block below is read as the (Offset) -> Unit that
    // detectTapGestures wants rather than as a zero-argument lambda.
    val hold: ((Offset) -> Unit)? = onPrivateSpace?.let { open ->
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            open()
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            // Only the tap is declared, deliberately. Announcing a long press
            // here would announce private space -- to a screen reader, out
            // loud, on a phone that may not have one -- which is the whole
            // thing this gesture exists not to do.
            .semantics(mergeDescendants = true) {
                contentDescription = "Close app drawer"
                role = Role.Button
                onClick(label = "Close") {
                    onDismiss()
                    true
                }
            }
            .pointerInput(onPrivateSpace) {
                detectTapGestures(onTap = { onDismiss() }, onLongPress = hold)
            }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}

@Composable
private fun DrawerGrid(
    apps: List<AppEntry>,
    settings: LauncherSettings,
    dragging: Boolean,
    state: LazyGridState,
    railReserve: Dp,
    onLaunch: (AppEntry, Rect) -> Unit,
    onLongPress: (AppEntry, Rect) -> Unit,
    onDragOut: (AppEntry, Offset) -> Unit,
) {
    // Sized by cell, not by column count.
    //
    // The drawer is the one surface here that can actually use a wider screen:
    // it is a scrolling list of everything installed, so there is no stored
    // position to preserve and extra width can become extra columns. Adaptive
    // with the width one column gets in portrait produces exactly the user's
    // setting when held upright, and more columns of that same size when turned
    // -- rather than the setting's worth of very wide, very empty ones.
    val config = LocalConfiguration.current
    val portraitWidth = minOf(config.screenWidthDp, config.screenHeightDp).dp
    // The rail's width comes out before the division, so the user's column
    // count survives it: the cells get a little narrower rather than one of
    // them disappearing.
    //
    // And then a couple of dp come off, which is not fussiness. Adaptive works
    // in whole pixels: it rounds the size asked for *up* and divides the space
    // available by it, so a cell sized to fit exactly n times fits n - 1 times
    // as often as not. Four columns became three, and the arithmetic said four.
    // Asking for very slightly less than a perfect fit puts the division safely
    // above the integer, and is far too small a difference to see.
    val cell = ((portraitWidth - 16.dp - railReserve) / settings.drawerCols - 2.dp)
        .coerceAtLeast(48.dp)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(cell),
        state = state,
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp + railReserve,
            top = 4.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = !dragging,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        items(apps, key = { it.key }) { entry ->
            DrawerCell(entry, settings, onLaunch, onLongPress, onDragOut)
        }
    }
}

@Composable
private fun DrawerList(
    apps: List<AppEntry>,
    settings: LauncherSettings,
    showHeaders: Boolean,
    dragging: Boolean,
    state: LazyListState,
    railReserve: Dp,
    onLaunch: (AppEntry, Rect) -> Unit,
    onLongPress: (AppEntry, Rect) -> Unit,
    onDragOut: (AppEntry, Offset) -> Unit,
) {
    // Precomputed so the letter for a row is not recalculated on every frame
    // the list scrolls. With headers off, this collapses to a plain list.
    // Typed explicitly. Without it the headerless branch fixes the element type
    // as DrawerEntry.App and the branch that adds headers stops compiling.
    val rows: List<DrawerEntry> = remember(apps, showHeaders) {
        if (!showHeaders) {
            apps.map { DrawerEntry.App(it) }
        } else {
            buildList {
                var letter: Char? = null
                for (entry in apps) {
                    val first = entry.label.firstOrNull()?.uppercaseChar()
                        ?.takeIf { it.isLetter() } ?: '#'
                    if (first != letter) {
                        letter = first
                        add(DrawerEntry.Header(first))
                    }
                    add(DrawerEntry.App(entry))
                }
            }
        }
    }

    LazyColumn(
        state = state,
        // A row's label is ellipsised at the width it is given, so without this
        // a long app name runs underneath the rail rather than stopping short
        // of it.
        contentPadding = PaddingValues(end = railReserve, bottom = 24.dp),
        userScrollEnabled = !dragging,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        items(rows, key = { row ->
            when (row) {
                is DrawerEntry.Header -> "h:${row.letter}"
                is DrawerEntry.App -> row.entry.key
            }
        }) { row ->
            when (row) {
                is DrawerEntry.Header -> Text(
                    text = row.letter.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 4.dp),
                )

                is DrawerEntry.App ->
                    DrawerRow(row.entry, settings, onLaunch, onLongPress, onDragOut)
            }
        }
    }
}

private sealed interface DrawerEntry {
    class Header(val letter: Char) : DrawerEntry
    class App(val entry: AppEntry) : DrawerEntry
}

@Composable
internal fun DrawerCell(
    entry: AppEntry,
    settings: LauncherSettings,
    onLaunch: (AppEntry, Rect) -> Unit,
    onLongPress: (AppEntry, Rect) -> Unit,
    onDragOut: (AppEntry, Offset) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val iconSize: Dp = (52.dp * settings.iconScale).coerceIn(32.dp, 84.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .semantics(mergeDescendants = true) {
                contentDescription = entry.label
                role = Role.Button
                onClick(label = "Open") {
                    onLaunch(entry, bounds)
                    true
                }
                onLongClick(label = "Show options") {
                    onLongPress(entry, bounds)
                    true
                }
            }
            .pointerInput(entry.key) {
                detectLauncherGestures(
                    onClick = { onLaunch(entry, bounds) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(entry, bounds)
                    },
                    // Hold, then move: the app comes out of the drawer and onto
                    // the home screen under the finger. This was wired to an
                    // empty lambda, so the one gesture everybody tries first on
                    // a launcher did nothing, and the only way to place an app
                    // was a menu row that drops it in the first free cell.
                    onDragStart = { local -> onDragOut(entry, bounds.topLeft + local) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        IconTile(
            label = entry.label,
            showLabel = settings.drawerShowLabels,
            labelScale = settings.labelScale,
            // The drawer has its own opaque background, so labels here are
            // ordinary text on a surface and should not carry the wallpaper
            // shadow that home screen labels need.
            onWallpaper = false,
        ) {
            AppIcon(entry, settings.iconShape, iconSize)
        }
    }
}

@Composable
private fun DrawerRow(
    entry: AppEntry,
    settings: LauncherSettings,
    onLaunch: (AppEntry, Rect) -> Unit,
    onLongPress: (AppEntry, Rect) -> Unit,
    onDragOut: (AppEntry, Offset) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val iconSize: Dp = (40.dp * settings.iconScale).coerceIn(28.dp, 64.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .semantics(mergeDescendants = true) {
                contentDescription = entry.label
                role = Role.Button
                onClick(label = "Open") {
                    onLaunch(entry, bounds)
                    true
                }
                onLongClick(label = "Show options") {
                    onLongPress(entry, bounds)
                    true
                }
            }
            .pointerInput(entry.key) {
                detectLauncherGestures(
                    onClick = { onLaunch(entry, bounds) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(entry, bounds)
                    },
                    onDragStart = { local -> onDragOut(entry, bounds.topLeft + local) },
                )
            }
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(entry, settings.iconShape, iconSize)
        Spacer(Modifier.width(16.dp))
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun EmptyState(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = if (query.isEmpty()) "No apps to show" else "Nothing matches \"$query\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 48.dp),
        )
    }
}

// ---- searching -----------------------------------------------------------

/**
 * Rank apps against what has been typed.
 *
 * Three ways to match, in descending order of how likely the user meant it:
 * the name starts with the query, a word in the name starts with it, or the
 * initials spell it -- "gm" finding Google Maps. Plain substring is last,
 * because "ma" matching Gmail before Maps is exactly the kind of result that
 * makes people stop using a search box.
 *
 * Ties keep the incoming alphabetical order, which sortedBy preserves, so the
 * list never reshuffles between two equally good matches.
 */
private fun search(apps: List<AppEntry>, query: String): List<AppEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return apps
    return apps
        .mapNotNull { entry -> score(entry.label.lowercase(), q)?.let { entry to it } }
        .sortedBy { it.second }
        .map { it.first }
}

private fun score(label: String, query: String): Int? {
    if (label.startsWith(query)) return 0

    val words = label.split(' ', '-', '_', '.', '&')
    if (words.any { it.startsWith(query) }) return 1

    val initials = words.mapNotNull { it.firstOrNull() }.joinToString("")
    if (initials.startsWith(query)) return 2

    if (label.contains(query)) return 3

    return null
}
