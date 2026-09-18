package com.freelauncher.app.nova

import android.util.Log
import com.freelauncher.app.data.AppRepository
import com.freelauncher.app.data.Container
import com.freelauncher.app.data.FOLDER_COLUMNS
import com.freelauncher.app.data.IconCache
import com.freelauncher.app.data.IconShape
import com.freelauncher.app.data.ItemType
import com.freelauncher.app.data.LauncherItem
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.data.LayoutStore
import kotlin.math.roundToInt

/**
 * What an import actually did.
 *
 * Reported in full, including the parts that did not work. An importer that
 * silently drops a third of someone's home screen and says "Done" is worse than
 * one that fails outright, because the user finds out days later and has no way
 * to tell what was lost.
 */
class NovaImportResult(
    val apps: Int,
    val folders: Int,
    val shortcuts: Int,
    val screens: Int,
    val iconsRestored: Int,
    val missingApps: List<String>,
    val widgetsSkipped: List<String>,
    val settingsChanged: Boolean,
) {
    val restored: Int get() = apps + folders + shortcuts
    val hasWarnings: Boolean get() = missingApps.isNotEmpty() || widgetsSkipped.isNotEmpty()
}

/**
 * Turning a parsed Nova backup into a FreeLauncher layout.
 *
 * Kept apart from the reader so the two failure modes stay apart: the reader
 * fails when a file is not a Nova backup, this fails when a backup describes
 * something this launcher cannot reproduce. Only the second kind is worth
 * showing to the user item by item.
 */
