package com.freelauncher.app.ui.shells

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freelauncher.app.data.AppEntry
import com.freelauncher.app.data.HomeTile
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.data.ShellArrangement
import com.freelauncher.app.data.TileSize
import com.freelauncher.app.ui.common.IconResult
import com.freelauncher.app.ui.common.rememberAppIcon
import com.freelauncher.app.ui.common.rememberItemIcon
import kotlinx.coroutines.launch

/**
 * The Windows Phone Start screen.
 *
 * Metro as it shipped: pure black, one accent colour for every tile, hard square
 * corners, no shadow and no gradient. The only motion is the press tilt, where a
 * tile tips towards the finger.
 *
 * The tiles are the home screen's - the same apps and shortcuts every style shows
 * - and this screen keeps only their order and sizes. Tile sizes are the user's,
 * set from a tile's menu or in rearrange mode, never derived from position.
 */

private val MetroBlack = Color(0xFF000000)
private val MetroInk = Color(0xFFFFFFFF)

@Composable
fun WinPhoneShell(
    settings: LauncherSettings,
    arrangement: ShellArrangement,
    tiles: List<HomeTile>,
    apps: List<AppEntry>,
    pinnedKeys: Set<String>,
    onLaunchTile: (HomeTile, Rect) -> Unit,
    onLaunchApp: (AppEntry, Rect) -> Unit,
    onTileMenu: (HomeTile) -> Unit,
    onAppMenu: (AppEntry) -> Unit,
    onPin: (AppEntry) -> Unit,
    onUnpin: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onResize: (String, TileSize) -> Unit,
    onDragBegan: () -> Unit,
    onSecret: () -> Unit,
    onOpenSettings: () -> Unit,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
) {
    val accent = Color(settings.accent.rgb)
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    BackHandler(enabled = editing) { onEditing(false) }

    Box(Modifier.fillMaxSize().background(MetroBlack)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            if (page == 0) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (editing) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "rearranging",
                                    color = MetroInk.copy(alpha = 0.7f),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Light,
                                )
                                Text(
                                    "hold and drag to move, tap a tile to unpin or resize",
                                    color = MetroInk.copy(alpha = 0.45f),
                                    fontSize = 11.sp,
                                )
                            }
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "Done",
                                tint = MetroInk,
                                modifier = Modifier
                                    .clickable { onEditing(false) }
                                    .padding(10.dp)
                                    .size(22.dp),
                            )
                        } else {
                            if (settings.shellClock) {
                                ShellClock(color = MetroInk, onSecret = onSecret)
                            }
                            Box(Modifier.weight(1f))
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = "Rearrange tiles",
                                tint = MetroInk.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .clickable { onEditing(true) }
                                    .padding(10.dp)
                                    .size(20.dp),
                            )
                            Icon(
                                Icons.Rounded.ArrowForward,
                                contentDescription = "All apps",
                                tint = MetroInk.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .clickable { scope.launch { pager.animateScrollToPage(1) } }
                                    .padding(10.dp)
                                    .size(21.dp),
                            )
                        }
                    }

                    if (tiles.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Nothing pinned yet. Swipe left for all apps and pin the ones you use.",
                                color = MetroInk.copy(alpha = 0.6f),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    } else {
                        TileGrid(
                            tiles = tiles,
                            arrangement = arrangement,
                            settings = settings,
                            accent = accent,
                            editing = editing,
                            onLaunchTile = onLaunchTile,
                            onTileMenu = onTileMenu,
                            onUnpin = onUnpin,
                            onReorder = onReorder,
                            onResize = onResize,
                            onDragBegan = onDragBegan,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
            } else {
                AppListPage(
                    apps = apps,
                    settings = settings,
                    accent = accent,
                    pinnedKeys = pinnedKeys,
                    onLaunch = onLaunchApp,
                    onMenu = onAppMenu,
                    onSecret = onSecret,
                    showClock = settings.shellClock,
                    onPin = { entry ->
                        onPin(entry)
                        scope.launch { pager.animateScrollToPage(0) }
                    },
                )
            }
        }
    }
}

