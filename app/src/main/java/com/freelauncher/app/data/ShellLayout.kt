package com.freelauncher.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which home surface the launcher draws.
 *
 * These are layouts, not separate home screens. Every one of them shows the same
 * set of things - whatever is on the classic home screen, in [LayoutStore] - and
 * differs only in how it draws them and in the order and sizes it keeps for them.
 * Pinning or unpinning in any of them changes that one shared set, so switching
 * never means adding an app a second time.
 */
enum class HomeShell(val label: String, val blurb: String) {
    CLASSIC("Classic", "Pages, dock and drawer. Widgets and folders."),
    WINDOWSPHONE("Windows Phone", "Metro. Accent tiles on black, app list one swipe away."),
    WINDOWS11("Windows 11", "A rounded Start panel: pinned grid, search, all apps."),
}

/** How wide a tile is, in columns of a four-column grid. */
enum class TileSize(val span: Int, val label: String) {
    SMALL(1, "Small"),
    MEDIUM(2, "Medium"),
    WIDE(4, "Wide"),
    ;

    fun next(): TileSize = when (this) {
        SMALL -> MEDIUM
        MEDIUM -> WIDE
        WIDE -> SMALL
    }
}

/**
 * What one alternative shell remembers for itself: the order of its tiles, and on
 * Windows Phone their sizes.
 *
 * Deliberately not *which* tiles. That lives in one place, the classic layout, so
 * there is exactly one answer to "is this app on the home screen" and no second
 * copy to fall out of step with it. An order may name keys that are no longer on
 * the home screen; they are simply skipped, and anything on the home screen the
 * order does not mention goes at the end.
 */
