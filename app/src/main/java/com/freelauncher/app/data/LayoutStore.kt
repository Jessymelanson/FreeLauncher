package com.freelauncher.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The arranged home screen: every item, and how many pages there are.
 *
 * Stored as one JSON file rather than a database. The whole layout is a few
 * hundred records that are always read together and always written together,
 * so a database would add a schema to migrate and a query layer to go through
 * without ever answering a question that "load all of it" does not. It also
 * makes the file trivially inspectable, which matters when the interesting bug
 * is "my dock came back wrong after an import".
 *
 * Writes go through a temp file and a rename. A launcher is killed abruptly and
 * often -- that is normal operation, not a crash -- and a half-written layout
 * file is a phone with no home screen on it.
 */
class LayoutStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "layout.json")

    /** Where imported and user-set custom icons live, one PNG per [LauncherItem.iconKey]. */
    val iconDir: File = File(appContext.filesDir, "icons").apply { mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val ids = AtomicLong(1L)

    private val _items = MutableStateFlow<List<LauncherItem>>(emptyList())
    val items: StateFlow<List<LauncherItem>> = _items.asStateFlow()

    private val _screenCount = MutableStateFlow(1)
    val screenCount: StateFlow<Int> = _screenCount.asStateFlow()

    /**
     * True once [load] has finished.
     *
     * The home screen has to distinguish "no items yet because nothing is
     * loaded" from "no items because the layout really is empty" -- the second
     * should offer to set things up, the first should show nothing at all
     * rather than flashing an empty state for one frame.
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * True when there was no layout file to read.
     *
     * Distinct from "the layout is empty", which is also what someone who has
     * deliberately cleared their home screen sees. Seeding a starter dock is
     * right for the first case and insulting in the second, and the absence of
     * the file is the only thing that tells them apart -- the first save writes
     * it, so this can never be true twice.
     */
    private val _firstRun = MutableStateFlow(false)
    val firstRun: StateFlow<Boolean> = _firstRun.asStateFlow()

    /**
     * Read the layout, on the calling thread.
     *
     * Synchronous on purpose, and called from Application.onCreate. The file is
     * a few kilobytes of JSON that parses in single-digit milliseconds, and
     * loading it in the background buys nothing except a guarantee that the
     * first frame of the home screen is empty: the workspace would draw, then
     * the layout would arrive, and every icon would pop in afterwards. That
     * flash is the largest part of how slow a launcher feels on a cold start.
     *
     * Writes stay asynchronous. Those happen while the user is doing something
     * else and must never block a drag.
     */
    fun load() {
        val existed = file.exists()
        val parsed = runCatching { readFile() }.getOrElse {
            Log.e(TAG, "layout unreadable, starting empty", it)
            null
        }
        if (parsed != null) {
            _items.value = parsed.items
            _screenCount.value = parsed.screens.coerceAtLeast(1)
            ids.set(parsed.nextId.coerceAtLeast(1L))
        }
        _firstRun.value = !existed
        _loaded.value = true
    }

    fun markSeeded() {
        _firstRun.value = false
    }

    fun nextId(): Long = ids.getAndIncrement()

    // ---- mutations -------------------------------------------------------
    //
    // Every one of these funnels through mutate(), which republishes and then
    // persists. Callers never write the file themselves, so there is exactly
    // one place where "the in-memory layout and the file disagree" can happen.

    private fun mutate(transform: (List<LauncherItem>) -> List<LauncherItem>) {
        _items.value = transform(_items.value)
        persist()
    }

    fun add(item: LauncherItem) = mutate { it + item }

    fun addAll(newItems: List<LauncherItem>) = mutate { it + newItems }

    fun update(item: LauncherItem) = mutate { list ->
        list.map { if (it.id == item.id) item else it }
    }

    /**
     * Remove an item, and anything it contained.
     *
     * Deleting a folder without its children leaves records whose container
     * points at nothing: invisible, still occupying their slot in every
     * "is this cell free" check, and impossible to get rid of from the UI.
     */
    fun remove(id: Long) = mutate { list ->
        list.filterNot { it.id == id || it.container == id }
    }

    fun move(id: Long, container: Long, screen: Int, cellX: Int, cellY: Int) = mutate { list ->
        list.map {
            if (it.id == id) it.copy(container = container, screen = screen, cellX = cellX, cellY = cellY)
            else it
        }
    }

    // ---- the shared home set ---------------------------------------------
    //
    // The alternative home styles do not keep their own list of pinned apps;
    // they read this layout. These two calls are how they change it, so pinning
    // or unpinning in any style is the same act as adding to or removing from
    // the classic home screen, and every style agrees afterwards.

    /**
     * Items a person could actually see on the home screen: anything on a page or
     * in the dock, and the contents of folders that are themselves on one. A
     * child of a folder that no longer exists is not on the home screen in any
     * sense that matters, so it does not count as the app already being there.
     */
    private fun visible(list: List<LauncherItem>): List<LauncherItem> {
        val top = list.filter { it.container == Container.DESKTOP || it.container == Container.DOCK }
        val folderIds = top.filter { it.type == ItemType.FOLDER }.map { it.id }.toSet()
        return top + list.filter { it.container in folderIds }
    }

    /** True when [key] is on the home screen, anywhere. */
    fun isOnHome(key: String): Boolean = visible(_items.value).any { homeKey(it) == key }

    /**
     * Puts an app on the home screen, unless it is already there.
     *
     * Placed with [findSlot], exactly as a shortcut pinned from another app is,
     * so it lands in the first free cell and a full home screen gets a new page
     * rather than a refusal.
     */
    fun addAppToHome(entry: AppEntry, cols: Int, rows: Int): Boolean {
        if (isOnHome(entry.key)) return false
        val (screen, x, y) = findSlot(0, cols, rows, _screenCount.value)
        add(
            LauncherItem(
                id = nextId(),
                type = ItemType.APP,
                title = entry.label,
                component = entry.component.flattenToString(),
                packageName = entry.packageName,
                userSerial = entry.userSerial,
                container = Container.DESKTOP,
                screen = screen,
                cellX = x,
                cellY = y,
            ),
        )
        return true
    }

    /**
     * Takes everything with [key] off the home screen, and returns how many
     * records went.
     *
     * Every copy goes, not just one: "unpin" in a shell means the app is no
     * longer on the home screen, and leaving a duplicate on page three would mean
     * it quietly came back the next time the shells looked.
     *
     * A folder is tidied in the same step, by the rule the classic screen already
     * follows: emptied, it goes; left with one app, it dissolves and that app
     * takes its cell; otherwise its remaining apps close up. Done here, in one
     * write, rather than by the caller in several, because a folder half-tidied
     * between two writes is a folder the next reader can see broken.
     *
     * Widgets and folders are never matched - they have no key - so this cannot
     * take either off the screen.
     */
    fun removeFromHome(key: String): Int {
        val doomed = _items.value.filter {
            it.type != ItemType.FOLDER && it.type != ItemType.WIDGET && homeKey(it) == key
        }
        if (doomed.isEmpty()) return 0
        val ids = doomed.map { it.id }.toSet()
        val touchedFolders = doomed.map { it.container }.filter { it >= 0L }.toSet()

        mutate { list ->
            var next = list.filterNot { it.id in ids }
            for (folderId in touchedFolders) {
                val folder = next.firstOrNull { it.id == folderId } ?: continue
                val left = next.filter { it.container == folderId }
                    .sortedWith(compareBy({ it.cellY }, { it.cellX }))
                next = when (left.size) {
                    0 -> next.filterNot { it.id == folderId }
                    1 -> {
                        val survivor = left.first()
                        next.filterNot { it.id == folderId }.map {
                            if (it.id != survivor.id) it
                            else it.copy(
                                container = folder.container,
                                screen = folder.screen,
                                cellX = folder.cellX,
                                cellY = folder.cellY,
                            )
                        }
                    }
                    else -> {
                        val slot = left.withIndex().associate { (i, child) -> child.id to i }
                        next.map { child ->
                            val i = slot[child.id] ?: return@map child
                            child.copy(cellX = i % FOLDER_COLUMNS, cellY = i / FOLDER_COLUMNS)
                        }
                    }
                }
            }
            next
        }
        return doomed.size
    }

    /** Replace the entire layout -- used by the Nova importer. */
    fun replaceAll(newItems: List<LauncherItem>, screens: Int, nextId: Long) {
        _screenCount.value = screens.coerceAtLeast(1)
        ids.set(nextId.coerceAtLeast(1L))
        _items.value = newItems
        persist()
    }

    fun setScreenCount(count: Int) {
        _screenCount.value = count.coerceIn(1, MAX_SCREENS)
        persist()
    }

    fun addScreen(): Int {
        val index = _screenCount.value
        setScreenCount(index + 1)
        return index
    }

    /**
     * Drop a page and close the gap.
     *
     * Screens are addressed by index, not by id, so removing one in the middle
     * has to renumber everything after it in the same pass. Doing the removal
     * and the renumbering as two separate mutations would publish an
     * intermediate layout where items point at a page that no longer exists.
     */
    fun removeScreen(index: Int) {
        if (_screenCount.value <= 1) return
        val survivors = _items.value
            .filterNot { it.container == Container.DESKTOP && it.screen == index }
            .map {
                if (it.container == Container.DESKTOP && it.screen > index) it.copy(screen = it.screen - 1)
                else it
            }
        _screenCount.value = _screenCount.value - 1
        _items.value = survivors
        persist()
    }

    /**
     * Move a page to a different position, carrying its contents.
     *
     * Expressed as a permutation of the old indices rather than as a series of
     * swaps. Moving page 1 to position 3 shifts pages 2 and 3 down by one, and
     * doing that with pairwise swaps gets the order right but the intermediate
     * states wrong -- and since every state here is published to the UI, "wrong
     * intermediate state" means a visible flicker of scrambled pages.
     */
    fun movePage(from: Int, to: Int) {
        val count = _screenCount.value
        if (from == to || from !in 0 until count || to !in 0 until count) return

        val order = ArrayList<Int>(count)
        for (i in 0 until count) order += i
        order.removeAt(from)
        order.add(to, from)

        // order[newIndex] == oldIndex, so invert it to relabel items.
        val remap = HashMap<Int, Int>(count)
        order.forEachIndexed { newIndex, oldIndex -> remap[oldIndex] = newIndex }

        _items.value = _items.value.map {
            if (it.container == Container.DESKTOP) it.copy(screen = remap[it.screen] ?: it.screen) else it
        }
        persist()
    }

    /**
     * Separate any items that have ended up sharing a cell.
     *
     * A repair, not a routine. Nothing should ever write two items into one
     * square, but earlier builds of the drag code could -- a drag that ended by
     * having its page disposed under it applied a stale drop -- and the result
     * is invisible and permanent: one icon sits exactly on top of another, and
     * the one underneath can never be tapped or dragged out again.
     *
     * Run once at startup, so a layout damaged by a past version comes back
     * whole rather than needing the user to notice and rebuild it. Whoever is
     * first in the list keeps the cell, which is the one nearest the top left,
     * and everything else is reflowed into free space on the nearest page.
     */
    fun resolveOverlaps(cols: Int, rows: Int, dockCols: Int, screens: Int) {
        // An item can also be outside the grid entirely, which is not an
        // overlap and is worse than one.
        //
        // The grid size is a setting. Taking a five-column home screen down to
        // four does not move anything: the icon in column five keeps cell (4,y)
        // and is drawn one cell past the right-hand edge, where the page clips
        // it. It occupies nothing, so nothing is dropped on top of it to reveal
        // it; it is not overlapping anything, so the pass below leaves it
        // alone; and the only cell scan that runs afterwards looks at columns
        // 0..3 and never sees it. The icon is gone, and the only way back is
        // to guess that widening the grid returns it.
        //
        // Sending it through the same relocation as a collision is the whole
        // fix -- it is the same question, "this cannot stay where it is, where
        // does it go" -- so out-of-bounds items are simply moved to the origin
        // of nowhere and left for the loop to place.
        fun outOfBounds(item: LauncherItem): Boolean {
            if (item.container >= 0L) return false
            val width = if (item.container == Container.DOCK) dockCols else cols
            val height = if (item.container == Container.DOCK) 1 else rows
            return item.cellX + item.spanX > width ||
                item.cellY + item.spanY > height ||
                item.cellX < 0 || item.cellY < 0 ||
                (item.container == Container.DESKTOP && item.screen >= screens)
        }
        // Empty folders go first.
        //
        // There is no way to make one on purpose: folders are created by
        // dropping one icon onto another, so they begin with two, and taking
        // the second-to-last one out dissolves them. One with nothing in it is
        // therefore always wreckage -- and on the home screen it is an
        // unlabelled grey square that opens to say it is empty.
        val emptyFolders = _items.value
            .filter { it.type == ItemType.FOLDER && _items.value.none { child -> child.container == it.id } }
            .map { it.id }
            .toSet()
        if (emptyFolders.isNotEmpty()) {
            Log.i(TAG, "removed ${emptyFolders.size} empty folder(s)")
            _items.value = _items.value.filterNot { it.id in emptyFolders }
        }

        // And folders holding exactly one app, which are not folders either.
        //
        // The launcher already says so everywhere a folder can be emptied by
        // hand: taking the second-to-last app out dissolves the folder and puts
        // the survivor in its place, because one app wearing a folder as a hat
        // is a state with no obvious way out of it. Every one of those paths
        // enforces the rule and nothing enforced it on the way *in*.
        //
        // A Nova restore is where that shows. A folder of eight apps, six of
        // them not installed on this phone, arrives as a folder of two; one of
        // five arrives as a folder of one. The importer already drops the ones
        // that arrive empty and had no answer for this, so a restore onto a
        // fresh phone produced a home screen dotted with folders that opened to
        // reveal a single icon.
        //
        // Here rather than in the importer because it is not the importer's
        // rule: it belongs to whatever a folder is, and this runs over every
        // layout however it arrived.
        val singles = _items.value
            .filter { it.type == ItemType.FOLDER }
            .mapNotNull { folder ->
                val children = _items.value.filter { it.container == folder.id }
                if (children.size == 1) folder to children.first() else null
            }
        if (singles.isNotEmpty()) {
            Log.i(TAG, "dissolved ${singles.size} folder(s) holding one app")
            val doomed = singles.map { it.first.id }.toSet()
            val promoted = singles.associate { (folder, child) ->
                child.id to child.copy(
                    container = folder.container,
                    screen = folder.screen,
                    cellX = folder.cellX,
                    cellY = folder.cellY,
                )
            }
            _items.value = _items.value
                .filterNot { it.id in doomed }
                .map { promoted[it.id] ?: it }
        }

        val taken = HashSet<String>()
        val out = ArrayList<LauncherItem>(_items.value.size)
        var moved = 0

        fun claim(container: Long, screen: Int, x: Int, y: Int, sx: Int, sy: Int): Boolean {
            val keys = ArrayList<String>(sx * sy)
            for (dx in 0 until sx) for (dy in 0 until sy) {
                keys += "$container:$screen:${x + dx}:${y + dy}"
            }
            if (keys.any { it in taken }) return false
            taken += keys
            return true
        }

        for (item in _items.value) {
            // Folder contents are addressed by their order inside the folder,
            // not by a cell on any page, so they cannot collide.
            if (item.container >= 0L) {
                out += item
                continue
            }

            if (!outOfBounds(item) &&
                claim(item.container, item.screen, item.cellX, item.cellY, item.spanX, item.spanY)
            ) {
                out += item
                continue
            }

            // Its own page first, so a displaced icon stays where the user last
            // saw it if there is any room at all. A page that no longer exists
            // is not offered, which is what a shrunk screen count leaves
            // behind.
            val order = (listOf(item.screen) + (0 until screens))
                .distinct()
                .filter { it in 0 until screens }
            // A widget wider than the grid it now lives in has to be narrowed
            // as well as moved, or there is no block that will hold it and it
            // stays exactly where it was -- which is the thing being fixed.
            val maxW = if (item.container == Container.DOCK) dockCols else cols
            val maxH = if (item.container == Container.DOCK) 1 else rows
            val spanX = item.spanX.coerceIn(1, maxW)
            val spanY = item.spanY.coerceIn(1, maxH)

            var placed = false
            search@ for (screen in order) {
                for (y in 0..(maxH - spanY).coerceAtLeast(0)) {
                    for (x in 0..(maxW - spanX).coerceAtLeast(0)) {
                        if (claim(item.container, screen, x, y, spanX, spanY)) {
                            out += item.copy(
                                screen = screen,
                                cellX = x,
                                cellY = y,
                                spanX = spanX,
                                spanY = spanY,
                            )
                            moved++
                            placed = true
                            break@search
                        }
                    }
                }
                if (item.container == Container.DOCK) break
            }

            // Nowhere at all. Kept as it is rather than dropped: a stacked icon
            // is recoverable, a deleted one is not.
            if (!placed) out += item
        }

        if (moved > 0) {
            Log.i(TAG, "separated $moved overlapping item(s)")
            _items.value = out
        }
        if (moved > 0 || emptyFolders.isNotEmpty() || singles.isNotEmpty()) persist()
    }

    // ---- queries ---------------------------------------------------------

    /**
     * The first free block of [spanX] by [spanY] cells, scanning rows before
     * columns.
     *
     * Returns null when the page is full, which the caller has to handle by
     * trying the next page rather than by dropping the item. Widgets are
     * included in the occupancy scan via [LauncherItem.occupies], so a 4x1
     * clock does not get an app dropped on top of it -- and asking in blocks
     * rather than in single cells is what stops a five-by-three widget being
     * placed on the first free corner of an otherwise full page.
     */
    fun findFreeArea(
        container: Long,
        screen: Int,
        cols: Int,
        rows: Int,
        spanX: Int,
        spanY: Int,
    ): Pair<Int, Int>? {
        val onPage = _items.value.filter { it.container == container && it.screen == screen }
        val w = spanX.coerceIn(1, cols)
        val h = spanY.coerceIn(1, rows)
        for (y in 0..(rows - h)) {
            for (x in 0..(cols - w)) {
                val clear = (0 until w).all { dx ->
                    (0 until h).all { dy -> onPage.none { it.occupies(x + dx, y + dy) } }
                }
                if (clear) return x to y
            }
        }
        return null
    }

    /**
     * Somewhere to put a new item, as (screen, x, y).
     *
     * Tries [preferredScreen] first, then every page in order, then makes one.
     * Never fails: an item the user asked for has to land somewhere they can
     * see, and a new page is a much smaller surprise than nothing happening.
     */
    fun findSlot(
        preferredScreen: Int,
        cols: Int,
        rows: Int,
        screens: Int,
        spanX: Int = 1,
        spanY: Int = 1,
    ): Triple<Int, Int, Int> {
        findFreeArea(Container.DESKTOP, preferredScreen, cols, rows, spanX, spanY)?.let {
            return Triple(preferredScreen, it.first, it.second)
        }
        for (page in 0 until screens) {
            findFreeArea(Container.DESKTOP, page, cols, rows, spanX, spanY)?.let {
                return Triple(page, it.first, it.second)
            }
        }
        return Triple(addScreen(), 0, 0)
    }

    /** True when [spanX] by [spanY] cells at (x, y) are free, ignoring [ignoreId]. */
    fun areaIsFree(
        container: Long,
        screen: Int,
        x: Int,
        y: Int,
        spanX: Int,
        spanY: Int,
        ignoreId: Long,
    ): Boolean {
        val onPage = _items.value.filter {
            it.container == container && it.screen == screen && it.id != ignoreId
        }
        for (dx in 0 until spanX) {
            for (dy in 0 until spanY) {
                if (onPage.any { it.occupies(x + dx, y + dy) }) return false
            }
        }
        return true
    }

    // ---- persistence -----------------------------------------------------

    private fun persist() {
        val snapshot = _items.value
        val screens = _screenCount.value
        val next = ids.get()
        scope.launch {
            writeLock.withLock {
                runCatching { writeFile(snapshot, screens, next) }
                    .onFailure { Log.e(TAG, "could not save layout", it) }
            }
        }
    }

    private fun writeFile(list: List<LauncherItem>, screens: Int, nextId: Long) {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("nextId", nextId)
        root.put("screens", screens)
        val arr = JSONArray()
        for (item in list) arr.put(item.toJson())
        root.put("items", arr)

        // Write beside the target and rename over it. rename is atomic within a
        // filesystem, so a kill at any point leaves either the old file intact
        // or the new one complete, never a truncated mix of the two.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            // renameTo can fail if the destination exists on some filesystems.
            file.delete()
            if (!tmp.renameTo(file)) throw IllegalStateException("rename failed for ${file.path}")
        }
    }

    private class Parsed(val items: List<LauncherItem>, val screens: Int, val nextId: Long)

    private fun readFile(): Parsed? {
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        val arr = root.optJSONArray("items") ?: JSONArray()
        val list = ArrayList<LauncherItem>(arr.length())
        var maxId = 0L
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)?.toLauncherItem() ?: continue
            list += item
            if (item.id > maxId) maxId = item.id
        }
        // Trust the recorded counter, but never below what is actually in the
        // file: a duplicate id silently merges two items in every lookup.
        val next = maxOf(root.optLong("nextId", 1L), maxId + 1)
        return Parsed(list, root.optInt("screens", 1), next)
    }

    private companion object {
        const val TAG = "LayoutStore"
        const val VERSION = 1
        const val MAX_SCREENS = 12
    }
}

