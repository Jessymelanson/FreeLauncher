package com.freelauncher.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * FreeLauncher's own backup file.
 *
 * A ZIP, for the same reason Nova's is: the layout, the settings and the custom
 * icons are three different kinds of thing, and the icons are binary. Writing
 * it in the same shape as the format this app already reads also means one set
 * of code paths gets exercised by both, rather than the export being the half
 * nobody tries until they need it.
 *
 * Deliberately not encrypted and not compressed beyond ZIP's own deflate. There
 * are no secrets in a home screen layout -- it is a list of apps the user has
 * installed and where they put them -- and a backup you cannot open on a
 * computer to see what went wrong is a backup you cannot trust.
 */
class BackupManager(
    context: Context,
    private val layout: LayoutStore,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext

    class Summary(val items: Int, val screens: Int, val icons: Int)

    /** `FreeLauncher-2026-09-14.flbackup` */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        return "FreeLauncher-$stamp$EXTENSION"
    }

    // ---- writing ---------------------------------------------------------

    fun export(uri: Uri): Result<Summary> = runCatching {
        val items = layout.items.value
        val screens = layout.screenCount.value
        var iconCount = 0

        val out = appContext.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Could not open that location for writing.")

        out.use { stream ->
            ZipOutputStream(stream.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(
                    JSONObject().apply {
                        put("format", FORMAT_VERSION)
                        put("app", "FreeLauncher")
                        put("created", System.currentTimeMillis())
                        put("items", items.size)
                        put("screens", screens)
                    }.toString().toByteArray()
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(LAYOUT))
                zip.write(layoutJson(items, screens).toString().toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(SETTINGS))
                zip.write(settingsJson(settings.value).toString().toByteArray())
                zip.closeEntry()

                // Only the icons something actually points at. The directory
                // accumulates orphans over time -- every removed item leaves
                // one -- and copying those into every backup forever is how a
                // 200 KB file becomes a 40 MB one.
                val wanted = items.flatMap { listOfNotNull(it.iconKey, it.chosenIconKey) }.toSet()
                for (name in wanted) {
                    val file = File(layout.iconDir, name)
                    if (!file.exists()) continue
                    zip.putNextEntry(ZipEntry("$ICON_DIR/$name"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    iconCount++
                }
            }
        }
        Summary(items.size, screens, iconCount)
    }.onFailure { Log.e(TAG, "export failed", it) }

    // ---- reading ---------------------------------------------------------

    fun import(uri: Uri): Result<Summary> = runCatching {
        var layoutJson: JSONObject? = null
        var settingsJson: JSONObject? = null
        var iconCount = 0

        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open that file.")

        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    when {
                        entry.name == LAYOUT -> layoutJson = JSONObject(zip.readBytes().decodeToString())
                        entry.name == SETTINGS -> settingsJson = JSONObject(zip.readBytes().decodeToString())
                        entry.name.startsWith("$ICON_DIR/") -> {
                            // Flattened to the bare name so an entry called
                            // ../../databases/x cannot escape the icon folder.
                            val name = File(entry.name).name
                            if (name.isNotEmpty()) {
                                File(layout.iconDir, name).writeBytes(zip.readBytes())
                                iconCount++
                            }
                        }
                    }
                }
            }
        }

        val parsedLayout = layoutJson
            ?: throw IllegalStateException("That file is not a FreeLauncher backup.")

        settingsJson?.let { json -> settings.update { applySettings(it, json) } }

        val arr = parsedLayout.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<LauncherItem>(arr.length())
        var maxId = 0L
        for (i in 0 until arr.length()) {
            val item = readItem(arr.optJSONObject(i) ?: continue) ?: continue
            items += item
            if (item.id > maxId) maxId = item.id
        }
        val screens = parsedLayout.optInt("screens", 1).coerceAtLeast(1)
        layout.replaceAll(items, screens, maxId + 1)

        Summary(items.size, screens, iconCount)
    }.onFailure { Log.e(TAG, "import failed", it) }

    // ---- mapping ---------------------------------------------------------
    //
    // Written out by hand and kept here rather than shared with LayoutStore's
    // own reader. They look like duplication and are not: this one has to stay
    // able to read files written by older versions of the app forever, while
    // the store's is free to change whenever the on-device format does.

    private fun layoutJson(items: List<LauncherItem>, screens: Int) = JSONObject().apply {
        put("screens", screens)
        put("items", JSONArray().apply { items.forEach { put(writeItem(it)) } })
    }

    private fun writeItem(item: LauncherItem) = JSONObject().apply {
        put("id", item.id)
        put("type", item.type.name)
        put("title", item.title)
        putOpt("component", item.component)
        putOpt("package", item.packageName)
        put("user", item.userSerial)
        putOpt("intent", item.intentUri)
        putOpt("shortcut", item.shortcutId)
        put("container", item.container)
        put("screen", item.screen)
        put("x", item.cellX)
        put("y", item.cellY)
        put("sx", item.spanX)
        put("sy", item.spanY)
        putOpt("icon", item.iconKey)
        putOpt("chosenIcon", item.chosenIconKey)
        // widgetId is deliberately absent. It identifies a binding held by the
        // AppWidgetHost of the phone that wrote the file and is meaningless
        // anywhere else -- restoring it would produce widgets that look placed
        // and can never draw. The provider is kept so the placeholder can at
        // least say which widget is missing.
        putOpt("widgetProvider", item.widgetProvider)
    }

    private fun readItem(json: JSONObject): LauncherItem? {
        val type = runCatching { ItemType.valueOf(json.getString("type")) }.getOrNull() ?: return null
        return LauncherItem(
            id = json.optLong("id"),
            type = type,
            title = json.optString("title", ""),
            component = json.stringOrNull("component"),
            packageName = json.stringOrNull("package"),
            userSerial = json.optLong("user", 0L),
            intentUri = json.stringOrNull("intent"),
            shortcutId = json.stringOrNull("shortcut"),
            container = json.optLong("container", Container.DESKTOP),
            screen = json.optInt("screen", 0),
            cellX = json.optInt("x", 0),
            cellY = json.optInt("y", 0),
            spanX = json.optInt("sx", 1).coerceAtLeast(1),
            spanY = json.optInt("sy", 1).coerceAtLeast(1),
            widgetId = -1,
            widgetProvider = json.stringOrNull("widgetProvider"),
            iconKey = json.stringOrNull("icon"),
            chosenIconKey = json.stringOrNull("chosenIcon"),
        )
    }

    private fun settingsJson(s: LauncherSettings) = JSONObject().apply {
        put("themeMode", s.themeMode.name)
        put("accent", s.accent.name)
        put("desktopCols", s.desktopCols)
        put("desktopRows", s.desktopRows)
        put("iconScale", s.iconScale.toDouble())
        put("labelScale", s.labelScale.toDouble())
        put("showDesktopLabels", s.showDesktopLabels)
        put("showPageIndicator", s.showPageIndicator)
        put("wallpaperDim", s.wallpaperDim.toDouble())
        put("hideStatusBar", s.hideStatusBar)
        put("defaultPage", s.defaultPage)
        put("dockEnabled", s.dockEnabled)
        put("dockCols", s.dockCols)
        put("dockShowLabels", s.dockShowLabels)
        put("dockBackground", s.dockBackground)
        put("drawerStyle", s.drawerStyle.name)
        put("drawerCols", s.drawerCols)
        put("drawerOpacity", s.drawerOpacity.toDouble())
        put("drawerShowLabels", s.drawerShowLabels)
        put("drawerSearchEnabled", s.drawerSearchEnabled)
        put("drawerAutoKeyboard", s.drawerAutoKeyboard)
        put("iconShape", s.iconShape.name)
        put("swipeDownAction", s.swipeDownAction.name)
        put("hiddenApps", JSONArray().apply { s.hiddenApps.forEach { put(it) } })
        put("iconPack", s.iconPack)
        put("notificationDots", s.notificationDots)

        // The private space arrangement, which was being left out.
        //
        // It is a preference like any other and the export promises "every
        // setting", but it is also the one that is most annoying to rebuild:
        // hidden apps can be re-hidden from a list, whereas an order has to be
        // dragged back into place one app at a time. The keys survive a restore
        // onto another phone or not depending on whether the same apps are in
        // that phone's private space, and an unknown key is simply an app that
        // sorts to the end -- which is what it would have done anyway.
        put("privateOrder", JSONArray().apply { s.privateOrder.forEach { put(it) } })
    }

    /**
     * Each field falls back to what is already set, not to the app default.
     *
     * A backup written by an older version simply will not have the newer keys,
     * and resetting those to defaults would mean restoring a layout silently
     * undoes unrelated settings the user has since chosen.
     */
    private fun applySettings(current: LauncherSettings, json: JSONObject) = current.copy(
        themeMode = json.enumOr("themeMode", current.themeMode),
        accent = json.enumOr("accent", current.accent),
        desktopCols = json.optInt("desktopCols", current.desktopCols).coerceIn(3, 10),
        desktopRows = json.optInt("desktopRows", current.desktopRows).coerceIn(3, 10),
        iconScale = json.optDouble("iconScale", current.iconScale.toDouble()).toFloat(),
        labelScale = json.optDouble("labelScale", current.labelScale.toDouble()).toFloat(),
        showDesktopLabels = json.optBoolean("showDesktopLabels", current.showDesktopLabels),
        showPageIndicator = json.optBoolean("showPageIndicator", current.showPageIndicator),
        wallpaperDim = json.optDouble("wallpaperDim", current.wallpaperDim.toDouble()).toFloat(),
        hideStatusBar = json.optBoolean("hideStatusBar", current.hideStatusBar),
        defaultPage = json.optInt("defaultPage", current.defaultPage).coerceAtLeast(0),
        dockEnabled = json.optBoolean("dockEnabled", current.dockEnabled),
        dockCols = json.optInt("dockCols", current.dockCols).coerceIn(2, 8),
        dockShowLabels = json.optBoolean("dockShowLabels", current.dockShowLabels),
        dockBackground = json.optBoolean("dockBackground", current.dockBackground),
        drawerStyle = json.enumOr("drawerStyle", current.drawerStyle),
        drawerCols = json.optInt("drawerCols", current.drawerCols).coerceIn(3, 8),
        drawerOpacity = json.optDouble("drawerOpacity", current.drawerOpacity.toDouble()).toFloat(),
        drawerShowLabels = json.optBoolean("drawerShowLabels", current.drawerShowLabels),
        drawerSearchEnabled = json.optBoolean("drawerSearchEnabled", current.drawerSearchEnabled),
        drawerAutoKeyboard = json.optBoolean("drawerAutoKeyboard", current.drawerAutoKeyboard),
        iconShape = json.enumOr("iconShape", current.iconShape),
        swipeDownAction = json.enumOr("swipeDownAction", current.swipeDownAction),
        hiddenApps = json.optJSONArray("hiddenApps")?.let { arr ->
            buildSet { for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotEmpty() }?.let(::add) }
        } ?: current.hiddenApps,
        iconPack = json.optString("iconPack", current.iconPack),
        notificationDots = json.optBoolean("notificationDots", current.notificationDots),
        privateOrder = json.optJSONArray("privateOrder")?.let { arr ->
            buildList { for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotEmpty() }?.let(::add) }
        } ?: current.privateOrder,
    )

    companion object {
        private const val TAG = "BackupManager"
        private const val FORMAT_VERSION = 1
        private const val MANIFEST = "manifest.json"
        private const val LAYOUT = "layout.json"
        private const val SETTINGS = "settings.json"
        private const val ICON_DIR = "icons"

        const val EXTENSION = ".flbackup"

        /**
         * Offered to the file picker.
         *
         * A made-up extension has no registered MIME type, so the system would
         * otherwise refuse to show the file at all when picking one to restore.
         * Octet-stream is the honest answer for "some bytes" and is what makes
         * the file selectable.
         */
        const val MIME = "application/octet-stream"
    }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

private inline fun <reified E : Enum<E>> JSONObject.enumOr(key: String, fallback: E): E {
    val raw = stringOrNull(key) ?: return fallback
    return runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)
}