/**
 * The tile wall.
 *
 * The order being dragged is held here and only written when the finger lifts.
 * Writing on every crossing would persist a dozen intermediate arrangements for
 * one gesture.
 */
@Composable
private fun TileGrid(
    tiles: List<HomeTile>,
    arrangement: ShellArrangement,
    settings: LauncherSettings,
    accent: Color,
    editing: Boolean,
    onLaunchTile: (HomeTile, Rect) -> Unit,
    onTileMenu: (HomeTile) -> Unit,
    onUnpin: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onResize: (String, TileSize) -> Unit,
    onDragBegan: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val grid = rememberLazyGridState()
    val arranged = remember(tiles, arrangement.order) { arrangement.arrange(tiles) { it.key } }
    val byKey = remember(arranged) { arranged.associateBy { it.key } }
    val shownKeys = remember(arranged) { arranged.map { it.key } }

    // The live order during a drag.
    var order by remember { mutableStateOf(shownKeys) }

    // Which tile shows its unpin and resize corners in rearrange mode. One at a
    // time, and only after it has been tapped: with the corners on every tile,
    // a press meant to pick a small tile up landed on its unpin corner and the
    // tile vanished from the home screen, back to the app list.
    var selected by remember(editing) { mutableStateOf<String?>(null) }

    val reorder = rememberReorder(
        order = { order },
        onOrder = { order = it },
        onCommit = { onReorder(order) },
        onBegin = onDragBegan,
    )
    ReorderAutoScroll(reorder, grid)

    // Take the stored order back only when the stored order itself changes, and
    // never mid-drag. Syncing on the drag ending as well rebuilt the grid from
    // the old order for the frame between the finger lifting and the new order
    // arriving, so a tile flicked back to where it came from before settling.
    LaunchedEffect(shownKeys) {
        if (reorder.dragKey == null) order = shownKeys
    }
    val dragKey = reorder.dragKey

    // Runs of small tiles collapse into 2x2 cubes; everything else stands alone.
    // Keyed on the sizes too, or a resize changed the map but not the packing.
    val sizes = arrangement.sizes
    val slots = remember(order, byKey, sizes) {
        packTiles(order.filter { it in byKey }) { sizes[it] ?: TileSize.MEDIUM }
    }

    @Composable
    fun tile(key: String, size: TileSize, fill: Boolean) {
        val t = byKey[key] ?: return
        MetroTile(
            tile = t,
            reorder = reorder,
            settings = settings,
            accent = accent,
            size = size,
            editing = editing,
            selected = selected == key,
            dragging = key == dragKey,
            fill = fill,
            onClick = { bounds ->
                if (editing) selected = if (selected == key) null else key
                else onLaunchTile(t, bounds)
            },
            onLongPress = {
                if (editing) selected = key else onTileMenu(t)
            },
            onUnpin = {
                selected = null
                onUnpin(key)
            },
            onResize = { onResize(key, size.next()) },
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = grid,
        modifier = Modifier.fillMaxSize().reorderableContainer(reorder),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (slot in slots) {
            when (slot) {
                is TileSlot.Single -> item(span = { GridItemSpan(slot.size.span) }, key = slot.key) {
                    tile(slot.key, slot.size, fill = false)
                }

                // Four smalls in the footprint of one medium, which is how the
                // phone packed them. As separate grid items they would spread
                // along a row instead, because a lazy grid sizes every row by its
                // tallest cell and a small would be stretched to a medium's height.
                is TileSlot.Cube -> item(span = { GridItemSpan(2) }, key = "cube_" + slot.keys.first()) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (row in 0 until 2) {
                                Row(
                                    Modifier.fillMaxWidth().weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    for (col in 0 until 2) {
                                        val k = slot.keys.getOrNull(row * 2 + col)
                                        Box(Modifier.weight(1f).fillMaxHeight()) {
                                            if (k != null) tile(k, TileSize.SMALL, fill = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!editing) {
            item(span = { GridItemSpan(2) }, key = "__settings") {
                SettingsTile(accent = accent, onClick = onOpenSettings)
            }
        }
        item(span = { GridItemSpan(4) }, key = "__pad") {
            Box(Modifier.navigationBarsPadding())
        }
    }
}

/** A placed tile, or a square of up to four small ones. */
private sealed interface TileSlot {
    data class Single(val key: String, val size: TileSize) : TileSlot
    data class Cube(val keys: List<String>) : TileSlot
}

/**
 * Groups runs of small tiles into 2x2 cubes.
 *
 * Four smalls occupy exactly one medium's footprint on a Windows Phone, and they
 * fill it corner to corner rather than running along a row. A lazy grid cannot
 * express that on its own: it sizes each row by the tallest cell in it, so a
 * single-column small placed beside a medium is stretched to the medium's height
 * and the pairing is lost. Collapsing each run into one double-width cell that
 * lays out its own 2x2 puts the geometry back.
 */
private fun packTiles(order: List<String>, sizeOf: (String) -> TileSize): List<TileSlot> {
    val out = ArrayList<TileSlot>()
    var run = ArrayList<String>()

    fun flush() {
        if (run.isEmpty()) return
        for (chunk in run.chunked(4)) out.add(TileSlot.Cube(chunk))
        run = ArrayList()
    }

    for (key in order) {
        val size = sizeOf(key)
        if (size == TileSize.SMALL) {
            run.add(key)
        } else {
            flush()
            out.add(TileSlot.Single(key, size))
        }
    }
    flush()
    return out
}

/**
 * One tile.
 *
 * The press tilt is the one piece of motion Metro allowed itself. In rearrange
 * mode the tile sits back slightly instead, and the tapped one gains its two
 * corners: unpin at the top right and resize at the bottom right, which is where
 * the phone put them.
 *
 * [fill] is set for a small inside a cube, where the parent has already decided
 * the size and an aspect ratio of its own would fight it.
 */
@Composable
private fun MetroTile(
    tile: HomeTile,
    reorder: ReorderState,
    settings: LauncherSettings,
    accent: Color,
    size: TileSize,
    editing: Boolean,
    selected: Boolean,
    dragging: Boolean,
    fill: Boolean,
    onClick: (Rect) -> Unit,
    onLongPress: () -> Unit,
    onUnpin: () -> Unit,
    onResize: () -> Unit,
) {
    val icon by rememberItemIcon(tile.item, settings.iconShape)
    var bounds by remember { mutableStateOf(Rect()) }
    var pressed by remember { mutableStateOf(false) }

    val tilt by animateFloatAsState(
        targetValue = if (pressed && !editing && !dragging) 6f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "metro-tilt",
    )
    val lift by animateFloatAsState(
        targetValue = when {
            dragging -> 1.06f
            editing && !selected -> 0.95f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "metro-lift",
    )

    Box(
        Modifier
            .then(
                if (fill) Modifier.fillMaxSize()
                else Modifier.fillMaxWidth().aspectRatio(if (size == TileSize.WIDE) 2.06f else 1f),
            )
            .graphicsLayer {
                rotationX = tilt
                scaleX = lift
                scaleY = lift
                alpha = if (dragging) 0.85f else 1f
                cameraDistance = 14f * density
            }
            .background(accent)
            .onGloballyPositioned { bounds = it.boundsInWindow().toLaunchBounds() }
            // Watches the press for the tilt without taking part in the gesture.
            // It lets go as soon as anything else claims the finger, so a scroll
            // that started on a tile does not leave it tipped for its duration.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.none { it.pressed }) break
                        if (event.changes.any { it.isConsumed && it.positionChanged() }) break
                    }
                    pressed = false
                }
            }
            .homeTile(
                state = reorder,
                key = tile.key,
                onClick = { onClick(bounds) },
                onLongPress = onLongPress,
            ),
    ) {
        val bmp: ImageBitmap? = (icon as? IconResult.Ready)?.bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (size == TileSize.SMALL) 26.dp else if (size == TileSize.WIDE) 44.dp else 38.dp),
            )
        }
        if (size != TileSize.SMALL) {
            Text(
                tile.label,
                color = MetroInk,
                fontSize = 11.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            )
        }

        if (editing && selected) {
            TileCorner(Alignment.TopEnd, "Unpin", onUnpin) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = MetroInk, modifier = Modifier.size(14.dp))
            }
            TileCorner(Alignment.BottomEnd, "Resize", onResize) {
                Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = MetroInk, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileCorner(
    alignment: Alignment,
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .align(alignment)
            .padding(3.dp)
            .size(26.dp)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SettingsTile(accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tilt by animateFloatAsState(
        targetValue = if (pressed) 6f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "metro-tilt-settings",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                rotationX = tilt
                cameraDistance = 14f * density
            }
            .background(accent.copy(alpha = 0.55f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Icon(
            Icons.Rounded.Settings,
            contentDescription = null,
            tint = MetroInk,
            modifier = Modifier.align(Alignment.Center).size(34.dp),
        )
        Text(
            "settings",
            color = MetroInk,
            fontSize = 11.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp),
        )
    }
}

/**
 * The app list: every app, pinned or not.
 *
 * Plain black, one row per app, a square accent letter before each run. Pinning
 * never removes an app from here; a pinned app just carries a lit pin. Tapping
 * the pin on an unpinned app puts it on Start, and a long press offers to pin or
 * unpin either way.
 */
@Composable
private fun AppListPage(
    apps: List<AppEntry>,
    settings: LauncherSettings,
    accent: Color,
    pinnedKeys: Set<String>,
    onLaunch: (AppEntry, Rect) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onSecret: () -> Unit,
    showClock: Boolean,
    onPin: (AppEntry) -> Unit,
) {
    val sorted = remember(apps) { apps.sortedBy { it.label.lowercase() } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "all apps",
                color = MetroInk,
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.weight(1f),
            )
            if (showClock) {
                ShellClock(color = MetroInk, onSecret = onSecret)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 10.dp, top = 6.dp, bottom = 30.dp),
        ) {
            var last: Char? = null
            for (entry in sorted) {
                val initial = entry.label.firstOrNull()?.uppercaseChar() ?: '#'
                val letter = if (initial.isLetter()) initial else '#'
                if (letter != last) {
                    last = letter
                    item(key = "l_$letter") {
                        Box(
                            Modifier
                                .padding(top = 16.dp, bottom = 6.dp)
                                .size(30.dp)
                                .background(accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                letter.lowercaseChar().toString(),
                                color = MetroInk,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                }
                item(key = "a_${entry.key}") {
                    MetroListRow(
                        entry = entry,
                        settings = settings,
                        accent = accent,
                        pinned = entry.key in pinnedKeys,
                        onLaunch = onLaunch,
                        onMenu = onMenu,
                        onPin = { onPin(entry) },
                    )
                }
            }
            item { Box(Modifier.navigationBarsPadding()) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MetroListRow(
    entry: AppEntry,
    settings: LauncherSettings,
    accent: Color,
    pinned: Boolean,
    onLaunch: (AppEntry, Rect) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onPin: () -> Unit,
) {
    val icon by rememberAppIcon(entry, settings.iconShape)
    var bounds by remember { mutableStateOf(Rect()) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .onGloballyPositioned { bounds = it.boundsInWindow().toLaunchBounds() }
                .combinedClickable(
                    onClick = { onLaunch(entry, bounds) },
                    onLongClick = { onMenu(entry) },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bmp: ImageBitmap? = icon
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(30.dp))
            } else {
                Box(Modifier.size(30.dp))
            }
            Text(
                entry.label,
                color = MetroInk,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        Icon(
            Icons.Rounded.PushPin,
            contentDescription = if (pinned) "On Start" else "Pin to Start",
            tint = if (pinned) accent else MetroInk.copy(alpha = 0.30f),
            modifier = Modifier
                .clickable(enabled = !pinned, onClick = onPin)
                .padding(10.dp)
                .size(17.dp),
        )
    }
}
