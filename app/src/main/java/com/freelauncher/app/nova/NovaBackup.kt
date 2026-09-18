package com.freelauncher.app.nova

import android.content.ComponentName
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Reading a `.novabackup` file.
 *
 * The format is not documented anywhere, so this is written against a real
 * backup rather than against a specification. What is in one:
 *
 *   nova.xml      the launcher's own preferences, in Android's SharedPreferences
 *                 XML dialect -- grid sizes, gestures, icon shape
 *   nova.db       SQLite. The interesting table is `favorites`, which is
 *                 AOSP Launcher3's schema with Nova's own columns added on the
 *                 end, plus `drawer_groups` and `appgroups` for drawer tabs
 *   cards.datastore, supportDetails.txt, and a second preferences file
 *                 none of which describe the home screen
 *
 * The whole thing is an ordinary ZIP, so none of it needs Nova installed to
 * read, and nothing here talks to Nova or to the network.
 */
class NovaBackupReader(context: Context) {

    private val appContext = context.applicationContext

    class NovaFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Extract and parse.
     *
     * The database is copied out to the cache directory first because
     * SQLiteDatabase can only open a real path -- there is no way to hand it a
     * stream, and the source is a content:// Uri from the system file picker
     * that has no path at all.
     */
    fun read(uri: Uri): NovaBackup {
        val work = File(appContext.cacheDir, "nova-import").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val entries = extract(uri, work)
            val db = entries["nova.db"]
                ?: throw NovaFormatException(
                    "This file does not contain nova.db, so it is not a Nova backup."
                )
            val prefs = entries["nova.xml"]?.let { parsePrefs(it) } ?: emptyMap()
            val (items, groups) = readDatabase(db)
            return NovaBackup(items = items, groups = groups, prefs = prefs)
        } finally {
            // The database copy is the user's home screen layout and there is
            // no reason for it to outlive the import by even a minute.
            work.deleteRecursively()
        }
    }

    private fun extract(uri: Uri, into: File): Map<String, File> {
        val out = HashMap<String, File>()
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: throw NovaFormatException("Could not open the selected file.")
        try {
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name

                    // Zip Slip: an entry named ../../databases/x escapes the
                    // destination and overwrites app files. The backup comes
                    // from the user's own phone, but it arrives as an arbitrary
                    // file chosen in a picker, and a path check costs nothing.
                    val target = File(into, File(name).name)
                    if (!target.canonicalPath.startsWith(into.canonicalPath + File.separator)) {
                        Log.w(TAG, "skipping suspicious entry: $name")
                        continue
                    }
                    if (entry.isDirectory) continue
                    target.outputStream().use { zip.copyTo(it) }

                    // Keyed by the bare name, which is also what it was written
                    // out as. A backup that has been unzipped and zipped again
                    // -- by a file manager, a cloud sync, a mail client --
                    // comes back with its entries under a folder, and keying by
                    // the full path meant nova.db could not be found in a file
                    // that plainly contained it.
                    out[target.name] = target
                }
            }
        } catch (io: IOException) {
            throw NovaFormatException("That file is not a readable Nova backup.", io)
        } finally {
            runCatching { stream.close() }
        }
        if (out.isEmpty()) throw NovaFormatException("That file is empty or not a ZIP archive.")
        return out
    }

    // ---- preferences -----------------------------------------------------

    /**
     * nova.xml is a SharedPreferences dump: a flat <map> of typed elements.
     *
     * Flattened to strings here. Every value this importer reads is either a
     * number it parses itself or an enum name, so preserving the XML types
     * would mean a sealed hierarchy that every caller immediately collapses
     * back to a string anyway.
     */
    private fun parsePrefs(file: File): Map<String, String> = runCatching {
        val map = HashMap<String, String>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.getAttributeValue(null, "name")
                    if (name != null) {
                        when (parser.name) {
                            "string" -> map[name] = parser.nextText()
                            "int", "long", "float", "boolean" ->
                                parser.getAttributeValue(null, "value")?.let { map[name] = it }
                        }
                    }
                }
                event = parser.next()
            }
        }
        map
    }.getOrElse {
        Log.w(TAG, "could not read nova.xml; importing layout only", it)
        emptyMap()
    }

    // ---- database --------------------------------------------------------

    private fun readDatabase(dbFile: File): Pair<List<NovaItem>, List<NovaGroup>> {
        val db = runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrElse {
            throw NovaFormatException("The layout database inside the backup could not be opened.", it)
        }
        db.use {
            return readItems(db) to readGroups(db)
        }
    }

    private fun readItems(db: SQLiteDatabase): List<NovaItem> {
        val out = ArrayList<NovaItem>(256)
        // Columns named explicitly rather than SELECT *: Nova has added columns
        // over the years and will add more, and a positional read would shift
        // by one the first time it happens.
        val sql = """
            SELECT _id, title, intent, container, screen, cellX, cellY, spanX, spanY,
                   itemType, appWidgetProvider, icon
            FROM favorites
            WHERE itemType >= 0
        """.trimIndent()
        runCatching { db.rawQuery(sql, null) }.getOrElse {
            throw NovaFormatException("The backup has no `favorites` table.", it)
        }.use { c ->
            while (c.moveToNext()) {
                out += NovaItem(
                    id = c.getLong(0),
                    title = if (c.isNull(1)) "" else c.getString(1),
                    intent = if (c.isNull(2)) null else c.getString(2),
                    container = c.getLong(3),
                    screen = c.getInt(4),
                    // REAL, not INTEGER. Nova's subgrid lets an icon sit on a
                    // half cell, so these are 2.5 as often as 2 -- reading them
                    // with getInt truncates silently and stacks two icons.
                    cellX = c.getFloat(5),
                    cellY = c.getFloat(6),
                    spanX = c.getFloat(7),
                    spanY = c.getFloat(8),
                    itemType = c.getInt(9),
                    widgetProvider = if (c.isNull(10)) null else c.getString(10),
                    icon = if (c.isNull(11)) null else c.getBlob(11),
                )
            }
        }
        return out
    }

    private fun readGroups(db: SQLiteDatabase): List<NovaGroup> {
        val out = ArrayList<NovaGroup>()
        // Older backups predate drawer tabs entirely, so a missing table here
        // is a normal outcome and not a reason to fail the import.
        runCatching {
            db.rawQuery("SELECT _id, title, groupType, hideApps FROM drawer_groups", null)
        }.getOrNull()?.use { c ->
            while (c.moveToNext()) {
                out += NovaGroup(
                    id = c.getLong(0),
                    title = if (c.isNull(1)) "" else c.getString(1),
                    type = if (c.isNull(2)) "" else c.getString(2),
                    hidesApps = !c.isNull(3) && c.getInt(3) != 0,
                )
            }
        }
        return out
    }

    companion object {
        private const val TAG = "NovaBackupReader"
    }
}

