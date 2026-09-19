package com.freelauncher.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
    BLACK("Black (AMOLED)"),
}

enum class AccentColor(val label: String, val rgb: Long) {
    VIOLET("Violet", 0xFF7C4DD1),
    BLUE("Blue", 0xFF3D7BE8),
    TEAL("Teal", 0xFF12A594),
    GREEN("Green", 0xFF3EA455),
    AMBER("Amber", 0xFFD98A0B),
    ORANGE("Orange", 0xFFE0652B),
    ROSE("Rose", 0xFFD9436B),
    SLATE("Slate", 0xFF64748B),
}

/**
 * How an icon is masked.
 *
 * SYSTEM means "do nothing": hand back the drawable the package manager gave
 * us, adaptive or not, and let the platform mask it the way it masks every
 * other icon on the phone. Every other value re-masks adaptive icons to a fixed
 * shape and leaves legacy ones alone, because a legacy icon is already a
 * finished bitmap with its own built-in shadow, and cropping it to a circle
 * cuts the artwork rather than the background.
 */
enum class IconShape(val label: String) {
    SYSTEM("System default"),
    CIRCLE("Circle"),
    SQUIRCLE("Squircle"),
    ROUNDED("Rounded square"),
    SQUARE("Square"),
}

enum class DrawerStyle(val label: String) {
    GRID("Grid"),
    LIST("List"),
}

enum class SwipeDownAction(val label: String) {
    NOTIFICATIONS("Open notifications"),
    SEARCH("Search apps"),
    NONE("Nothing"),
}

/**
 * An immutable snapshot of every preference.
 *
 * Passed down the composable tree as one value rather than read key by key, so
 * that changing any setting produces exactly one recomposition of the things
 * that actually read it, and so a screen cannot observe a half-applied change
 * where the column count has updated but the icon size has not.
 */
data class LauncherSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentColor = AccentColor.VIOLET,

    val desktopCols: Int = 5,
    val desktopRows: Int = 5,
    val iconScale: Float = 1f,
    val labelScale: Float = 1f,
    val showDesktopLabels: Boolean = true,
    val showPageIndicator: Boolean = true,

    /**
     * Draw a dot on apps that have a notification waiting.
     *
     * Off by default, and it stays off until the user turns it on, because
     * switching it on is a request for a permission that lets this app read
     * every notification on the phone. A launcher should not help itself to
     * that on the strength of a default.
     */
    val notificationDots: Boolean = false,
    val wallpaperDim: Float = 0.15f,
    val hideStatusBar: Boolean = false,

    /**
     * The page Home returns to. Not always page 0: people who put the page they
     * actually use in the middle want Home to land there, not at the far left.
     */
    val defaultPage: Int = 0,

    val dockEnabled: Boolean = true,
    val dockCols: Int = 5,
    val dockShowLabels: Boolean = false,
    val dockBackground: Boolean = true,

    val drawerStyle: DrawerStyle = DrawerStyle.GRID,
    val drawerCols: Int = 4,
    val drawerOpacity: Float = 0.94f,
    val drawerShowLabels: Boolean = true,
    val drawerSearchEnabled: Boolean = true,
    val drawerAutoKeyboard: Boolean = false,

    val iconShape: IconShape = IconShape.SYSTEM,

    /**
     * The package name of an installed icon pack, or empty for none.
     *
     * A package name rather than an index into a list, because the list is
     * whatever happens to be installed and changes without warning. A pack that
     * has been uninstalled leaves a name here that resolves to nothing, which
     * degrades to the apps' own icons on its own.
     */
    val iconPack: String = "",
    val swipeDownAction: SwipeDownAction = SwipeDownAction.NOTIFICATIONS,

    /** [AppEntry.key] values kept out of the drawer. */
    val hiddenApps: Set<String> = emptySet(),

    /**
     * The order private space apps are shown in, as [AppEntry.key] values.
     *
     * A list and not a set, because the order *is* the value. Apps missing from
     * it are not hidden, they are simply unplaced -- newly installed ones go to
     * the end rather than into the middle of an arrangement the user made.
     *
     * Kept here rather than in the layout store because these are not placed
     * items. A private app has no cell, no page and no folder; it has a
     * position in one list, which is a preference.
     */
    val privateOrder: List<String> = emptyList(),
)