data class ShellArrangement(
    val order: List<String> = emptyList(),
    val sizes: Map<String, TileSize> = emptyMap(),
) {
    fun sizeOf(key: String): TileSize = sizes[key] ?: TileSize.MEDIUM

    /** [tiles] in this shell's order, with anything it has not seen appended. */
    fun <T> arrange(tiles: List<T>, keyOf: (T) -> String): List<T> {
        val rank = order.withIndex().associate { (i, k) -> k to i }
        return tiles.withIndex()
            .sortedWith(compareBy({ rank[keyOf(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }
}

/**
 * Persistence for the alternative shells' orders and sizes.
 *
 * SharedPreferences, one small JSON blob per shell, read on the first frame and
 * written only when the user rearranges or resizes something.
 */
class ShellStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Only the shells that keep an arrangement. Classic keeps its own, in [LayoutStore]. */
    private val shells = listOf(HomeShell.WINDOWSPHONE, HomeShell.WINDOWS11)

    private val flows: Map<HomeShell, MutableStateFlow<ShellArrangement>> =
        shells.associateWith { MutableStateFlow(read(it)) }

    init {
        // Anything in this file that no current shell owns is left over from a
        // shell or a format that no longer exists. It could only ever be read
        // back by a backup, so it is cleared rather than carried forever.
        val owned = shells.map(::keyFor).toSet()
        val stale = prefs.all.keys.filterNot { it in owned }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
    }

    fun flowFor(shell: HomeShell): StateFlow<ShellArrangement> =
        (flows[shell] ?: flows.getValue(HomeShell.WINDOWSPHONE)).asStateFlow()

    fun current(shell: HomeShell): ShellArrangement = flowFor(shell).value

    private fun keyFor(shell: HomeShell) = "layout_${shell.name.lowercase()}"
    private fun legacyKeyFor(shell: HomeShell) = "arrangement_${shell.name.lowercase()}"

    private fun read(shell: HomeShell): ShellArrangement {
        prefs.getString(keyFor(shell), null)?.let { raw ->
            return runCatching { parse(JSONObject(raw)) }.getOrElse { ShellArrangement() }
        }
        // First run of this version: take the order and sizes out of the old
        // category arrangement, so a rearranged Start screen keeps its shape.
        // Only the first category's list is kept, because that was the one on
        // screen; which apps appear is no longer this store's business.
        prefs.getString(legacyKeyFor(shell), null)?.let { raw ->
            val migrated = runCatching { migrate(JSONObject(raw)) }.getOrNull() ?: ShellArrangement()
            prefs.edit()
                .putString(keyFor(shell), toJson(migrated).toString())
                .remove(legacyKeyFor(shell))
                .apply()
            return migrated
        }
        return ShellArrangement()
    }

    private fun migrate(root: JSONObject): ShellArrangement {
        val first = root.optJSONArray("categories")?.optJSONObject(0) ?: return ShellArrangement()
        return ShellArrangement(
            order = first.optJSONArray("apps").strings(),
            sizes = first.optJSONObject("sizes").sizes(),
        )
    }

    private fun parse(o: JSONObject) = ShellArrangement(
        order = o.optJSONArray("order").strings(),
        sizes = o.optJSONObject("sizes").sizes(),
    )

    private fun toJson(value: ShellArrangement): JSONObject {
        // Only non-default sizes are written: MEDIUM is what an unrecorded tile is.
        val sizes = JSONObject()
        for ((k, v) in value.sizes) if (v != TileSize.MEDIUM) sizes.put(k, v.name)
        return JSONObject()
            .put("order", JSONArray().apply { value.order.forEach { put(it) } })
            .put("sizes", sizes)
    }

    private fun write(shell: HomeShell, value: ShellArrangement) {
        val flow = flows[shell] ?: return
        prefs.edit().putString(keyFor(shell), toJson(value).toString()).apply()
        flow.value = value
    }

    /**
     * Saves the order a drag left on screen.
     *
     * Keys the shell was not showing - an app on an unmounted card, a shortcut
     * whose publisher is updating - are kept after the shown ones instead of
     * being dropped, so a tile that was briefly unavailable comes back to its
     * place rather than to the end.
     */
    fun setOrder(shell: HomeShell, shown: List<String>) {
        val old = current(shell)
        write(shell, old.copy(order = shown + old.order.filterNot { it in shown }))
    }

    fun setSize(shell: HomeShell, key: String, size: TileSize) {
        val old = current(shell)
        write(shell, old.copy(sizes = old.sizes + (key to size)))
    }

    /**
     * Drops one key from every shell.
     *
     * Called when something is unpinned, so that pinning it again later puts it at
     * the end - where a newly added tile belongs - rather than back in a slot it
     * left a while ago.
     */
    fun forget(key: String) {
        for (shell in shells) {
            val old = current(shell)
            if (key in old.order || key in old.sizes) {
                write(shell, old.copy(order = old.order - key, sizes = old.sizes - key))
            }
        }
    }

    /** Puts [key] at the end of every shell's order, for something just pinned. */
    fun appendEverywhere(key: String) {
        for (shell in shells) {
            val old = current(shell)
            write(shell, old.copy(order = (old.order - key) + key))
        }
    }

    // ---- backup ------------------------------------------------------------

    fun exportJson(): JSONObject = JSONObject().apply {
        for (shell in shells) put(shell.name, toJson(current(shell)))
    }

    fun importJson(root: JSONObject) {
        for (shell in shells) {
            val o = root.optJSONObject(shell.name) ?: continue
            write(shell, runCatching { parse(o) }.getOrElse { ShellArrangement() })
        }
    }

    private companion object {
        const val FILE = "shell_layout"
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList { for (i in 0 until length()) optString(i).takeIf { it.isNotEmpty() }?.let(::add) }
}

/**
 * Read leniently: a size this version does not know is dropped rather than
 * thrown, so an arrangement written by a later build still opens.
 */
private fun JSONObject?.sizes(): Map<String, TileSize> {
    if (this == null) return emptyMap()
    val out = HashMap<String, TileSize>()
    val names = keys()
    while (names.hasNext()) {
        val k = names.next()
        runCatching { TileSize.valueOf(getString(k)) }.getOrNull()?.let { out[k] = it }
    }
    return out
}
