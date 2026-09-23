package com.freelauncher.app.ui.shells

import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freelauncher.app.data.AppEntry
import com.freelauncher.app.data.HomeTile
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.data.ShellArrangement
import com.freelauncher.app.launcher
import com.freelauncher.app.ui.common.IconResult
import com.freelauncher.app.ui.common.rememberAppIcon
import com.freelauncher.app.ui.common.rememberItemIcon
import kotlinx.coroutines.launch

/**
 * The Windows 11 Start panel.
 *
 * Rounded instead of square, centred instead of cornered, one soft translucent
 * surface rather than coloured tiles, and a lot of space left deliberately
 * empty. Pinned is six across on a desktop and four here, labels under the
 * icons, Recommended below, a user row on the bottom edge.
 *
 * Pinned is the home screen - the same apps and shortcuts every style shows -
 * paged sideways the way Start pages it. This panel keeps only their order.
 *
 * Every control does the thing it is drawn as, and every icon is a vector from
 * the Material set, never a Unicode character: the power symbol U+23FB is absent
 * from the fonts Android ships and drew as an empty box.
 */

/** The pinned grid is four across, and a tile is about this tall with its label. */
private const val W11PinColumns = 4
private val W11TileHeight = 78.dp

private val W11Panel = Color(0xFF2B2B2B)
private val W11Ink = Color(0xFFFFFFFF)
private val W11Sub = Color(0xFFA0A0A0)
private val W11Accent = Color(0xFF4CC2FF)
private val W11Field = Color(0xFF353535)
private val W11Hover = Color(0xFF3A3A3A)

/** A Recommended entry, with the reason it is there. */
private data class Suggestion(val entry: AppEntry, val why: String)

/**
 * What to put under Recommended.
 *
 * Recently opened, taken from the launcher's own record of what it started.
 * Android can report which apps a person has been using through
 * UsageStatsManager, but only behind the PACKAGE_USAGE_STATS special access,
 * which grants sight of every app on the phone however it was opened. Asking for
 * that to fill one panel would trade away the thing this launcher is, and would
 * make the permission table in its README untrue.
 *
 * Before anything has been opened there is nothing to report, so it falls back
 * to the newest installs and says so. Each row states which of the two it is,
 * because a panel that shows one thing under the label of another is the kind of
 * detail nobody checks and everybody would be misled by.
 */
@Composable
private fun rememberSuggestions(apps: List<AppEntry>, recentKeys: List<String>, count: Int): List<Suggestion> {
    val pm = LocalContext.current.packageManager
    return remember(apps, recentKeys, count) {
        val byKey = apps.associateBy { it.key }
        val opened = recentKeys.mapNotNull { byKey[it] }.take(count).map { Suggestion(it, "Recently opened") }
        if (opened.size >= count) return@remember opened

        val already = opened.map { it.entry.key }.toSet()
        val installed = apps.asSequence()
            .filter { it.key !in already }
            .distinctBy { it.packageName }
            .mapNotNull { entry ->
                val at = runCatching {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(entry.packageName, 0).firstInstallTime
                }.getOrNull() ?: return@mapNotNull null
                entry to at
            }
            .sortedByDescending { it.second }
            .take(count - opened.size)
            .map { Suggestion(it.first, "Recently added") }
            .toList()

        opened + installed
    }
}

