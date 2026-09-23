package com.freelauncher.app.data

/**
 * One thing on the home screen, as the alternative shells see it.
 *
 * The shells do not keep their own list of pinned apps. They read the classic
 * layout, because that is the one place that says what is on the home screen,
 * and a second list is a second answer that can disagree with the first. That
 * disagreement is exactly how an app ended up pinned in one shell and missing in
 * another, or removed in one and still present in the rest.
 *
 * [item] is the real classic record, so a tile launches, and draws its icon,
 * through exactly the code the classic home screen uses - which is what makes a
 * website shortcut added from a browser work here with no special case.
 */
data class HomeTile(
    val key: String,
    val item: LauncherItem,
    val label: String,
)

/**
 * The identity a shell remembers an item by, or null for things no shell shows.
 *
 * An app is keyed by what it is - component and profile - not by the record that
 * placed it, so moving it between pages, into a folder or to the dock on the
 * classic screen does not lose its place in a shell. A shortcut has no such
 * identity of its own, so it is keyed by its record; restoring a backup keeps
 * record ids, so the key survives that too.
 */
fun homeKey(item: LauncherItem): String? = when (item.type) {
    ItemType.APP -> item.component?.let { AppEntry.keyOf(it, item.userSerial) }
    ItemType.SHORTCUT, ItemType.DEEP_SHORTCUT -> "item:${item.id}"
    ItemType.FOLDER, ItemType.WIDGET -> null
}

/**
 * Everything on the classic home screen that a shell can show, in the order a
 * person reads the classic screen: page by page, row by row, a folder's contents
 * where the folder is, and the dock last.
 *
 * Widgets and folders themselves are left out - a shell has nowhere to put either
 * - but the apps inside a folder are included, because they are on the home
 * screen. An app placed twice appears once.
 *
 * [labelFor] returns null for an app that is not installed. Those are skipped: a
 * tile that cannot launch is worse than no tile, and the record stays on the
 * classic screen, which is the place that offers to reinstall or tidy it away.
 * Nothing here ever writes, so a list that is briefly short while the app list
 * is still loading cannot remove anything.
 */
fun homeTiles(items: List<LauncherItem>, labelFor: (LauncherItem) -> String?): List<HomeTile> {
    val topLevel = items
        .filter { it.container == Container.DESKTOP || it.container == Container.DOCK }
        .sortedWith(
            compareBy(
                { if (it.container == Container.DOCK) 1 else 0 },
                { it.screen },
                { it.cellY },
                { it.cellX },
            ),
        )

    val seen = HashSet<String>()
    val out = ArrayList<HomeTile>()

    fun offer(item: LauncherItem) {
        val key = homeKey(item) ?: return
        if (key in seen) return
        val label = labelFor(item) ?: return
        seen += key
        out += HomeTile(key, item, label)
    }

    // Only folders that are themselves on a page are walked, so the children of a
    // folder that no longer exists stay out: they are invisible on the classic
    // screen too, and not on the home screen in any sense a person would know.
    for (item in topLevel) {
        if (item.type == ItemType.FOLDER) {
            items.filter { it.container == item.id }
                .sortedWith(compareBy({ it.cellY }, { it.cellX }))
                .forEach(::offer)
        } else {
            offer(item)
        }
    }
    return out
}

/**
 * Private space apps in the order the user arranged them.
 *
 * Shared by every home style so the arrangement is the same wherever the sheet is
 * opened. Anything the stored order does not mention sorts to the end by name,
 * which is where a newly installed private app belongs.
 */
fun orderPrivateApps(apps: List<AppEntry>, order: List<String>): List<AppEntry> {
    val rank = order.withIndex().associate { (index, key) -> key to index }
    return apps.sortedWith(compareBy({ rank[it.key] ?: Int.MAX_VALUE }, { it.label.lowercase() }))
}
