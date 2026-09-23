package com.freelauncher.app.data

import android.content.ComponentName
import android.os.UserHandle

/**
 * Where an item lives.
 *
 * The two negative values are the same ones AOSP's Launcher3 has used since
 * 2011, and Nova inherits them. Keeping the numbers identical is not nostalgia:
 * it means a Nova backup's `container` column drops straight into this field
 * with no translation table to get wrong, and a misread container is the kind
 * of bug that silently empties someone's dock.
 *
 * Any other value is the id of the folder holding the item.
 */
object Container {
    const val DESKTOP = -100L
    const val DOCK = -101L
}

/**
 * How many icons a folder holds across.
 *
 * In the model rather than in the panel that draws it, because it is not only a
 * drawing decision: a folder's children are stored as cells, so anything that
 * *puts* something in a folder has to agree with it. The Nova importer is in
 * this layer and cannot see a UI constant, so it had its own literal 3 -- which
 * is the kind of agreement that holds right up until one of the two changes.
 */
const val FOLDER_COLUMNS = 3

enum class ItemType {
    /** An installed app, launched through its component. */
    APP,

    /** A legacy shortcut carrying its own intent -- pre-8.0 style. */
    SHORTCUT,

    /** A folder. Its children are the items whose container is its id. */
    FOLDER,

    /** A home-screen widget hosted through AppWidgetHost. */
    WIDGET,

    /** An app-published shortcut, launched through LauncherApps. */
    DEEP_SHORTCUT,
}

/**
 * One thing on the home screen, the dock, or inside a folder.
 *
 * Flat rather than a sealed hierarchy on purpose. Every item is read from and
 * written to one JSON array, and folders address their children by id rather
 * than by nesting, so a flat record maps to storage without a discriminated
 * union at either end. The cost is a handful of fields that only apply to one
 * type; [widgetId] on an app is simply -1.
 */
data class LauncherItem(
    val id: Long,
    val type: ItemType,
    val title: String = "",

    /** `pkg/cls`, as [ComponentName.flattenToString] writes it. */
    val component: String? = null,
    val packageName: String? = null,

    /**
     * Serial number of the profile that owns this item, from UserManager.
     * Zero is the primary user; a work profile is some other number. Stored
     * rather than the UserHandle because a serial survives being written to
     * disk and a handle does not.
     */
    val userSerial: Long = 0L,

    /** A legacy shortcut's intent, in `Intent.URI_INTENT_SCHEME` form. */
    val intentUri: String? = null,

    /** The publisher's id for a deep shortcut. */
    val shortcutId: String? = null,

    val container: Long = Container.DESKTOP,
    val screen: Int = 0,
    val cellX: Int = 0,
    val cellY: Int = 0,
    val spanX: Int = 1,
    val spanY: Int = 1,

    /** Allocated by AppWidgetHost, meaningless on any other device. */
    val widgetId: Int = -1,
    val widgetProvider: String? = null,

    /**
     * Name of a PNG in the app's `icons` directory that overrides whatever the
     * package manager would supply. This is how an imported Nova backup keeps
     * icon-pack artwork: Nova stores the already-rendered bitmap, so the pack
     * itself does not have to be installed for the icons to come back.
     */
    val iconKey: String? = null,

    /**
     * An icon the user picked, which overrides everything else.
     *
     * A slot of its own rather than a flag on [iconKey], and the difference
     * matters the moment somebody changes their mind. [iconKey] is the icon
     * that *came with* the item -- a shortcut's own picture, captured when it
     * was pinned because it can never be asked for again, or the bitmap a Nova
     * import carried. Overwriting that to hold a chosen icon would mean
     * choosing one destroyed it, so "use the original icon" could not put back
     * anything but the publisher's logo.
     *
     * The two also lose to different things. An icon that merely arrived is a
     * snapshot, and the app's own current icon beats a stale one; an icon
     * somebody went and chose is the entire point of having chosen it, and
     * beats everything. Keeping them apart is what lets both rules hold at
     * once.
     */
    val chosenIconKey: String? = null,
) {
    val componentName: ComponentName?
        get() = component?.let { ComponentName.unflattenFromString(it) }

    val isContainerItem: Boolean get() = type == ItemType.FOLDER

    /** Grid items occupy cells; widgets may occupy several. */
    fun occupies(x: Int, y: Int): Boolean =
        x >= cellX && x < cellX + spanX && y >= cellY && y < cellY + spanY
}

/**
 * A launchable activity, as the system currently reports it.
 *
 * Distinct from [LauncherItem] because the two answer different questions.
 * This is "what is installed right now", rebuilt from LauncherApps whenever
 * packages change; a LauncherItem is "what the user arranged", which has to
 * survive an app being updated, and has to survive it being uninstalled for
 * long enough to show the user that it went.
 */
data class AppEntry(
    val component: ComponentName,
    val user: UserHandle,
    val userSerial: Long,
    val label: String,
) {
    val packageName: String get() = component.packageName

    /** Stable identity across restarts: component plus profile. */
    val key: String get() = "${component.flattenToString()}#$userSerial"

    companion object {
        fun keyOf(component: String, userSerial: Long) = "${canonical(component)}#$userSerial"

        /**
         * One spelling for a component: `pkg/.Main` and `pkg/pkg.Main` name the
         * same activity, and [key] is built from the long one.
         *
         * Layouts written by builds from before the Nova import expanded
         * relative class names still hold the short spelling. The classic
         * screen found those apps anyway, by falling back to the package, but
         * the other home styles match on the key alone -- so an app pinned in a
         * classic folder was simply missing from Windows Phone and Windows 11.
         */
        fun canonical(component: String): String =
            ComponentName.unflattenFromString(component)?.flattenToString() ?: component
    }
}

/** A home screen page. Screens are ordered by [index] and renumbered on delete. */
data class WorkspaceScreen(val id: Long, val index: Int)