class NovaImporter(
    private val layout: LayoutStore,
    private val repo: AppRepository,
    private val icons: IconCache,
) {

    /**
     * @param adoptGrid take the grid dimensions from the backup. On by default,
     *   because a layout restored into a differently shaped grid is not the
     *   layout that was backed up -- icons reflow, and the arrangement that was
     *   the whole point of taking a backup is gone.
     * @param restoreIcons write Nova's stored icon bitmaps out and use them.
     *   These are the icons as they appeared, so an icon pack's artwork comes
     *   back without the pack being installed.
     */
    fun import(
        backup: NovaBackup,
        current: LauncherSettings,
        adoptGrid: Boolean = true,
        restoreIcons: Boolean = true,
    ): Pair<NovaImportResult, LauncherSettings> {

        // Nothing can be imported before the launcher knows what is installed.
        //
        // Every app in the backup is checked against the app list and dropped
        // if it is not there. Against an empty list that is every app -- so an
        // import run a moment too early would report the entire backup as
        // missing and replace the home screen with nothing, which is both
        // destructive and completely convincing: the file really was read, and
        // the report really does list every app on it.
        check(repo.loaded.value) {
            "The list of installed apps is still loading. Try again in a moment."
        }

        val settings = if (adoptGrid) adaptSettings(backup, current) else current
        val cols = settings.desktopCols
        val rows = settings.desktopRows
        val dockCols = settings.dockCols

        var nextId = 1L
        fun newId() = nextId++

        val out = ArrayList<LauncherItem>(backup.items.size)
        val missing = LinkedHashSet<String>()
        val widgets = LinkedHashSet<String>()
        var iconCount = 0
        var appCount = 0
        var shortcutCount = 0

        // Folders first: their children address them by Nova's id, so the
        // mapping from old id to new has to exist before any child is read.
        val folderIds = HashMap<Long, Long>()
        val folderSources = backup.items.filter {
            it.itemType == NovaItem.Type.FOLDER && it.isOnHomeOrDock
        }
        for (folder in folderSources) folderIds[folder.id] = newId()

        /**
         * Nova's icon blobs are PNGs; they go in as-is.
         *
         * Stored unmodified, but not drawn unmodified: [IconCache] masks every
         * stored icon to the shape the user picked, these included. Keeping the
         * original bytes on disk is what makes that reversible -- the shape is
         * a display decision and re-deciding it must not need the backup again.
         */
        fun storeIcon(item: NovaItem): String? {
            if (!restoreIcons) return null
            val blob = item.icon ?: return null
            if (blob.size < 8) return null
            val key = icons.writeCustomIcon("${IconCache.NOVA_PREFIX}${item.id}.png", blob)
            if (key != null) iconCount++
            return key
        }

        fun place(item: NovaItem, container: Long, id: Long): LauncherItem? {
            val component = NovaIntent.componentOf(item.intent)
            val pkg = NovaIntent.packageOf(item.intent)

            return when (item.itemType) {
                NovaItem.Type.APPLICATION -> {
                    if (component == null) return null
                    if (!repo.isInstalled(component, 0L)) {
                        // Named by package, not component: the user thinks in
                        // apps, and three dead icons from one uninstalled app
                        // should read as one missing app.
                        missing += pkg ?: component
                        return null
                    }
                    appCount++
                    LauncherItem(
                        id = id,
                        type = ItemType.APP,
                        title = item.title,
                        component = component,
                        packageName = pkg,
                        container = container,
                        iconKey = storeIcon(item),
                    )
                }

                NovaItem.Type.DEEP_SHORTCUT -> {
                    val shortcutId = NovaIntent.shortcutIdOf(item.intent) ?: return null
                    if (pkg == null) return null

                    // Asked of the package, not the activity.
                    //
                    // A deep shortcut records the activity it was published
                    // from, and an app that has renamed or dropped that
                    // activity since the backup is still installed and still
                    // publishes the shortcut. Testing the component reported
                    // those as missing apps -- naming an app the user can see
                    // on their phone in a list of things that could not be
                    // restored, which is the least believable kind of wrong.
                    if (repo.entryForPackage(pkg, 0L) == null) {
                        missing += pkg
                        return null
                    }
                    shortcutCount++
                    LauncherItem(
                        id = id,
                        type = ItemType.DEEP_SHORTCUT,
                        title = item.title,
                        component = component,
                        packageName = pkg,
                        shortcutId = shortcutId,
                        container = container,
                        iconKey = storeIcon(item),
                    )
                }

                NovaItem.Type.SHORTCUT -> {
                    val intent = item.intent ?: return null
                    shortcutCount++
                    LauncherItem(
                        id = id,
                        type = ItemType.SHORTCUT,
                        title = item.title,
                        packageName = pkg,
                        intentUri = intent,
                        container = container,
                        iconKey = storeIcon(item),
                    )
                }

                NovaItem.Type.FOLDER -> LauncherItem(
                    id = id,
                    type = ItemType.FOLDER,
                    title = item.title,
                    container = container,
                )

                NovaItem.Type.WIDGET, NovaItem.Type.CUSTOM_WIDGET -> {
                    // A widget id is issued by the AppWidgetHost of the phone
                    // that created it and means nothing here -- the binding is
                    // between that host and that widget instance, and it did
                    // not come along in the backup. Re-creating the widget also
                    // cannot be done in bulk: each one needs the user to grant
                    // the bind, and some then need their own configuration
                    // activity. Named and skipped, so the user knows to place
                    // them again rather than wondering where they went.
                    item.widgetProvider?.substringBefore('/')?.let { widgets += it }
                    null
                }

                else -> null
            }
        }

        // ---- home screen and dock ----------------------------------------

        for (item in backup.items) {
            if (!item.isOnHomeOrDock) continue
            if (item.itemType == NovaItem.Type.FOLDER) continue

            val id = newId()
            val container = if (item.container == NovaItem.Containers.DOCK) Container.DOCK else Container.DESKTOP
            val placed = place(item, container, id) ?: continue

            out += if (container == Container.DOCK) {
                placed.copy(screen = 0, cellX = item.cellX.roundToInt().coerceIn(0, dockCols - 1), cellY = 0)
            } else {
                placed.copy(
                    screen = item.screen.coerceAtLeast(0),
                    cellX = item.cellX.roundToInt().coerceIn(0, cols - 1),
                    cellY = item.cellY.roundToInt().coerceIn(0, rows - 1),
                    spanX = item.spanX.roundToInt().coerceAtLeast(1),
                    spanY = item.spanY.roundToInt().coerceAtLeast(1),
                )
            }
        }

        // ---- folders and their contents ----------------------------------

        var folderCount = 0
        for (folder in folderSources) {
            val id = folderIds.getValue(folder.id)
            val container = if (folder.container == NovaItem.Containers.DOCK) Container.DOCK else Container.DESKTOP

            val children = backup.items
                .filter { it.container == folder.id }
                .sortedWith(compareBy({ it.cellY }, { it.cellX }))

            val mapped = ArrayList<LauncherItem>(children.size)
            for (child in children) {
                val childId = newId()
                val placed = place(child, id, childId) ?: continue
                mapped += placed.copy(
                    screen = 0,
                    cellX = mapped.size % FOLDER_COLUMNS,
                    cellY = mapped.size / FOLDER_COLUMNS,
                )
            }

            // An empty folder is not worth restoring. It happens when every app
            // inside it has been uninstalled since the backup, and leaving it
            // puts an empty box on the home screen that the user has to find
                // and delete by hand.
            if (mapped.isEmpty()) continue

            folderCount++
            out += LauncherItem(
                id = id,
                type = ItemType.FOLDER,
                title = folder.title,
                container = container,
                screen = if (container == Container.DOCK) 0 else folder.screen.coerceAtLeast(0),
                cellX = if (container == Container.DOCK) {
                    folder.cellX.roundToInt().coerceIn(0, dockCols - 1)
                } else {
                    folder.cellX.roundToInt().coerceIn(0, cols - 1)
                },
                cellY = if (container == Container.DOCK) 0 else folder.cellY.roundToInt().coerceIn(0, rows - 1),
            )
            out += mapped
        }

        // ---- collisions --------------------------------------------------
        //
        // Rounding a subgrid position to whole cells can land two icons on the
        // same one, and clamping into a smaller grid can too. Whoever gets
        // there first keeps the cell; the rest are reflowed into free space so
        // they stay on the home screen instead of hiding underneath each other.

        val resolved = resolveCollisions(out, cols, rows, dockCols)
        val screens = maxOf(
            backup.screenCount,
            (resolved.filter { it.container == Container.DESKTOP }.maxOfOrNull { it.screen } ?: 0) + 1,
        )

        layout.replaceAll(resolved, screens, nextId)

        val result = NovaImportResult(
            apps = appCount,
            folders = folderCount,
            shortcuts = shortcutCount,
            screens = screens,
            iconsRestored = iconCount,
            missingApps = missing.toList().sorted(),
            widgetsSkipped = widgets.toList().sorted(),
            settingsChanged = settings != current,
        )
        Log.i(TAG, "imported ${result.restored} items across $screens screens")
        return result to settings
    }

    /**
     * Give every item a cell of its own.
     *
     * Items are taken in the order they were read, which is Nova's row order,
     * so the first one to claim a cell is the one nearest the top-left -- the
     * same one a person would say was "already there".
     */
    private fun resolveCollisions(
        items: List<LauncherItem>,
        cols: Int,
        rows: Int,
        dockCols: Int,
    ): List<LauncherItem> {
        val taken = HashSet<String>()
        val out = ArrayList<LauncherItem>(items.size)

        fun claim(container: Long, screen: Int, x: Int, y: Int, sx: Int, sy: Int): Boolean {
            val keys = ArrayList<String>(sx * sy)
            for (dx in 0 until sx) for (dy in 0 until sy) {
                keys += "$container:$screen:${x + dx}:${y + dy}"
            }
            if (keys.any { it in taken }) return false
            taken += keys
            return true
        }

        for (item in items) {
            // Folder contents are addressed by order inside the folder, not by
            // a cell on any page, so they never collide with anything.
            if (item.container >= 0L) {
                out += item
                continue
            }

            val width = if (item.container == Container.DOCK) dockCols else cols
            val height = if (item.container == Container.DOCK) 1 else rows

            if (claim(item.container, item.screen, item.cellX, item.cellY, item.spanX, item.spanY)) {
                out += item
                continue
            }

            // Look for the first free cell, this page first and then later
            // pages. The dock cannot grow, so an item that will not fit there
            // moves to the home screen rather than being dropped.
            var placed = false
            var screen = item.screen
            val lastScreen = if (item.container == Container.DOCK) item.screen else item.screen + MAX_OVERFLOW_PAGES
            outer@ while (screen <= lastScreen) {
                for (y in 0 until height) for (x in 0 until width) {
                    if (claim(item.container, screen, x, y, item.spanX, item.spanY)) {
                        out += item.copy(screen = screen, cellX = x, cellY = y)
                        placed = true
                        break@outer
                    }
                }
                screen++
            }

            if (!placed && item.container == Container.DOCK) {
                var s = 0
                dock@ while (s < MAX_OVERFLOW_PAGES) {
                    for (y in 0 until rows) for (x in 0 until cols) {
                        if (claim(Container.DESKTOP, s, x, y, 1, 1)) {
                            out += item.copy(container = Container.DESKTOP, screen = s, cellX = x, cellY = y)
                            placed = true
                            break@dock
                        }
                    }
                    s++
                }
            }

            if (!placed) Log.w(TAG, "no room for ${item.title}; dropped")
        }
        return out
    }

    /**
     * Take from the backup the settings that describe its shape.
     *
     * Only the ones whose meaning is unambiguous. Nova stores a
     * `drawer_transparency` value with no indication of whether it counts up
     * from opaque or down from clear, and guessing wrong would hand the user a
     * drawer they cannot read -- so that one is left alone rather than restored
     * on a coin flip.
     */
    private fun adaptSettings(backup: NovaBackup, current: LauncherSettings): LauncherSettings {
        var next = current

        backup.grid("desktop_grid")?.let { (cols, rows) ->
            next = next.copy(
                desktopCols = cols.coerceIn(3, 10),
                desktopRows = rows.coerceIn(3, 10),
            )
        }
        backup.grid("drawer_grid")?.let { (cols, _) ->
            next = next.copy(drawerCols = cols.coerceIn(3, 8))
        }
        backup.prefs["dock_grid_cols"]?.toIntOrNull()?.let {
            next = next.copy(dockCols = it.coerceIn(2, 8), dockEnabled = it > 0)
        }
        backup.prefs["drawer_show_keyboard_by_default"]?.let {
            next = next.copy(drawerAutoKeyboard = it.toBoolean())
        }
        backup.prefs["adaptive_icon_shape"]?.let { shape ->
            val mapped = when (shape.uppercase()) {
                "CIRCLE" -> IconShape.CIRCLE
                "SQUIRCLE" -> IconShape.SQUIRCLE
                "ROUNDED_SQUARE", "ROUNDEDSQUARE", "ROUNDED" -> IconShape.ROUNDED
                "SQUARE" -> IconShape.SQUARE
                // TEARDROP, SYSTEM, and anything Nova adds later all land here.
                // The system shape is the safe answer for an unknown value:
                // it is what the rest of the phone already uses.
                else -> IconShape.SYSTEM
            }
            next = next.copy(iconShape = mapped)
        }
        return next
    }

    private companion object {
        const val TAG = "NovaImporter"

        /** How far past its own page an overflowing item may be pushed. */
        const val MAX_OVERFLOW_PAGES = 6
    }
}