// ---- JSON mapping --------------------------------------------------------
//
// Hand-written rather than generated. It is one small object, and writing it
// out means the file format is decided here in plain sight instead of falling
// out of whatever the serialisation library does with a Kotlin default -- which
// matters because this file has to be readable by the next version of the app.

private fun LauncherItem.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("type", type.name)
    put("title", title)
    putOpt("component", component)
    putOpt("package", packageName)
    put("user", userSerial)
    putOpt("intent", intentUri)
    putOpt("shortcut", shortcutId)
    put("container", container)
    put("screen", screen)
    put("x", cellX)
    put("y", cellY)
    put("sx", spanX)
    put("sy", spanY)
    put("widgetId", widgetId)
    putOpt("widgetProvider", widgetProvider)
    putOpt("icon", iconKey)
    putOpt("chosenIcon", chosenIconKey)
}

private fun JSONObject.toLauncherItem(): LauncherItem? {
    val type = runCatching { ItemType.valueOf(getString("type")) }.getOrNull() ?: return null
    return LauncherItem(
        id = optLong("id"),
        type = type,
        title = optString("title", ""),
        component = optStringOrNull("component"),
        packageName = optStringOrNull("package"),
        userSerial = optLong("user", 0L),
        intentUri = optStringOrNull("intent"),
        shortcutId = optStringOrNull("shortcut"),
        container = optLong("container", Container.DESKTOP),
        screen = optInt("screen", 0),
        cellX = optInt("x", 0),
        cellY = optInt("y", 0),
        spanX = optInt("sx", 1).coerceAtLeast(1),
        spanY = optInt("sy", 1).coerceAtLeast(1),
        widgetId = optInt("widgetId", -1),
        widgetProvider = optStringOrNull("widgetProvider"),
        iconKey = optStringOrNull("icon"),
        chosenIconKey = optStringOrNull("chosenIcon"),
    )
}

/**
 * optString returns the literal string "null" for a JSON null, which then sails
 * through every null check downstream and ends up as a component name that
 * cannot be resolved. This returns an actual null for both cases.
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }
