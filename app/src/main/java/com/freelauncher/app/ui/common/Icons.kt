package com.freelauncher.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.freelauncher.app.data.AppEntry
import com.freelauncher.app.data.IconShape
import com.freelauncher.app.data.ItemType
import com.freelauncher.app.data.LauncherItem
import com.freelauncher.app.data.NotificationDots
import com.freelauncher.app.launcher
import com.freelauncher.app.ui.theme.LocalLauncherPalette
import com.freelauncher.app.ui.theme.iconLabelStyle

/**
 * The apps with something waiting, as the icons see it.
 *
 * A composition local rather than a parameter threaded through every icon.
 * There are eight call sites for [ItemIcon] and [AppIcon] across the home
 * screen, the dock, folders, the drawer, private space, the page overview and
 * two drag previews, and every one of them would have had to carry a value it
 * has no interest in. The default is empty, so anything drawn outside the home
 * screen -- the pin confirmation, a preview -- simply has no dots.
 *
 * Dynamic and not `staticCompositionLocalOf`, which is the important half.
 * A static local does not track who reads it: changing one recomposes the
 * entire content of its provider. This value changes every time any app on the
 * phone posts or clears a notification, so as a static local it would rebuild
 * the whole home screen -- every page, the dock, and every hosted widget, each
 * of which answers recomposition with a binder call into another process --
 * several times a minute, to move a four-pixel dot. A dynamic local invalidates
 * only the icons that actually read it.
 */
val LocalNotificationDots = compositionLocalOf { emptySet<String>() }

/** A dot on an icon, meaning the app has something waiting. */
@Composable
private fun BoxScope.NotificationDot(size: Dp) {
    val palette = LocalLauncherPalette.current
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .size(size)
            .clip(CircleShape)
            // An outline in the drawer's own background, not a shadow. The dot
            // lands on the corner of an icon whose colour is unknowable, and a
            // ring of the surface colour separates it from anything -- a white
            // icon, a red one, or the wallpaper past the icon's edge.
            .background(palette.drawerBackground)
            .padding(size * 0.16f)
            .clip(CircleShape)
            .background(palette.accent)
    )
}

/**
 * Whether this app has something waiting.
 *
 * Reads the local rather than taking an argument, so an icon anywhere in the
 * tree picks its dot up without its caller knowing dots exist.
 */
@Composable
private fun hasDot(packageName: String?, userSerial: Long): Boolean {
    val dots = LocalNotificationDots.current
    if (dots.isEmpty()) return false
    val key = NotificationDots.keyOf(packageName, userSerial) ?: return false
    return key in dots
}

/**
 * An app's icon, loaded off the main thread and cached.
 *
 * Keyed on the entry and the shape, so changing the icon shape in settings
 * re-reads every visible icon and nothing else. Null while loading -- callers
 * draw a placeholder of the same size rather than nothing, because a grid that
 * collapses and re-expands as icons arrive is far more distracting than one
 * that fills in.
 */
@Composable
fun rememberAppIcon(entry: AppEntry?, shape: IconShape): State<ImageBitmap?> {
    val context = LocalContext.current
    val app = context.launcher

    // Re-read when the cache has thrown its answer away, which is what makes
    // an app that changed its icon in an update show the new one without the
    // launcher being restarted. The key is the entry's key and not the entry,
    // because an AppEntry compares by name and profile and an icon change
    // alters neither.
    val generation by app.icons.generation.collectAsState()

    return produceState<ImageBitmap?>(initialValue = null, entry?.key, shape, generation) {
        val e = entry ?: return@produceState
        value = app.icons.iconFor(app.apps, e, shape)?.asImageBitmap()
    }
}

/**
 * The icon for a placed item.
 *
 * For an app that is installed, the app's **current** icon wins over anything
 * stored for it. This used to be the other way round, and it is what made the
 * home screen disagree with the drawer: a Nova backup saves the bitmap Nova had
 * already rendered, and one saved pre-cropped to a circle cannot be masked into
 * a square however the setting is changed -- masking removes pixels, it cannot
 * invent the corners back. The drawer, which always draws the live icon,
 * followed the setting while the home screen sat there unchanged.
 *
 * Reading the live icon costs nothing extra: it is the same cache the drawer
 * already fills, so a home screen and a drawer showing the same app now share
 * one entry rather than holding two renderings of it.
 *
 * The stored icon is still what gets drawn when there is no live one to prefer:
 *
 * - **An app that is not installed.** Keeping its icon is the whole reason a
 *   restore can show a layout for apps that have not been reinstalled yet.
 * - **A shortcut.** Its icon is a favicon or a contact photo and is its
 *   identity; the publisher's own icon is a different picture entirely. A web
 *   shortcut resolves to the browser that made it, so preferring the live icon
 *   here would replace every site with the browser's logo.
 */
