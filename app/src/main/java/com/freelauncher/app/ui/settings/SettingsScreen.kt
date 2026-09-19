package com.freelauncher.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.freelauncher.app.data.AccentColor
import com.freelauncher.app.data.AppRepository
import com.freelauncher.app.data.BackupManager
import com.freelauncher.app.data.DrawerStyle
import com.freelauncher.app.data.IconPack
import com.freelauncher.app.data.IconPacks
import com.freelauncher.app.data.IconShape
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.data.NotificationDots
import com.freelauncher.app.data.SwipeDownAction
import com.freelauncher.app.data.ThemeMode
import com.freelauncher.app.launcher
import com.freelauncher.app.nova.NovaBackupReader
import com.freelauncher.app.nova.NovaImportResult
import com.freelauncher.app.nova.NovaImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What a long-running restore or backup is currently doing. */
private sealed interface Job {
    data object Idle : Job
    class Busy(val what: String) : Job
    class Done(val title: String, val body: String) : Job
    class Failed(val message: String) : Job
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.launcher
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsState()
    val apps by app.apps.apps.collectAsState()

    val backups = remember { BackupManager(context, app.layout, app.settings) }
    var job by remember { mutableStateOf<Job>(Job.Idle) }
    var showHidden by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    // Re-checked every time this screen comes back to the foreground.
    //
    // There is no broadcast for "the default launcher changed", and the whole
    // point of the banner below is to send the user to system settings to
    // change it -- so the one moment it matters is the moment they return. A
    // LaunchedEffect(Unit) would run once and leave them staring at a banner
    // telling them to do something they have just done.
    var isDefault by remember { mutableStateOf(AppRepository.isDefaultLauncher(context)) }