// ---- the parsed backup ---------------------------------------------------

/** One row of Nova's `favorites` table, before it means anything. */
class NovaItem(
    val id: Long,
    val title: String,
    val intent: String?,
    val container: Long,
    val screen: Int,
    val cellX: Float,
    val cellY: Float,
    val spanX: Float,
    val spanY: Float,
    val itemType: Int,
    val widgetProvider: String?,
    val icon: ByteArray?,
) {
    /** Nova's item types, inherited from Launcher3 and stable since 2011. */
    object Type {
        const val APPLICATION = 0
        const val SHORTCUT = 1
        const val FOLDER = 2
        const val WIDGET = 4
        const val CUSTOM_WIDGET = 5
        const val DEEP_SHORTCUT = 6
    }

    object Containers {
        const val DESKTOP = -100L
        const val DOCK = -101L

        /**
         * Drawer tabs and drawer folders are stored as containers too, at
         * -200 minus the group's id. These are not home screen positions and
         * must not be imported as such -- they are what fills Nova's app drawer
         * tabs, which FreeLauncher does not have.
         */
        fun isDrawerGroup(container: Long) = container <= -200L
    }

    val isOnHomeOrDock: Boolean
        get() = container == Containers.DESKTOP || container == Containers.DOCK

    val isInFolder: Boolean get() = container >= 0L
}