/**
 * Preference storage.
 *
 * SharedPreferences rather than DataStore. The launcher reads settings on the
 * very first frame after a cold start, because the grid cannot be laid out
 * without knowing its column count, and DataStore is asynchronous by design.
 * That would mean either a frame of the wrong grid or a blocking read that
 * defeats the point of using it. SharedPreferences is already in memory by the
 * time onCreate runs.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<LauncherSettings> = _state.asStateFlow()

    val value: LauncherSettings get() = _state.value

    private fun read() = LauncherSettings(
        themeMode = prefs.enumOr("theme_mode", ThemeMode.SYSTEM),
        accent = prefs.enumOr("accent", AccentColor.VIOLET),
        desktopCols = prefs.getInt("desktop_cols", 5),
        desktopRows = prefs.getInt("desktop_rows", 5),
        iconScale = prefs.getFloat("icon_scale", 1f),
        labelScale = prefs.getFloat("label_scale", 1f),
        showDesktopLabels = prefs.getBoolean("desktop_labels", true),
        showPageIndicator = prefs.getBoolean("page_indicator", true),
        notificationDots = prefs.getBoolean("notification_dots", false),
        wallpaperDim = prefs.getFloat("wallpaper_dim", 0.15f),
        hideStatusBar = prefs.getBoolean("hide_status_bar", false),
        defaultPage = prefs.getInt("default_page", 0),
        dockEnabled = prefs.getBoolean("dock_enabled", true),
        dockCols = prefs.getInt("dock_cols", 5),
        dockShowLabels = prefs.getBoolean("dock_labels", false),
        dockBackground = prefs.getBoolean("dock_background", true),
        drawerStyle = prefs.enumOr("drawer_style", DrawerStyle.GRID),
        drawerCols = prefs.getInt("drawer_cols", 4),
        drawerOpacity = prefs.getFloat("drawer_opacity", 0.94f),
        drawerShowLabels = prefs.getBoolean("drawer_labels", true),
        drawerSearchEnabled = prefs.getBoolean("drawer_search", true),
        drawerAutoKeyboard = prefs.getBoolean("drawer_auto_keyboard", false),
        iconShape = prefs.enumOr("icon_shape", IconShape.SYSTEM),
        iconPack = prefs.getString("icon_pack", "").orEmpty(),
        swipeDownAction = prefs.enumOr("swipe_down", SwipeDownAction.NOTIFICATIONS),
        // The set SharedPreferences returns is owned by SharedPreferences and
        // must not be mutated or held onto; copying it is not defensive style
        // here, it is the documented contract.
        hiddenApps = prefs.getStringSet("hidden_apps", emptySet())!!.toSet(),
        // One delimited string rather than a string set, because a set does not
        // keep an order and this field is nothing but an order. Newlines are
        // the separator: an AppEntry key is a component name and a profile
        // serial, and neither can contain one.
        privateOrder = prefs.getString("private_order", null)
            ?.split('\n')
            ?.filter { it.isNotEmpty() }
            .orEmpty(),
    )

    /**
     * Apply a change and republish.
     *
     * Takes a whole-object transform rather than a key and a value, so callers
     * name the field in Kotlin and get the compiler's help instead of matching
     * a string key to a getter by hand at every call site.
     */
    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        val next = transform(_state.value)
        prefs.edit().apply {
            putString("theme_mode", next.themeMode.name)
            putString("accent", next.accent.name)
            putInt("desktop_cols", next.desktopCols)
            putInt("desktop_rows", next.desktopRows)
            putFloat("icon_scale", next.iconScale)
            putFloat("label_scale", next.labelScale)
            putBoolean("desktop_labels", next.showDesktopLabels)
            putBoolean("page_indicator", next.showPageIndicator)
            putBoolean("notification_dots", next.notificationDots)
            putFloat("wallpaper_dim", next.wallpaperDim)
            putBoolean("hide_status_bar", next.hideStatusBar)
            putInt("default_page", next.defaultPage)
            putBoolean("dock_enabled", next.dockEnabled)
            putInt("dock_cols", next.dockCols)
            putBoolean("dock_labels", next.dockShowLabels)
            putBoolean("dock_background", next.dockBackground)
            putString("drawer_style", next.drawerStyle.name)
            putInt("drawer_cols", next.drawerCols)
            putFloat("drawer_opacity", next.drawerOpacity)
            putBoolean("drawer_labels", next.drawerShowLabels)
            putBoolean("drawer_search", next.drawerSearchEnabled)
            putBoolean("drawer_auto_keyboard", next.drawerAutoKeyboard)
            putString("icon_shape", next.iconShape.name)
            putString("icon_pack", next.iconPack)
            putString("swipe_down", next.swipeDownAction.name)
            putStringSet("hidden_apps", next.hiddenApps)
            putString("private_order", next.privateOrder.joinToString("\n"))
        }.apply()
        _state.value = next
    }

    fun resetToDefaults() = update { LauncherSettings() }

    private inline fun <reified E : Enum<E>> SharedPreferences.enumOr(key: String, fallback: E): E {
        val raw = getString(key, null) ?: return fallback
        // A stored name can outlive the constant that wrote it, if a later
        // version drops an option. valueOf would throw on the way to the first
        // frame, which for a launcher means no home screen at all.
        return runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)
    }

    private companion object {
        const val FILE = "freelauncher"
    }
}