@Composable
fun rememberItemIcon(item: LauncherItem, shape: IconShape): State<IconResult> {
    val context = LocalContext.current
    val app = context.launcher

    // The app list is a key, not just something read inside the producer.
    //
    // This is the whole of a bug that showed up as a handful of home screen
    // icons being question marks -- permanently, until the launcher was
    // restarted, and a different handful each time.
    //
    // The layout loads synchronously at startup and the app list does not: it
    // is a binder call per profile returning a couple of hundred activities,
    // published when it finishes. So the home screen composes with every icon
    // it has to draw and, for a moment, nothing to resolve them against. Each
    // icon's producer ran, called entryFor, got null, and returned -- and a
    // producer that has returned is not restarted unless one of its keys
    // changes. None of them did, because the item had not changed; only the
    // world around it had.
    //
    // A different handful every time because the producers run one after
    // another and each suspends at the first cache miss, so the list arriving
    // part way through that queue is served to the rest and not to the ones
    // already past.
    //
    // Naming the lists here is what makes the resolution repeat when there is
    // finally something to resolve against -- and again whenever a package is
    // installed, updated or removed, which is the same question asked later.
    // The repository coalesces those bursts, and a repeat is a cache hit.
    val apps by app.apps.apps.collectAsState()
    val privateApps by app.apps.privateApps.collectAsState()

    // And again when the cache is emptied, which is how a changed icon and a
    // changed icon shape reach a home screen that is already drawn.
    val generation by app.icons.generation.collectAsState()

    return produceState<IconResult>(
        initialValue = IconResult.Loading,
        item.iconKey, item.chosenIconKey, item.component, item.userSerial, item.type, shape,
        apps, privateApps, generation,
    ) {
        // An icon the user picked wins outright, before anything is resolved.
        //
        // Everything below prefers the app's live icon, which is right for an
        // icon that merely arrived with an import -- a Nova backup stores what
        // Nova drew, and a stale picture is a worse answer than the current
        // one. It is exactly wrong for an icon someone chose on purpose: that
        // would have been overruled the instant the app list loaded, so the
        // choice would have appeared to do nothing at all.
        val chosen = item.chosenIconKey?.let { app.icons.customIcon(it, shape) }
        if (chosen != null) {
            value = IconResult.Ready(chosen.asImageBitmap())
            return@produceState
        }

        if (item.type == ItemType.APP) {
            val entry = app.apps.entryFor(item.component, item.userSerial)
            val live = entry?.let { app.icons.iconFor(app.apps, it, shape) }
            if (live != null) {
                value = IconResult.Ready(live.asImageBitmap())
                return@produceState
            }
        }

        val custom = item.iconKey?.let { app.icons.customIcon(it, shape) }
        if (custom != null) {
            value = IconResult.Ready(custom.asImageBitmap())
            return@produceState
        }

        // A shortcut whose icon was never captured, or could not be. The
        // publisher's icon is a poor answer but a far better one than a blank
        // cell the user cannot identify.
        val entry = app.apps.entryFor(item.component, item.userSerial)
            ?: app.apps.entryForPackage(item.packageName, item.userSerial)
        val fallback = entry?.let { app.icons.iconFor(app.apps, it, shape) }

        // Absent rather than still loading: this has asked everything it can
        // ask. Saying so is what puts the "not installed" mark on the cell --
        // and saying it only here is what keeps the mark off an icon that is
        // merely a frame away from having its bitmap.
        value = if (fallback != null) IconResult.Ready(fallback.asImageBitmap()) else IconResult.Absent
    }
}

/**
 * What became of an icon that was asked for.
 *
 * Three states and not a nullable bitmap, because "no bitmap" was being asked
 * to mean two opposite things: not yet, and never. The cell drew the
 * not-installed mark for both, so every icon flashed one on its way in and an
 * icon that lost the startup race kept one for good.
 */
sealed interface IconResult {
    /** Still being resolved. Draw a space of the right size and wait. */
    data object Loading : IconResult

    data class Ready(val bitmap: ImageBitmap) : IconResult

    /** Resolved, and there is nothing to draw: the app is genuinely gone. */
    data object Absent : IconResult
}

/**
 * One icon, at a given size, with no label and no click handling.
 *
 * Split out from [IconTile] because a folder preview, a drag shadow and a
 * drawer row all need exactly this and none of them want the rest.
 */