class NovaGroup(
    val id: Long,
    val title: String,
    val type: String,
    val hidesApps: Boolean,
)

class NovaBackup(
    val items: List<NovaItem>,
    val groups: List<NovaGroup>,
    val prefs: Map<String, String>,
) {
    val desktopItems = items.count { it.container == NovaItem.Containers.DESKTOP }
    val dockItems = items.count { it.container == NovaItem.Containers.DOCK }
    val folders = items.count { it.itemType == NovaItem.Type.FOLDER }
    val widgets = items.count { it.itemType == NovaItem.Type.WIDGET || it.itemType == NovaItem.Type.CUSTOM_WIDGET }
    val shortcuts = items.count {
        it.itemType == NovaItem.Type.SHORTCUT || it.itemType == NovaItem.Type.DEEP_SHORTCUT
    }

    /** Pages, from the preference if it is there and from the data if not. */
    val screenCount: Int
        get() = prefs["workspace_screen_count"]?.toIntOrNull()
            ?: ((items.filter { it.container == NovaItem.Containers.DESKTOP }
                .maxOfOrNull { it.screen } ?: 0) + 1)

    /** `"6x6 subgrid"` and `"5x5"` both mean the same first two numbers. */
    fun grid(key: String): Pair<Int, Int>? {
        val raw = prefs[key] ?: return null
        val m = Regex("""(\d+)\s*x\s*(\d+)""").find(raw) ?: return null
        val cols = m.groupValues[1].toIntOrNull() ?: return null
        val rows = m.groupValues[2].toIntOrNull() ?: return null
        return cols to rows
    }
}

// ---- intent decoding -----------------------------------------------------

/**
 * Pulling identity out of Nova's stored intent strings.
 *
 * These are written by `Intent.toUri(URI_INTENT_SCHEME)` and could in principle
 * be read back by `Intent.parseUri`. They are not, deliberately: Nova adds its
 * own keys that the platform parser does not know -- `extendedLaunchFlags` is
 * in every app entry in the backup used to build this -- and on the platform
 * versions where parseUri throws URISyntaxException on an unknown key, that
 * would fail every single row rather than degrade.
 *
 * Reading the two or three fields that matter with a regex cannot fail that
 * way, and for an installed app the stored intent is not what should launch it
 * anyway: the component is looked up through LauncherApps at launch time, so
 * the app keeps working after an update moves its entry activity.
 */
object NovaIntent {

    private val COMPONENT = Regex("""(?:^|;)component=([^;]+)""")
    private val PACKAGE = Regex("""(?:^|;)package=([^;]+)""")
    private val SHORTCUT_ID = Regex("""(?:^|;)S\.shortcut_id=([^;]+)""")

    fun componentOf(intent: String?): String? {
        val raw = intent?.let { COMPONENT.find(it)?.groupValues?.get(1) } ?: return null
        val decoded = Uri.decode(raw)
        // Round-trip through ComponentName so a relative class name -- Nova
        // writes `com.wireguard.android/.activity.MainActivity` -- comes back
        // fully qualified, which is what every later lookup compares against.
        return ComponentName.unflattenFromString(decoded)?.flattenToString()
    }

    fun packageOf(intent: String?): String? {
        if (intent == null) return null
        PACKAGE.find(intent)?.groupValues?.get(1)?.let { return Uri.decode(it) }
        return componentOf(intent)?.let { ComponentName.unflattenFromString(it)?.packageName }
    }

    fun shortcutIdOf(intent: String?): String? =
        intent?.let { SHORTCUT_ID.find(it)?.groupValues?.get(1) }?.let { Uri.decode(it) }
}
