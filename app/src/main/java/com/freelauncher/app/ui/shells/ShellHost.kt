package com.freelauncher.app.ui.shells

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freelauncher.app.data.AppEntry
import com.freelauncher.app.data.HomeShell
import com.freelauncher.app.data.HomeTile
import com.freelauncher.app.data.ItemType
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.data.TileSize
import com.freelauncher.app.data.homeTiles
import com.freelauncher.app.data.orderPrivateApps
import com.freelauncher.app.launcher
import com.freelauncher.app.ui.drawer.PRIVATE_PIN_WARNING
import com.freelauncher.app.ui.drawer.PrivateSpaceSheet
import com.freelauncher.app.ui.home.ConfirmDialog

/**
 * Mounts whichever alternative shell is selected and gives it what every shell
 * needs: the home tiles, the app list, launching, menus, and private space.
 *
 * The tiles come from the classic layout, not from anything the shell keeps. That
 * is the whole of the syncing: there is one list of what is on the home screen,
 * pinning adds to it and unpinning takes from it, and every style reads the same
 * list. A shell remembers only its own order and, on Windows Phone, sizes.
 *
 * The classic grid is not mounted from here. It has its own state, its own drag
 * machinery and its own overlays.
 */
@Composable
fun ShellHost(
    shell: HomeShell,
    settings: LauncherSettings,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.launcher

    val apps by app.apps.apps.collectAsState()
    val items by app.layout.items.collectAsState()
    val arrangement by app.shells.flowFor(shell).collectAsState()

    val privateApps by app.apps.privateApps.collectAsState()
    val privateProfile by app.apps.privateProfile.collectAsState()
    val privateLocked by app.apps.privateLocked.collectAsState()
    val privateSerial by app.apps.privateSerial.collectAsState()
    var showPrivate by remember { mutableStateOf(false) }

    // The lists show every app except the ones hidden in settings, exactly as the
    // classic drawer does. Pinning never takes an app out of a list.
    val listApps = remember(apps, settings.hiddenApps) {
        apps.filterNot { it.key in settings.hiddenApps }
    }
    // Resolved exactly as the classic screen resolves an icon, so a tile is here
    // whenever that icon is drawn there. Looking tiles up by key alone, as this
    // once did, dropped anything the classic screen found by its fallbacks --
    // an app stored under an older spelling of its activity, one whose update
    // renamed that activity -- and an app pinned in a classic folder was simply
    // missing here. It is also what lets an app pinned from private space show,
    // since private apps are not in `apps` at all.
    val tiles = remember(items, apps, privateApps, privateSerial) {
        homeTiles(items) { item ->
            when (item.type) {
                ItemType.APP -> (
                    app.apps.entryFor(item.component, item.userSerial)
                        ?: app.apps.entryForPackage(
                            item.packageName ?: item.componentName?.packageName,
                            item.userSerial,
                        )
                    )?.label
                else -> item.title.ifBlank { "Shortcut" }
            }
        }
    }
    val pinnedKeys = remember(tiles) { tiles.map { it.key }.toSet() }

    var tileMenu by remember { mutableStateOf<HomeTile?>(null) }
    var appMenu by remember { mutableStateOf<AppEntry?>(null) }
    var privateMenu by remember { mutableStateOf<AppEntry?>(null) }

    /** A private app the user has asked to pin, held until they confirm it. */
    var confirmPrivatePin by remember { mutableStateOf<AppEntry?>(null) }

    // Rearrange mode lives here rather than inside each shell, so the long-press
    // menu can turn it on.
    var editing by remember(shell) { mutableStateOf(false) }

    // A private profile that has gone away must not leave its sheet on screen.
    if (privateProfile == null && showPrivate) showPrivate = false

    // Every launch from a shell comes through here, so it is the one place a
    // launch is recorded. Private space is left out on purpose: an app behind a
    // lock should not be named on the home screen a moment later.
    val launchTile: (HomeTile, Rect) -> Unit = { tile, bounds ->
        val isPrivate = privateSerial != null && tile.item.userSerial == privateSerial
        when {
            // A pinned private app while the space is locked. Starting it would
            // report success and do nothing -- the platform does not fail a start
            // in quiet mode -- so the sheet opens instead, where Unlock is.
            isPrivate && privateLocked -> showPrivate = true

            app.apps.launchItem(tile.item, bounds) -> {
                if (tile.item.type == ItemType.APP && !isPrivate) app.recents.record(tile.key)
            }

            else -> Toast.makeText(context, "${tile.label} couldn't be opened", Toast.LENGTH_SHORT).show()
        }
    }

    // App info for the profile the app is actually in. The settings intent by
    // package name always opens the personal copy's page, which for a private
    // app is the wrong app's settings, or "not installed".
    val showAppInfo: (AppEntry?, String?) -> Unit = { entry, packageName ->
        if (entry != null) app.apps.openAppInfo(entry)
        else if (packageName != null) openAppInfo(context, packageName)
    }
    val launchApp: (AppEntry, Rect) -> Unit = { entry, bounds ->
        if (app.apps.launchApp(entry, bounds)) app.recents.record(entry.key)
    }

    // Pinning puts the app on the classic home screen; unpinning takes it off.
    // Both are the shared list, so every style follows at once.
    val pin: (AppEntry) -> Unit = { entry ->
        if (app.layout.addAppToHome(entry, settings.desktopCols, settings.desktopRows)) {
            app.shells.appendEverywhere(entry.key, tiles.map { it.key })
        }
    }
    val unpin: (String) -> Unit = { key ->
        app.layout.removeFromHome(key)
        app.shells.forget(key)
    }
    val reorder: (List<String>) -> Unit = { keys -> app.shells.setOrder(shell, keys) }
    val resize: (String, TileSize) -> Unit = { key, size ->
        app.shells.setSize(HomeShell.WINDOWSPHONE, key, size)
    }
    // A long press opens the menu and a drag that follows it picks the tile up;
    // the menu has to get out of the way the moment that happens.
    val dragBegan: () -> Unit = { tileMenu = null }

    Box(Modifier.fillMaxSize()) {
        when (shell) {
            HomeShell.WINDOWSPHONE -> WinPhoneShell(
                settings = settings,
                arrangement = arrangement,
                tiles = tiles,
                apps = listApps,
                pinnedKeys = pinnedKeys,
                onLaunchTile = launchTile,
                onLaunchApp = launchApp,
                onTileMenu = { tileMenu = it },
                onAppMenu = { appMenu = it },
                onPin = pin,
                onUnpin = unpin,
                onReorder = reorder,
                onResize = resize,
                onDragBegan = dragBegan,
                onSecret = { if (privateProfile != null) showPrivate = true },
                onOpenSettings = onOpenSettings,
                editing = editing,
                onEditing = { editing = it },
            )

            HomeShell.WINDOWS11 -> Win11Shell(
                settings = settings,
                arrangement = arrangement,
                tiles = tiles,
                apps = listApps,
                pinnedKeys = pinnedKeys,
                onLaunchTile = launchTile,
                onLaunchApp = launchApp,
                onTileMenu = { tileMenu = it },
                onAppMenu = { appMenu = it },
                onPin = pin,
                onUnpin = unpin,
                onReorder = reorder,
                onDragBegan = dragBegan,
                onSecret = { if (privateProfile != null) showPrivate = true },
                onOpenSettings = onOpenSettings,
                editing = editing,
                onEditing = { editing = it },
            )

            HomeShell.CLASSIC -> Unit
        }

        tileMenu?.let { tile ->
            val close = { tileMenu = null }
            ShellMenu(title = tile.label, onDismiss = close) {
                MenuRow("Unpin from Start") { close(); unpin(tile.key) }
                if (shell == HomeShell.WINDOWSPHONE) {
                    val size = arrangement.sizeOf(tile.key)
                    MenuCaption("Tile size")
                    for (option in TileSize.entries) {
                        val label = if (option == size) "${option.label}  (current)" else option.label
                        MenuRow(label) { close(); resize(tile.key, option) }
                    }
                }
                MenuRow("Rearrange") { close(); editing = true }
                MenuRow("App info") {
                    close()
                    val item = tile.item
                    val pkg = item.packageName ?: item.componentName?.packageName
                    showAppInfo(
                        app.apps.entryFor(item.component, item.userSerial)
                            ?: app.apps.entryForPackage(pkg, item.userSerial),
                        pkg,
                    )
                }
                MenuRow("Launcher settings") { close(); onOpenSettings() }
            }
        }

        appMenu?.let { entry ->
            val close = { appMenu = null }
            val pinned = entry.key in pinnedKeys
            ShellMenu(title = entry.label, onDismiss = close) {
                if (pinned) MenuRow("Unpin from Start") { close(); unpin(entry.key) }
                else MenuRow("Pin to Start") { close(); pin(entry) }
                MenuRow("App info") { close(); showAppInfo(entry, entry.packageName) }
                MenuRow("Launcher settings") { close(); onOpenSettings() }
            }
        }

        if (showPrivate && privateProfile != null) {
            BackHandler { showPrivate = false }
            // The same order the classic sheet shows, from the same setting, so a
            // private app is in the same place whichever style opened the sheet,
            // and a drop here stays where it was dropped.
            val ordered = remember(privateApps, settings.privateOrder) {
                orderPrivateApps(privateApps, settings.privateOrder)
            }
            PrivateSpaceSheet(
                apps = ordered,
                locked = privateLocked,
                settings = settings,
                // The sheet measures in Compose coordinates; launchApp wants the
                // platform rect it hands to the system for the open animation.
                onLaunch = { entry, bounds -> app.apps.launchApp(entry, bounds.toLaunchBounds()) },
                onMenu = { entry, _ -> privateMenu = entry },
                onReorder = { order -> app.settings.update { it.copy(privateOrder = order) } },
                onUnlock = {
                    if (!app.apps.setPrivateSpaceLocked(false)) openPrivateSettings(context)
                },
                onLock = { app.apps.setPrivateSpaceLocked(true) },
                onOpenSystemSettings = { openPrivateSettings(context) },
                onDismiss = { showPrivate = false },
            )
        }

        // Pinning a private app is allowed, but asked twice: its tile stays on the
        // home screen while the space is locked, which is the one thing private
        // space otherwise never does.
        privateMenu?.let { entry ->
            val close = { privateMenu = null }
            ShellMenu(title = entry.label, onDismiss = close) {
                if (entry.key in pinnedKeys) MenuRow("Unpin from Start") { close(); unpin(entry.key) }
                else MenuRow("Pin to Start") { close(); confirmPrivatePin = entry }
                MenuRow("App info") { close(); showAppInfo(entry, entry.packageName) }
            }
        }

        confirmPrivatePin?.let { entry ->
            ConfirmDialog(
                title = "Pin ${entry.label} to Start?",
                message = PRIVATE_PIN_WARNING,
                confirmLabel = "Pin anyway",
                onConfirm = {
                    confirmPrivatePin = null
                    pin(entry)
                    showPrivate = false
                },
                onDismiss = { confirmPrivatePin = null },
            )
        }
    }
}

private fun openAppInfo(context: android.content.Context, packageName: String) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** A short menu sheet at the bottom of the screen. */
@Composable
private fun ShellMenu(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier
                    .padding(vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                content()
            }
        }
    }
}

@Composable
private fun MenuCaption(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
    )
}