@Composable
fun ItemIcon(
    item: LauncherItem,
    shape: IconShape,
    size: Dp,
    modifier: Modifier = Modifier,
    folderChildren: List<LauncherItem> = emptyList(),
    badged: Boolean = true,
) {
    if (item.type == ItemType.FOLDER) {
        FolderIcon(children = folderChildren, shape = shape, size = size, modifier = modifier)
        return
    }
    val icon by rememberItemIcon(item, shape)

    // Only where the dot is large enough to be a dot. The four thumbnails
    // inside a folder icon are about a fifth of an icon each, and a mark on one
    // of those is a stray pixel rather than information -- the folder draws its
    // own, once, for whatever is inside it.
    val dot = badged && size >= DOT_MIN_ICON_DP.dp &&
        hasDot(item.packageName ?: item.componentName?.packageName, item.userSerial)

    // Only a shortcut is badged, and only when it is drawn large enough for the
    // badge to be a picture rather than a speck. The threshold is what keeps
    // the four thumbnails inside a folder icon clean.
    val wantsBadge = badged &&
        size >= BADGE_MIN_ICON_DP.dp &&
        (item.type == ItemType.DEEP_SHORTCUT || item.type == ItemType.SHORTCUT)

    if (!wantsBadge && !dot) {
        IconBitmap(icon, size, modifier)
        return
    }

    Box(modifier.size(size)) {
        IconBitmap(icon, size, Modifier)
        if (dot) NotificationDot(size * DOT_FRACTION)
        if (wantsBadge) ShortcutBadge(
            packageName = item.packageName,
            userSerial = item.userSerial,
            shape = shape,
            size = (size * 0.36f).coerceIn(14.dp, 26.dp),
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/**
 * Below this, a badge is a smudge rather than a picture, so it is left off.
 */
private const val BADGE_MIN_ICON_DP = 34

/**
 * The plate behind a folder's four previews.
 *
 * A fixed light tint rather than a theme colour, because it sits on the
 * wallpaper and not on any surface the theme knows about. Following the dark
 * theme here would put a dark plate on a bright wallpaper, which is the one
 * combination that disappears.
 */
private val FOLDER_PLATE = Color(0xD9EFEFF2)

/**
 * Who put this shortcut on the home screen.
 *
 * A shortcut's own icon says what it opens -- a YouTube favicon, a contact's
 * photo -- and nothing about where it came from, which is the one thing the
 * user needs to know to understand why tapping it opens Brave rather than the
 * YouTube app. The platform badges pinned shortcuts for exactly this reason.
 *
 * Drawn on a white plate rather than bare. The badge sits over the corner of
 * another icon and over the wallpaper beyond it, so without a plate it lands on
 * an unpredictable background and a dark publisher icon disappears into a dark
 * wallpaper.
 *
 * Nothing at all is drawn if the publisher is not installed. An absent badge
 * says less than a wrong one, and a shortcut can easily outlive the app that
 * created it.
 */
@Composable
private fun ShortcutBadge(
    packageName: String?,
    userSerial: Long,
    shape: IconShape,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap by rememberPackageIcon(packageName, userSerial, shape)
    val badge = bitmap ?: return
    val plate = shape.plateShape()

    Box(
        modifier
            .size(size)
            .shadow(2.dp, plate)
            .background(Color.White, plate),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = badge,
            contentDescription = null,
            modifier = Modifier.size(size * 0.82f),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * The badge's backing plate, in the shape the user picked for their icons.
 *
 * A circular plate among square icons is exactly as wrong as a square shortcut
 * among round ones, and for the same reason -- the setting is meant to describe
 * the whole home screen, not most of it.
 *
 * Percentages rather than fixed radii, because the plate is a fraction of an
 * icon and a 12dp corner on a 20dp badge is a circle by accident. The squircle
 * is approximated with a large rounded corner rather than the true superellipse
 * [IconCache] renders: at badge size the two are a pixel apart, and a Path-based
 * Shape would be rebuilt on every recomposition of every shortcut.
 *
 * SYSTEM becomes a circle. The platform's own mask is a Path that cannot be had
 * as a Compose Shape without rebuilding it, and Android's own shortcut badges
 * are circular on every device that ships one.
 */
private fun IconShape.plateShape(): Shape = when (this) {
    IconShape.CIRCLE, IconShape.SYSTEM -> CircleShape
    IconShape.SQUIRCLE -> RoundedCornerShape(percent = 42)
    IconShape.ROUNDED -> RoundedCornerShape(percent = 24)
    IconShape.SQUARE -> RoundedCornerShape(percent = 8)
}

/**
 * The icon of an installed package, by package name.
 *
 * Separate from [rememberAppIcon] because that one starts from an [AppEntry],
 * and a shortcut never has one -- it records a package and leaves the rest to
 * the launcher.
 */
@Composable
fun rememberPackageIcon(
    packageName: String?,
    userSerial: Long,
    shape: IconShape,
): State<ImageBitmap?> {
    val context = LocalContext.current
    val app = context.launcher
    val apps by app.apps.apps.collectAsState()
    val generation by app.icons.generation.collectAsState()
    return produceState<ImageBitmap?>(
        initialValue = null, packageName, userSerial, shape, apps, generation,
    ) {
        val entry = app.apps.entryForPackage(packageName, userSerial) ?: return@produceState
        value = app.icons.iconFor(app.apps, entry, shape)?.asImageBitmap()
    }
}

@Composable
fun AppIcon(
    entry: AppEntry,
    shape: IconShape,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap by rememberAppIcon(entry, shape)
    val dot = size >= DOT_MIN_ICON_DP.dp && hasDot(entry.packageName, entry.userSerial)

    val content = @Composable { m: Modifier ->
        IconBitmap(
            // An installed app always has an icon; there is nothing here for
            // the not-installed mark to mean, so a slow load stays a blank
            // space.
            if (bitmap != null) IconResult.Ready(bitmap!!) else IconResult.Loading,
            size,
            m,
        )
    }

    if (!dot) {
        content(modifier)
        return
    }
    Box(modifier.size(size)) {
        content(Modifier)
        NotificationDot(size * DOT_FRACTION)
    }
}

/** Below this an icon has no corner worth marking. */
private const val DOT_MIN_ICON_DP = 30

/** How much of the icon the dot takes, ring included. */
private const val DOT_FRACTION = 0.27f

@Composable
private fun IconBitmap(
    icon: IconResult,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when (icon) {
            is IconResult.Ready -> Image(
                bitmap = icon.bitmap,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Fit,
            )

            // An item whose app is gone keeps its place and says so, rather
            // than vanishing. A user who uninstalled something on purpose can
            // remove the icon; a user whose app disappeared because an SD card
            // was unmounted would otherwise lose that part of their layout
            // permanently, with no way to tell what used to be there.
            IconResult.Absent -> Icon(
                imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                contentDescription = "App not installed",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(size * 0.7f),
            )

            IconResult.Loading -> Spacer(Modifier.size(size))
        }
    }
}

/**
 * A folder, drawn as its first four contents on a rounded plate.
 *
 * Four because that is what fits at icon size and still reads as separate
 * things; more than that and each one is a smudge, which tells the user nothing
 * that an empty box would not.
 */
@Composable
fun FolderIcon(
    children: List<LauncherItem>,
    shape: IconShape,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val preview = remember(children) { children.take(4) }
    val inner = size * 0.40f
    val gap = size * 0.05f

    // A closed folder speaks for everything in it. Without this a folder is the
    // one place a notification can hide completely: the apps inside it are not
    // drawn, so nothing on the home screen would show that one of them has
    // anything waiting.
    val dots = LocalNotificationDots.current
    val dot = size >= DOT_MIN_ICON_DP.dp && dots.isNotEmpty() && children.any { child ->
        NotificationDots.keyOf(
            child.packageName ?: child.componentName?.packageName,
            child.userSerial,
        ) in dots
    }

    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            // Its own plate rather than the dock's glass.
            //
            // The dock is a long bar and can be barely-there; a folder is one
            // icon among icons and has to read as a container at a glance.
            // At the dock's 20% white the four previews looked like four loose
            // icons that happened to be close together.
            .background(FOLDER_PLATE),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(gap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (row in 0 until 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (col in 0 until 2) {
                        val index = row * 2 + col
                        val child = preview.getOrNull(index)
                        if (child != null) {
                            ItemIcon(child, shape, inner, badged = false)
                        } else {
                            Spacer(Modifier.size(inner))
                        }
                    }
                }
            }
        }
        if (dot) NotificationDot(size * DOT_FRACTION)
    }
}

/**
 * The full cell: an icon with its label underneath.
 *
 * The label is laid out for exactly one line, ellipsised. Letting it wrap to
 * two makes cells on the same row different heights, which shows up as a ragged
 * grid the moment one app has a long name.
 */
@Composable
fun IconTile(
    label: String,
    showLabel: Boolean,
    labelScale: Float,
    onWallpaper: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = iconLabelStyle(labelScale, onWallpaper),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