    // Same reasoning as the banner above, and the same lack of any broadcast to
    // hang it on: notification access is granted in a system screen, and the
    // moment that matters is the moment the user comes back from it.
    var notificationAccess by remember { mutableStateOf(NotificationDots.hasAccess(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefault = AppRepository.isDefaultLauncher(context)
                notificationAccess = NotificationDots.hasAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun update(transform: (LauncherSettings) -> LauncherSettings) = app.settings.update(transform)

    // ---- file pickers ----------------------------------------------------

    val pickNova = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        job = Job.Busy("Reading Nova backup")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val backup = NovaBackupReader(context).read(uri)
                    NovaImporter(app.layout, app.apps, app.icons)
                        .import(backup, app.settings.value)
                }
            }
            job = outcome.fold(
                onSuccess = { (result, newSettings) ->
                    app.settings.update { newSettings }
                    app.icons.clear()
                    Job.Done("Nova backup restored", describe(result))
                },
                onFailure = { Job.Failed(it.message ?: "The backup could not be read.") },
            )
        }
    }

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME)
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        job = Job.Busy("Saving backup")
        scope.launch {
            val result = withContext(Dispatchers.IO) { backups.export(uri) }
            job = result.fold(
                onSuccess = {
                    Job.Done(
                        "Backup saved",
                        "${it.items} items across ${it.screens} " +
                            "${if (it.screens == 1) "page" else "pages"}, " +
                            "${it.icons} custom ${if (it.icons == 1) "icon" else "icons"}.",
                    )
                },
                onFailure = { Job.Failed(it.message ?: "The backup could not be written.") },
            )
        }
    }

    val restoreBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        job = Job.Busy("Restoring backup")
        scope.launch {
            val result = withContext(Dispatchers.IO) { backups.import(uri) }
            job = result.fold(
                onSuccess = {
                    app.icons.clear()
                    Job.Done(
                        "Backup restored",
                        "${it.items} items across ${it.screens} " +
                            "${if (it.screens == 1) "page" else "pages"}.\n\n" +
                            "Widgets are not restored by a backup, because a widget's " +
                            "link to the phone that made it cannot be transferred. " +
                            "Their places are kept; tap one to put a widget back.",
                    )
                },
                onFailure = { Job.Failed(it.message ?: "That file could not be restored.") },
            )
        }
    }

    // ---- screen ----------------------------------------------------------

    // A Surface, not a Box with a background colour.
    //
    // Material3 resolves an unstyled Text against LocalContentColor, and that
    // composition local defaults to black -- it is Surface that replaces it with
    // the right "on" colour for whatever it is painting. Colouring a plain Box
    // therefore produces a dark screen with black headings on it, which is
    // invisible rather than merely wrong, and only on the rows that did not
    // happen to set a colour explicitly.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 40.dp),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            item {
                Text(
                    "FreeLauncher",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 4.dp),
                )
                Text(
                    "No accounts, no keys, nothing to unlock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp, bottom = 12.dp),
                )
            }

            if (!isDefault) {
                item {
                    DefaultLauncherCard {
                        AppRepository.openHomeSettings(context)
                    }
                }
            }

            item { SectionHeader("Restore and backup") }
            item {
                ActionRow(
                    title = "Restore a Nova backup",
                    subtitle = "Reads a .novabackup file: home screens, dock, folders, " +
                        "grid size and icons.",
                    icon = Icons.Rounded.Restore,
                ) {
                    // Nova's extension has no registered MIME type, so filtering
                    // by one would hide the file the user came here to pick.
                    pickNova.launch(arrayOf("*/*"))
                }
            }
            item {
                ActionRow(
                    title = "Back up this layout",
                    subtitle = "Writes a ${BackupManager.EXTENSION} file with your pages, " +
                        "dock, folders, settings and custom icons.",
                    icon = Icons.Rounded.CloudUpload,
                ) {
                    saveBackup.launch(backups.suggestedFileName())
                }
            }
            item {
                ActionRow(
                    title = "Restore a FreeLauncher backup",
                    subtitle = "Replaces the current layout and settings.",
                    icon = Icons.Rounded.Download,
                ) {
                    restoreBackup.launch(arrayOf("*/*"))
                }
            }

            item { SectionHeader("Look") }
            item {
                ChoiceRow(
                    title = "Theme",
                    current = settings.themeMode,
                    options = ThemeMode.entries,
                    label = { it.label },
                ) { choice -> update { it.copy(themeMode = choice) } }
            }
            item { AccentRow(settings.accent) { choice -> update { it.copy(accent = choice) } } }
            item {
                ChoiceRow(
                    title = "Icon shape",
                    subtitle = "Applies everywhere: home screen, dock, folders and the " +
                        "drawer. Apps that ship an older, non-adaptive icon are drawn as " +
                        "their author made them.",
                    current = settings.iconShape,
                    options = IconShape.entries,
                    label = { it.label },
                ) { choice ->
                    update { it.copy(iconShape = choice) }
                    // Every cached bitmap was masked with the old shape.
                    app.icons.clear()
                }
            }
            item {
                // Off the main thread. Listing packs is three
                // queryIntentActivities calls and a label lookup per result --
                // binder work, done while the settings list is being composed,
                // which is exactly where a stutter is most visible.
                val packs by produceState(initialValue = emptyList<IconPack>()) {
                    value = withContext(Dispatchers.IO) { IconPacks.installed(context) }
                }
                // A pack that is set but not here.
                //
                // This is what a restored backup looks like on a new phone:
                // the setting names a pack that has not been installed yet, and
                // the list of choices cannot offer it. Keeping it in the list
                // and saying so is the only honest answer -- dropping it would
                // silently discard a setting the user had, and showing the bare
                // package name beside "no icon packs are installed" reads as
                // the screen contradicting itself.
                val chosenMissing = settings.iconPack.isNotEmpty() &&
                    packs.none { it.packageName == settings.iconPack }

                ChoiceRow(
                    title = "Icon pack",
                    subtitle = when {
                        chosenMissing ->
                            "This pack is not installed on this phone, so apps are " +
                                "using their own icons. Install it and they will " +
                                "change over."
                        packs.isEmpty() ->
                            "No icon packs are installed. Any pack from Play that " +
                                "works with Nova or ADW works here."
                        else ->
                            "Apps the pack has no icon for keep their own, dressed " +
                                "in the pack's style where it provides one."
                    },
                    current = settings.iconPack,
                    options = buildList {
                        add("")
                        if (chosenMissing) add(settings.iconPack)
                        addAll(packs.map { it.packageName })
                    },
                    label = { pkg ->
                        when {
                            pkg.isEmpty() -> "None"
                            else -> packs.firstOrNull { it.packageName == pkg }?.label
                                ?: "$pkg (not installed)"
                        }
                    },
                ) { choice ->
                    update { it.copy(iconPack = choice) }
                    // The cache is emptied by the pack loader itself, on the
                    // worker that loads it. Nothing to do here.
                }
            }
            item {
                SliderRow("Icon size", settings.iconScale, 0.7f, 1.5f) { v ->
                    update { it.copy(iconScale = v) }
                }
            }
            item {
                SliderRow("Label size", settings.labelScale, 0.8f, 1.4f) { v ->
                    update { it.copy(labelScale = v) }
                }
            }
            item {
                SliderRow("Wallpaper dimming", settings.wallpaperDim, 0f, 0.7f) { v ->
                    update { it.copy(wallpaperDim = v) }
                }
            }

            item { SectionHeader("Home screen") }
            item {
                StepperRow("Columns", settings.desktopCols, 3, 10) { v ->
                    update { it.copy(desktopCols = v) }
                }
            }
            item {
                StepperRow("Rows", settings.desktopRows, 3, 10) { v ->
                    update { it.copy(desktopRows = v) }
                }
            }
            item {
                SwitchRow("Show icon labels", settings.showDesktopLabels) { v ->
                    update { it.copy(showDesktopLabels = v) }
                }
            }
            item {
                SwitchRow(
                    "Show page dots",
                    settings.showPageIndicator,
                    subtitle = "Tap the dots to manage pages.",
                ) { v -> update { it.copy(showPageIndicator = v) } }
            }
            item {
                SwitchRow(
                    "Notification dots",
                    settings.notificationDots,
                    subtitle = when {
                        !settings.notificationDots ->
                            "A dot on apps that have something waiting. Needs " +
                                "Android's notification access."
                        notificationAccess ->
                            "On. FreeLauncher reads only which app a notification " +
                                "came from, never what it says."
                        else ->
                            "Waiting for notification access. Tap below to grant it."
                    },
                ) { v ->
                    update { it.copy(notificationDots = v) }
                    // Sent straight to the grant screen on the way on, because
                    // the switch does nothing at all without it and a switch
                    // that does nothing reads as broken rather than as
                    // unfinished.
                    if (v && !NotificationDots.hasAccess(context)) openNotificationAccess(context)
                }
            }
            if (settings.notificationDots && !notificationAccess) {
                item {
                    Row(modifier = Modifier.padding(horizontal = 14.dp)) {
                        TextButton(onClick = { openNotificationAccess(context) }) {
                            Text("Open notification access settings")
                        }
                    }
                }
            }
            item {
                SwitchRow("Hide the status bar", settings.hideStatusBar) { v ->
                    update { it.copy(hideStatusBar = v) }
                }
            }
            item {
                ChoiceRow(
                    title = "Swipe down",
                    subtitle = "Opening the notification shade uses a private system call. " +
                        "Android blocks it on some versions, and where it is blocked the " +
                        "gesture does nothing.",
                    current = settings.swipeDownAction,
                    options = SwipeDownAction.entries,
                    label = { it.label },
                ) { choice -> update { it.copy(swipeDownAction = choice) } }
            }

            item { SectionHeader("Dock") }
            item {
                SwitchRow("Show the dock", settings.dockEnabled) { v ->
                    update { it.copy(dockEnabled = v) }
                }
            }
            item {
                StepperRow("Dock columns", settings.dockCols, 2, 8) { v ->
                    update { it.copy(dockCols = v) }
                }
            }
            item {
                SwitchRow("Dock labels", settings.dockShowLabels) { v ->
                    update { it.copy(dockShowLabels = v) }
                }
            }
            item {
                SwitchRow("Dock background", settings.dockBackground) { v ->
                    update { it.copy(dockBackground = v) }
                }
            }

            item { SectionHeader("App drawer") }
            item {
                ChoiceRow(
                    title = "Layout",
                    current = settings.drawerStyle,
                    options = DrawerStyle.entries,
                    label = { it.label },
                ) { choice -> update { it.copy(drawerStyle = choice) } }
            }
            item {
                StepperRow("Drawer columns", settings.drawerCols, 3, 8) { v ->
                    update { it.copy(drawerCols = v) }
                }
            }
            item {
                SliderRow("Background opacity", settings.drawerOpacity, 0.3f, 1f) { v ->
                    update { it.copy(drawerOpacity = v) }
                }
            }
            item {
                SwitchRow("Drawer labels", settings.drawerShowLabels) { v ->
                    update { it.copy(drawerShowLabels = v) }
                }
            }
            item {
                SwitchRow("Search bar", settings.drawerSearchEnabled) { v ->
                    update { it.copy(drawerSearchEnabled = v) }
                }
            }
            item {
                SwitchRow("Open the keyboard with the drawer", settings.drawerAutoKeyboard) { v ->
                    update { it.copy(drawerAutoKeyboard = v) }
                }
            }
            item {
                ActionRow(
                    title = "Hidden apps",
                    subtitle = if (settings.hiddenApps.isEmpty()) {
                        "None hidden. Hold an app in the drawer to hide it."
                    } else {
                        "${settings.hiddenApps.size} hidden"
                    },
                    icon = null,
                ) { showHidden = true }
            }

            item { SectionHeader("Reset") }
            item {
                ActionRow(
                    title = "Reset all settings",
                    subtitle = "Puts every option back to its default. Your layout is kept.",
                    icon = null,
                    destructive = true,
                ) { confirmReset = true }
            }

            item {
                Text(
                    "FreeLauncher 1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 22.dp, top = 24.dp),
                )
            }
        }
    }

    // ---- dialogs ---------------------------------------------------------

    when (val current = job) {
        Job.Idle -> Unit

        is Job.Busy -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(current.what) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("This only takes a moment.")
                }
            },
        )

        is Job.Done -> AlertDialog(
            onDismissRequest = { job = Job.Idle },
            confirmButton = { TextButton(onClick = { job = Job.Idle }) { Text("Done") } },
            title = { Text(current.title) },
            text = { Text(current.body) },
        )

        is Job.Failed -> AlertDialog(
            onDismissRequest = { job = Job.Idle },
            confirmButton = { TextButton(onClick = { job = Job.Idle }) { Text("Close") } },
            title = { Text("That did not work") },
            text = { Text(current.message) },
        )
    }

    if (showHidden) {
        val hidden = remember(apps, settings.hiddenApps) {
            apps.filter { it.key in settings.hiddenApps }
        }
        AlertDialog(
            onDismissRequest = { showHidden = false },
            confirmButton = { TextButton(onClick = { showHidden = false }) { Text("Done") } },
            title = { Text("Hidden apps") },
            text = {
                if (settings.hiddenApps.isEmpty()) {
                    Text("Nothing is hidden. Hold an app in the drawer to hide it.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(hidden, key = { it.key }) { entry ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        update { it.copy(hiddenApps = it.hiddenApps - entry.key) }
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(entry.label, Modifier.weight(1f), maxLines = 1)
                                Text(
                                    "Show",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        // Keys whose app is no longer installed have no row
                        // above, so without this the count in settings could
                        // say "3 hidden" over a list showing one.
                        if (hidden.size < settings.hiddenApps.size) {
                            item {
                                Text(
                                    "${settings.hiddenApps.size - hidden.size} hidden " +
                                        "entries are for apps that are no longer installed.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = {
                TextButton(onClick = {
                    app.settings.resetToDefaults()
                    app.icons.clear()
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
            title = { Text("Reset all settings?") },
            text = { Text("Every option goes back to its default. Your home screen layout is not touched.") },
        )
    }
}

private fun describe(result: NovaImportResult): String = buildString {
    append("${result.apps} apps")
    if (result.folders > 0) append(", ${result.folders} folders")
    if (result.shortcuts > 0) append(", ${result.shortcuts} shortcuts")
    append(" across ${result.screens} ")
    append(if (result.screens == 1) "page" else "pages")
    append(".")
    if (result.iconsRestored > 0) {
        append("\n\n${result.iconsRestored} icons came across exactly as Nova drew them.")
    }
    if (result.missingApps.isNotEmpty()) {
        append("\n\nSkipped, because these are not installed:\n")
        append(result.missingApps.take(8).joinToString("\n") { "  $it" })
        if (result.missingApps.size > 8) append("\n  and ${result.missingApps.size - 8} more")
    }
    if (result.widgetsSkipped.isNotEmpty()) {
        append("\n\nWidgets were not restored. A widget is tied to the launcher that ")
        append("created it, and that link is not in the backup, so these have to be ")
        append("placed again from the home screen:\n")
        append(result.widgetsSkipped.joinToString("\n") { "  $it" })
    }
}

// ---- rows ----------------------------------------------------------------

/**
 * Android's notification access screen.
 *
 * There is no way to ask for this in a dialog: it is a list of every app that
 * has requested it, with a warning, and the user has to find this one and turn
 * it on. The fallback to the top-level settings app is for builds that do not
 * carry the dedicated screen.
 */
private fun openNotificationAccess(context: android.content.Context) {
    val candidates = listOf(
        android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        android.provider.Settings.ACTION_SETTINGS,
    )
    for (action in candidates) {
        val intent = android.content.Intent(action)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) continue
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 6.dp),
    )
}

@Composable
private fun RowShell(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(14.dp))
            trailing()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
    RowShell(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    RowShell(title, subtitle, onClick = onClick, titleColor = color) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * A number with plus and minus.
 *
 * A stepper rather than a slider for grid sizes. The range is six values wide;
 * a slider makes the user aim at a sixth of the track, and getting it wrong
 * reflows the entire home screen.
 */
@Composable
private fun StepperRow(title: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    RowShell(title, null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("-", enabled = value > min) { onChange(value - 1) }
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            StepButton("+", enabled = value < max) { onChange(value + 1) }
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = tint)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            // The value itself, not its position along the track. Showing the
            // fraction of the range would label the default icon size "38%",
            // which reads as something being wrong rather than as normal.
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            // Stepped rather than continuous, so the same setting can be
            // reproduced deliberately instead of landing on 0.8341 by accident.
            steps = 15,
        )
    }
}

/**
 * A fixed set of options, shown as a dialog.
 *
 * Generic over the enum so each call site keeps its own type all the way
 * through, rather than passing strings around and matching them back up.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    current: T,
    options: List<T>,
    label: (T) -> String,
    subtitle: String? = null,
    onChoose: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    RowShell(title, subtitle ?: label(current), onClick = { open = true }) {
        if (subtitle != null) {
            Text(
                label(current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            title = { Text(title) },
            text = {
                Column {
                    for (option in options) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onChoose(option)
                                    open = false
                                }
                                .padding(vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label(option), Modifier.weight(1f))
                            if (option == current) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun AccentRow(current: AccentColor, onChoose: (AccentColor) -> Unit) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("Accent", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (accent in AccentColor.entries) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(accent.rgb))
                        .clickable { onChoose(accent) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (accent == current) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = accent.label,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultLauncherCard(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "FreeLauncher is not your home screen yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Android only lets you change this from system settings. App shortcuts " +
                    "and the swipe-up drawer need it too.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text("Open home settings") }
        }
    }
}