@Composable
fun Win11Shell(
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
    onDragBegan: () -> Unit,
    onSecret: () -> Unit,
    onOpenSettings: () -> Unit,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
) {
    val app = LocalContext.current.launcher
    val scope = rememberCoroutineScope()

    val arranged = remember(tiles, arrangement.order) { arrangement.arrange(tiles) { it.key } }
    val byKey = remember(arranged) { arranged.associateBy { it.key } }
    val shownKeys = remember(arranged) { arranged.map { it.key } }

    // The live order during a drag, written back only when the finger lifts.
    var order by remember { mutableStateOf(shownKeys) }

    // Which tile shows its unpin corner in rearrange mode: only the one tapped.
    // With a corner on every tile, a press meant to pick an icon up could land on
    // it, and the app left the home screen instead of moving.
    var selected by remember(editing) { mutableStateOf<String?>(null) }

    val reorder = rememberReorder(
        order = { order },
        onOrder = { order = it },
        onCommit = { onReorder(order) },
        onBegin = onDragBegan,
    )
    // Only when the stored order itself changes, and never mid-drag. Syncing on
    // the drag ending too redrew the old order for the frame between the finger
    // lifting and the new order arriving, so a tile flicked back before settling.
    LaunchedEffect(shownKeys) {
        if (reorder.dragKey == null) order = shownKeys
    }
    val dragKey = reorder.dragKey

    BackHandler(enabled = editing) { onEditing(false) }
    val all = remember(apps) { apps.sortedBy { it.label.lowercase() } }
    val recentKeys by app.recents.keys.collectAsState()
    val recent = rememberSuggestions(apps, recentKeys, 2)

    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    val results = remember(query, all) {
        val q = query.trim()
        if (q.isEmpty()) emptyList() else all.filter { it.label.contains(q, ignoreCase = true) }
    }
    val searching = query.isNotBlank()

    Box(Modifier.fillMaxSize()) {
        MicaBackground()

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(W11Panel.copy(alpha = 0.93f)),
        ) {
            // ---- search, which actually searches ----------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(W11Field)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = W11Sub, modifier = Modifier.size(17.dp))
                Box(Modifier.weight(1f).padding(start = 9.dp)) {
                    if (query.isEmpty()) {
                        Text("Search for apps", color = W11Sub, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = W11Ink, fontSize = 13.sp),
                        cursorBrush = SolidColor(W11Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (searching) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = W11Sub,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { query = ""; keyboard?.hide() }
                            .padding(4.dp)
                            .size(16.dp),
                    )
                }
            }

            // ---- section header ---------------------------------------------
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            searching -> "${results.size} result${if (results.size == 1) "" else "s"}"
                            editing -> "Rearranging"
                            showAll -> "All apps"
                            else -> "Pinned"
                        },
                        color = W11Ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (editing) {
                        Text(
                            "Hold and drag to move. Tap an icon to unpin it.",
                            color = W11Sub,
                            fontSize = 10.sp,
                        )
                    }
                }
                if (editing) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(W11Accent.copy(alpha = 0.22f))
                            .clickable { onEditing(false) }
                            .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Done", color = W11Ink, fontSize = 12.sp)
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = W11Ink,
                            modifier = Modifier.padding(start = 3.dp).size(15.dp),
                        )
                    }
                } else if (!searching) {
                    if (!showAll && arranged.isNotEmpty()) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = "Rearrange pinned",
                            tint = W11Sub,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onEditing(true) }
                                .padding(6.dp)
                                .size(16.dp),
                        )
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(W11Hover)
                            .clickable { showAll = !showAll }
                            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (showAll) "Back" else "All apps", color = W11Ink, fontSize = 12.sp)
                        Icon(
                            if (showAll) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = W11Ink,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    searching && results.isEmpty() -> {
                        Text(
                            "No apps match \"${query.trim()}\"",
                            color = W11Sub,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    // Every app, pinned or not. Pinning never takes one out of
                    // this list; a pinned app just carries a lit pin.
                    searching || showAll -> {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            items(if (searching) results else all, key = { it.key }) { entry ->
                                W11ListRow(
                                    entry = entry,
                                    settings = settings,
                                    pinned = entry.key in pinnedKeys,
                                    onLaunch = onLaunchApp,
                                    onMenu = onAppMenu,
                                    onPin = { onPin(entry) },
                                )
                            }
                        }
                    }

                    // Nothing pinned is a real state, not one to paper over with
                    // filler tiles that belong to no arrangement and so cannot be
                    // moved. An empty Start that says how to fill itself is honest.
                    arranged.isEmpty() -> {
                        Column(
                            Modifier.align(Alignment.Center).padding(horizontal = 30.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Nothing pinned yet",
                                color = W11Ink,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Open All apps and pin what you use. Anything pinned here can be dragged into the order you want.",
                                color = W11Sub,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Row(
                                Modifier
                                    .padding(top = 16.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(W11Hover)
                                    .clickable { showAll = true; query = "" }
                                    .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("All apps", color = W11Ink, fontSize = 12.sp)
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = W11Ink,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    else -> {
                        val shown = order.mapNotNull { byKey[it] }

                        // Pinned pages sideways rather than running off the
                        // bottom, which is how Start behaves. Rows are measured
                        // rather than assumed, so a short panel drops to fewer rows
                        // per page instead of clipping the last one.
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val rows = (maxHeight / W11TileHeight).toInt().coerceIn(2, 6)
                            val perPage = W11PinColumns * rows
                            val pages = shown.chunked(perPage).ifEmpty { listOf(emptyList()) }
                            val pager = rememberPagerState(pageCount = { pages.size })

                            // Unpinning can empty the last page out from under the
                            // reader, so come back into range when it does.
                            LaunchedEffect(pages.size) {
                                if (pager.currentPage > pages.lastIndex) {
                                    pager.scrollToPage(pages.lastIndex)
                                }
                            }

                            Row(Modifier.fillMaxSize()) {
                                HorizontalPager(
                                    state = pager,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .reorderableContainer(reorder),
                                    beyondViewportPageCount = 1,
                                ) { page ->
                                    val here = pages.getOrElse(page) { emptyList() }
                                    Column(
                                        Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        for (row in 0 until rows) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                for (col in 0 until W11PinColumns) {
                                                    val tile = here.getOrNull(row * W11PinColumns + col)
                                                    Box(Modifier.weight(1f)) {
                                                        if (tile != null) {
                                                            W11PinTile(
                                                                tile = tile,
                                                                reorder = reorder,
                                                                settings = settings,
                                                                editing = editing,
                                                                selected = selected == tile.key,
                                                                dragging = tile.key == dragKey,
                                                                onClick = { bounds ->
                                                                    if (editing) {
                                                                        selected = if (selected == tile.key) null else tile.key
                                                                    } else {
                                                                        onLaunchTile(tile, bounds)
                                                                    }
                                                                },
                                                                onLongPress = {
                                                                    if (editing) selected = tile.key else onTileMenu(tile)
                                                                },
                                                                onUnpin = {
                                                                    selected = null
                                                                    onUnpin(tile.key)
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                ReorderPageTurn(reorder, pager, pages.size)

                                if (pages.size > 1) {
                                    Column(
                                        Modifier.fillMaxHeight().padding(start = 2.dp, end = 4.dp),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        for (i in pages.indices) {
                                            val current = i == pager.currentPage
                                            Box(
                                                Modifier
                                                    .padding(vertical = 3.dp)
                                                    .size(if (current) 7.dp else 5.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (current) W11Ink else W11Sub.copy(alpha = 0.45f),
                                                    )
                                                    .clickable {
                                                        scope.launch { pager.animateScrollToPage(i) }
                                                    },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- Recommended, from the launcher's own launch record -----------
            if (!searching && !showAll && recent.isNotEmpty()) {
                Text(
                    "Recommended",
                    color = W11Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (s in recent) {
                        Box(Modifier.weight(1f)) {
                            W11Recommended(s.entry, s.why, settings, onLaunchApp, onAppMenu)
                        }
                    }
                }
            }

            // ---- the bottom row ----------------------------------------------
            //
            // Both controls do what they are drawn as: the left opens the app
            // list, the right opens settings and is drawn as a gear.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { showAll = true; query = "" }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(W11Accent.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Apps,
                            contentDescription = null,
                            tint = Color(0xFF0C2C42),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("All apps", color = W11Ink, fontSize = 13.sp)
                        Text("${all.size} installed", color = W11Sub, fontSize = 10.sp)
                    }
                }

                if (settings.shellClock) {
                    ShellClock(color = W11Ink, onSecret = onSecret, timeSize = 18.sp, dateSize = 10.sp)
                }

                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "Launcher settings",
                    tint = W11Ink,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenSettings)
                        .padding(8.dp)
                        .size(19.dp),
                )
            }
        }
    }
}

/**
 * One pinned item: icon with its label underneath, as Windows 11 draws them.
 *
 * Apps and website shortcuts alike, drawn from the home screen's own record so a
 * shortcut shows its site's icon rather than the browser's.
 */
@Composable
private fun W11PinTile(
    tile: HomeTile,
    reorder: ReorderState,
    settings: LauncherSettings,
    editing: Boolean,
    selected: Boolean,
    dragging: Boolean,
    onClick: (Rect) -> Unit,
    onLongPress: () -> Unit,
    onUnpin: () -> Unit,
) {
    val icon by rememberItemIcon(tile.item, settings.iconShape)
    var bounds by remember { mutableStateOf(Rect()) }

    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .then(
                    when {
                        dragging -> Modifier.background(Color.White.copy(alpha = 0.14f))
                        editing && selected -> Modifier.background(W11Accent.copy(alpha = 0.18f))
                        else -> Modifier
                    },
                )
                .onGloballyPositioned { bounds = it.boundsInWindow().toLaunchBounds() }
                .homeTile(
                    state = reorder,
                    key = tile.key,
                    onClick = { onClick(bounds) },
                    onLongPress = onLongPress,
                )
                .padding(vertical = 10.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val bmp: ImageBitmap? = (icon as? IconResult.Ready)?.bitmap
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(34.dp))
            } else {
                Box(Modifier.size(34.dp))
            }
            Text(
                tile.label,
                color = W11Ink,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 7.dp).fillMaxWidth(),
            )
        }

        if (editing && selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(onClickLabel = "Unpin", onClick = onUnpin),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = W11Ink, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A Recommended card. The second line says which kind of recent this is. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun W11Recommended(
    entry: AppEntry,
    why: String,
    settings: LauncherSettings,
    onLaunch: (AppEntry, Rect) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    val icon by rememberAppIcon(entry, settings.iconShape)
    var bounds by remember { mutableStateOf(Rect()) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .onGloballyPositioned { bounds = it.boundsInWindow().toLaunchBounds() }
            .combinedClickable(
                onClick = { onLaunch(entry, bounds) },
                onLongClick = { onMenu(entry) },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp: ImageBitmap? = icon
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(start = 9.dp)) {
            Text(
                entry.label,
                color = W11Ink,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(why, color = W11Sub, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun W11ListRow(
    entry: AppEntry,
    settings: LauncherSettings,
    pinned: Boolean,
    onLaunch: (AppEntry, Rect) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onPin: () -> Unit,
) {
    val icon by rememberAppIcon(entry, settings.iconShape)
    var bounds by remember { mutableStateOf(Rect()) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(5.dp))
                .onGloballyPositioned { bounds = it.boundsInWindow().toLaunchBounds() }
                .combinedClickable(
                    onClick = { onLaunch(entry, bounds) },
                    onLongClick = { onMenu(entry) },
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bmp: ImageBitmap? = icon
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(26.dp))
            } else {
                Box(Modifier.size(26.dp))
            }
            Text(
                entry.label,
                color = W11Ink,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Icon(
            Icons.Rounded.PushPin,
            contentDescription = if (pinned) "Pinned" else "Pin to Start",
            tint = if (pinned) W11Accent else W11Sub.copy(alpha = 0.55f),
            modifier = Modifier
                .clickable(enabled = !pinned, onClick = onPin)
                .padding(9.dp)
                .size(15.dp),
        )
    }
}

/**
 * Mica: the wallpaper blurred and tinted, not a colour.
 *
 * There is no wallpaper bitmap to sample here, so this is the soft blue wash
 * Mica resolves to over a default Windows 11 desktop, drifting slowly the way
 * the real material does as the desktop behind it changes.
 */
@Composable
private fun MicaBackground() {
    val transition = rememberInfiniteTransition(label = "mica")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(21_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "mica-drift",
    )

    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF0A2540),
                    Color(0xFF123A63),
                    Color(0xFF1B4C7E),
                    Color(0xFF0E2A47),
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
        )
        val c1 = Offset(size.width * (0.22f + 0.10f * drift), size.height * 0.26f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF3C8FD6).copy(alpha = 0.40f), Color.Transparent),
                center = c1,
                radius = size.minDimension * 0.95f,
            ),
            radius = size.minDimension * 0.95f,
            center = c1,
        )
        val c2 = Offset(size.width * (0.82f - 0.08f * drift), size.height * 0.78f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF6FD0E8).copy(alpha = 0.22f), Color.Transparent),
                center = c2,
                radius = size.minDimension * 0.75f,
            ),
            radius = size.minDimension * 0.75f,
            center = c2,
        )
    }
}
