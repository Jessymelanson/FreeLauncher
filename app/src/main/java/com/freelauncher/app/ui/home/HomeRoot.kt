package com.freelauncher.app.ui.home

import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.freelauncher.app.data.AppEntry
import com.freelauncher.app.data.AppRepository
import com.freelauncher.app.data.Container
import com.freelauncher.app.data.FirstRun
import com.freelauncher.app.data.IconCache
import com.freelauncher.app.data.ItemType
import com.freelauncher.app.data.LauncherItem
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.data.SwipeDownAction
import com.freelauncher.app.launcher
import com.freelauncher.app.ui.drawer.AppDrawer
import com.freelauncher.app.ui.drawer.PrivateSpaceSheet
import androidx.compose.ui.graphics.asImageBitmap
import android.content.pm.ShortcutInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MAX_SCREENS = 12

/**
 * How many of an app's own shortcuts the long-press menu will show.
 *
 * Four is what the platform's own menus show, and it is also about where the
 * menu stops fitting on a short screen once the launcher's own rows are under
 * it. Publishers rank their shortcuts, so the ones that are dropped are the
 * ones they care least about.
 */
private const val MAX_APP_SHORTCUTS = 4

/**
 * The whole home surface: pages, dock, drawer and every overlay on top of them.
 *
 * One composable rather than several siblings because all of it shares one
 * coordinate space. A drag that starts on a page can end on the dock, in a
 * folder or on the remove target, and the only way to decide which is to
 * compare the finger against bounds measured in the same window. Splitting this
 * up would mean lifting those bounds into a shared holder anyway, which is
 * exactly what [HomeUiState] already is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeRoot(
    settings: LauncherSettings,
    homeResetKey: Int,
    widgetHost: LauncherWidgetHost,
    onOpenSettings: () -> Unit,
    onConfigureWidget: (Int, (Boolean) -> Unit) -> Unit,
) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val app = context.launcher
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val items by app.layout.items.collectAsState()
    val screenCount by app.layout.screenCount.collectAsState()
    val apps by app.apps.apps.collectAsState()

    // Private space. Kept out of `apps` by the repository, so nothing below
    // has to remember to filter it, and reached only through the sheet.
    val privateApps by app.apps.privateApps.collectAsState()
    val privateProfile by app.apps.privateProfile.collectAsState()
    val privateLocked by app.apps.privateLocked.collectAsState()
    val privateSerial by app.apps.privateSerial.collectAsState()
    var showPrivate by remember { mutableStateOf(false) }

    /**
     * Private space in the order the user arranged it.
     *
     * Anything they have not placed sorts after everything they have,
     * alphabetically, so installing an app into private space adds it to the
     * end instead of pushing an arrangement around.
     */
    // Shared with the other home styles, so a private app sits in the same place
    // whichever style opened the sheet.
    val orderedPrivateApps = remember(privateApps, settings.privateOrder) {
        com.freelauncher.app.data.orderPrivateApps(privateApps, settings.privateOrder)
    }

    /** An icon whose app has gone: the offer to reinstall it, or to tidy it away. */
    var missing by remember { mutableStateOf<LauncherItem?>(null) }

    /** An app the user has asked to uninstall, held until they say so twice. */
    var confirmUninstall by remember { mutableStateOf<AppEntry?>(null) }

    /** A shortcut whose publisher refused to start it. */
    var deadShortcut by remember { mutableStateOf<LauncherItem?>(null) }

    /**
     * A page the user has asked to delete, held until they say so twice.
     *
     * The same argument as uninstall, only stronger. Uninstall is one app and
     * Android asks again afterwards; this is every icon, folder and widget on
     * the page, it happens the instant the button is pressed, and the button is
     * a 24dp circle on a card the user is also expected to drag. There is no
     * undo and nothing else asks.
     */
    var confirmDeletePage by remember { mutableStateOf<Int?>(null) }

    /**
     * A folder the user has asked to remove, held until they say so twice.
     *
     * Removing a folder removes what is in it -- it has to, or its apps become
     * records pointing at nothing, invisible and impossible to get rid of. But
     * that makes one tap on a row labelled "Remove" delete a dozen icons, from
     * a menu opened by holding the folder, with nothing said and no way back.
     * An app can be put on the home screen again from the drawer; the
     * arrangement inside a folder cannot.
     */
    var confirmRemoveFolder by remember { mutableStateOf<LauncherItem?>(null) }

    /**
     * The icon waiting for a picture, while the picker is open.
     *
     * Held here rather than passed through the picker, because the result
     * arrives on a callback that outlives the menu the request came from -- the
     * menu is dismissed as the picker opens, and by the time an image comes
     * back there is nothing left to ask which icon it was for.
     */
    var iconTarget by remember { mutableStateOf<LauncherItem?>(null) }

    /** An icon being given a name of its own. */
    var renameTarget by remember { mutableStateOf<LauncherItem?>(null) }

    val ui = remember { HomeUiState() }
    val placement = remember(activity) { activity?.let { WidgetPlacement(it, widgetHost) } }

    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
    val pagerState = rememberPagerState(
        initialPage = settings.defaultPage.coerceIn(0, (screenCount - 1).coerceAtLeast(0)),
        pageCount = { screenCount },
    )

    // An estimate of one grid cell, used only to turn a widget's declared dp
    // size into a number of cells. The workspace is roughly three quarters of
    // the screen once the dock and the page dots have taken their share; being
    // a little out here costs a widget one cell of slack, which the user can
    // resize away, so it does not need to be measured exactly.
    val widgetCellW = config.screenWidthDp.dp / settings.desktopCols
    val widgetCellH = (config.screenHeightDp.dp * 0.75f) / settings.desktopRows

    /**
     * How far up the drawer is, 0 closed to 1 open.
     *
     * A plain Float, not an Animatable, and this matters more than it looks.
     *
     * Animatable serialises through a MutatorMutex: starting any new animation
     * or snapTo cancels whatever was running. Driving a drag by launching a
     * coroutine per delta therefore queues a pile of mutations that each cancel
     * the last -- and when the finger lifts, the settle animation is started
     * and then cancelled by whichever per-delta snapTo was still queued behind
     * it. The drawer stops halfway, or snaps shut, or never opens.
     *
     * That is exactly the "sometimes it works" behaviour: it is a race, so it
     * depends entirely on how fast the pointer events arrive.
     *
     * The drag writes this value straight through, synchronously, and only the
     * settle is animated -- through one job that a new drag cancels explicitly.
     */
    var drawerProgress by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var pullDown by remember { mutableFloatStateOf(0f) }

    /**
     * Derived, not computed.
     *
     * Reading drawerProgress straight from this function's body -- which is
     * what `val drawerOpen = drawerProgress > 0.5f` did -- subscribes the whole
     * of HomeRoot to a value that changes on every frame of the drawer
     * animation. Every page, every icon, the dock and every hosted widget were
     * being recomposed sixty times a second for the length of an open or close,
     * and each widget's AndroidView answered that with a binder call to push
     * its size again.
     *
     * derivedStateOf reads the float inside its own scope and only notifies
     * when the *boolean* changes, so an open costs two recompositions rather
     * than twenty. The places that genuinely need the float every frame -- the
     * workspace's fade and the drawer's offset -- read it inside graphicsLayer
     * and offset lambdas, which run at draw time and do not recompose anything.
     */
    val drawerOpen by remember { derivedStateOf { drawerProgress > 0.5f } }
    val drawerVisible by remember { derivedStateOf { drawerProgress > 0.001f } }

    // Same reasoning: the hint changes continuously while dragging, but this
    // only cares whether it is over the remove target.
    val removeActive by remember { derivedStateOf { ui.dropHint is DropTarget.Remove } }

    fun settleDrawer(target: Float, velocity: Float = 0f) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = drawerProgress,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
            ) { value, _ -> drawerProgress = value }
        }
    }

    fun dragDrawer(delta: Float) {
        settleJob?.cancel()
        drawerProgress = (drawerProgress - delta / screenHeightPx).coerceIn(0f, 1f)
    }
    var showPageEditor by remember { mutableStateOf(false) }
    var showWidgetPicker by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { ui.currentPage = it }
    }

    // Icon files nothing points at any more, cleared once per launch. Held
    // until the layout has loaded, or the sweep would see a layout with no
    // items in it and delete every icon on the phone.
    val layoutLoaded by app.layout.loaded.collectAsState()
    LaunchedEffect(layoutLoaded) {
        if (!layoutLoaded) return@LaunchedEffect
        val referenced = app.layout.items.value
            .flatMap { listOfNotNull(it.iconKey, it.chosenIconKey) }
            .toSet()
        withContext(Dispatchers.IO) { app.icons.pruneIcons(referenced) }
    }

    // Once per launch, undo any stacking a past version left behind -- and
    // again whenever the grid changes shape.
    //
    // The second half is not housekeeping. Narrowing the grid leaves every
    // icon in a dropped column sitting outside the page, drawn nowhere and
    // reachable by nothing; the settings screen already tells the user that
    // changing this "reflows the entire home screen", and this is what makes
    // that true. Keyed on the three numbers that decide what fits.
    LaunchedEffect(settings.desktopCols, settings.desktopRows, settings.dockCols) {
        app.layout.resolveOverlaps(
            cols = settings.desktopCols,
            rows = settings.desktopRows,
            dockCols = settings.dockCols,
            screens = screenCount,
        )
    }

    // Put something in the dock the very first time, once the app list has
    // actually arrived. Both conditions matter: seeding before the list loads
    // finds nothing to seed with, and seeding on any run but the first would
    // undo a deliberately emptied dock.
    val firstRun by app.layout.firstRun.collectAsState()
    val appsLoaded by app.apps.loaded.collectAsState()
    LaunchedEffect(firstRun, appsLoaded) {
        if (firstRun && appsLoaded) {
            FirstRun.seedDock(context, app.layout, app.apps, settings)
        }
    }

    // Home pressed. Everything folds away and the pager returns to the page the
    // user nominated, which is the entire contract of the Home key and the
    // thing a launcher is most noticeably wrong about when it gets it wrong.
    LaunchedEffect(homeResetKey) {
        if (homeResetKey == 0) return@LaunchedEffect
        ui.dismissOverlays()
        ui.endDrag()
        showPageEditor = false
        showWidgetPicker = false

        // Pressing Home closes private space, every time. It does not re-lock
        // it -- that is the system's business and the user's, and re-locking on
        // the way past would mean authenticating again after every app you
        // opened from in there.
        showPrivate = false
        missing = null
        confirmUninstall = null
        deadShortcut = null
        confirmDeletePage = null
        confirmRemoveFolder = null
        renameTarget = null
        settleDrawer(0f)
        runCatching {
            pagerState.animateScrollToPage(settings.defaultPage.coerceIn(0, screenCount - 1))
        }
    }

    // ---- actions ---------------------------------------------------------

    /**
     * Open an icon, or explain why it will not open.
     *
     * A launcher item outlives the app it points at, deliberately: uninstalling
     * something should not silently rearrange the home screen, and a restored
     * backup names apps this phone may never have had. The icon staying put is
     * right. Tapping it and getting nothing at all is not -- that is
     * indistinguishable from the launcher being broken, and it is what happened
     * here, because launchItem returned a false that nobody read.
     *
     * The failure is checked before it is explained. launchItem can also fail
     * on an app that *is* installed -- a shortcut whose host refused the call,
     * a profile that has just been locked -- and offering to install something
     * the user already has would be its own kind of wrong answer.
     */
    fun launch(item: LauncherItem, bounds: Rect) {
        // A private space app, while the space is locked, and this is checked
        // before the attempt rather than after it.
        //
        // Starting one does not fail. startMainActivity returns perfectly
        // normally for a profile in quiet mode and simply does not start
        // anything -- so every recovery below, which all hang off the launch
        // having reported a failure, was unreachable. The icon did nothing at
        // all, which is the same symptom as a dead shortcut and a completely
        // different cause.
        //
        // Compared by serial rather than by profile handle: a locked profile is
        // a stopped one, and turning a serial back into a handle for a stopped
        // profile can come back with nothing.
        if (privateLocked && privateSerial != null && item.userSerial == privateSerial) {
            showPrivate = true
            return
        }

        if (app.apps.launchItem(item, bounds.toAndroidRect())) return

        // A shortcut is not an app and cannot be offered from a store, so it
        // gets its own answer. Saying nothing at all is what this is replacing:
        // a tap on a shortcut whose publisher refused it did exactly nothing,
        // which is indistinguishable from the tap not registering, so people
        // tap it again.
        if (item.type == ItemType.DEEP_SHORTCUT || item.type == ItemType.SHORTCUT) {
            deadShortcut = item
            return
        }

        // An app that is genuinely not on this phone.
        //
        // Asked as "is the package here", not "is that exact activity here":
        // the launch above already falls back to the package, so reaching this
        // line with the package installed means the app is present and refused
        // to start, which is not something a store listing helps with.
        val pkg = item.packageName ?: item.componentName?.packageName ?: return
        if (app.apps.entryForPackage(pkg, item.userSerial) != null) return
        missing = item
    }

    fun closeDrawer() {
        settleDrawer(0f)
    }

    fun launchApp(entry: AppEntry, bounds: Rect) {
        app.apps.launchApp(entry, bounds.toAndroidRect())
        closeDrawer()
    }

    /**
     * Somewhere to put something [spanX] by [spanY] cells, making a page if
     * need be.
     *
     * The current page first, then every other page, then a new one. Preferring
     * the page the user is looking at is the whole point: an icon that appears
     * on page four when you were on page one has, as far as the user is
     * concerned, not appeared at all.
     */
    fun freeSlot(spanX: Int = 1, spanY: Int = 1): Triple<Int, Int, Int> {
        val cols = settings.desktopCols
        val rows = settings.desktopRows
        app.layout.findFreeArea(Container.DESKTOP, ui.currentPage, cols, rows, spanX, spanY)?.let {
            return Triple(ui.currentPage, it.first, it.second)
        }
        for (page in 0 until screenCount) {
            app.layout.findFreeArea(Container.DESKTOP, page, cols, rows, spanX, spanY)?.let {
                return Triple(page, it.first, it.second)
            }
        }
        // Every page is full. Making one is better than refusing, and better
        // than silently dropping the item where the user cannot see it.
        return Triple(app.layout.addScreen(), 0, 0)
    }

    /**
     * Choosing a picture for an icon.
     *
     * The photo picker rather than a file chooser: it needs no storage
     * permission on any version, it hands back exactly one image, and the app
     * never sees anything the user did not pick. On a device old enough to lack
     * it the contract falls back to the document picker by itself.
     */
    val pickIcon = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val item = iconTarget
        iconTarget = null
        if (uri == null || item == null) return@rememberLauncherForActivityResult
        scope.launch {
            val previous = item.chosenIconKey
            val key = withContext(Dispatchers.IO) {
                app.icons.storeImage(
                    context.contentResolver,
                    uri,
                    "chosen_${item.id}_${System.currentTimeMillis()}.png",
                )
            }
            if (key == null) {
                Toast.makeText(context, "That picture could not be used", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Read back from the store, not from the captured copy: the item
            // may have been dragged somewhere else while the picker was open,
            // and writing the stale copy back would move it.
            val current = app.layout.items.value.firstOrNull { it.id == item.id } ?: return@launch
            app.layout.update(current.copy(chosenIconKey = key))
            previous?.let { old -> withContext(Dispatchers.IO) { app.icons.deleteCustomIcon(old) } }
        }
    }

    /**
     * Put back whatever the icon was before somebody chose one.
     *
     * Only the chosen icon is cleared. What is underneath -- a shortcut's own
     * captured picture, or an imported bitmap -- is left exactly where it was,
     * which is the whole reason the two are stored separately: a pinned
     * shortcut reset this way goes back to its own icon rather than to the logo
     * of the app that published it.
     */
    fun resetIcon(item: LauncherItem) {
        val old = item.chosenIconKey ?: return
        val current = app.layout.items.value.firstOrNull { it.id == item.id } ?: return
        app.layout.update(current.copy(chosenIconKey = null))
        scope.launch { withContext(Dispatchers.IO) { app.icons.deleteCustomIcon(old) } }
    }

    /** One installed app, as a layout record. */
    fun itemFor(entry: AppEntry) = LauncherItem(
        id = app.layout.nextId(),
        type = ItemType.APP,
        title = entry.label,
        component = entry.component.flattenToString(),
        packageName = entry.packageName,
        userSerial = entry.userSerial,
    )

    fun addToHome(entry: AppEntry) {
        val (page, x, y) = freeSlot()
        app.layout.add(itemFor(entry).copy(container = Container.DESKTOP, screen = page, cellX = x, cellY = y))
    }

    /**
     * An app carried out of the drawer.
     *
     * The record is made now but not stored: it exists only as the thing under
     * the finger until the drop decides where it goes, and a drag that ends
     * nowhere leaves nothing behind. The id is taken now so that everything
     * downstream -- the preview, the drop, the folder it might land in -- has
     * one identity to work with.
     *
     * The drawer is sent away at the same moment. It covers the whole screen,
     * so carrying something out of it onto a home screen you cannot see would
     * be aiming blind.
     */
    fun dragOutOfDrawer(entry: AppEntry, at: Offset) {
        ui.beginDrag(DragSession(itemFor(entry), isNew = true), at)
        settleDrawer(0f)
    }

    /**
     * Put one of an app's own shortcuts on the home screen.
     *
     * The system is asked to pin it first and the icon is only recorded if it
     * agrees. A shortcut that is merely written into the layout is one the
     * publisher may withdraw at any moment, which would leave an icon that
     * fails to open with nothing able to say why.
     *
     * The icon is captured now because it can never be recovered later: a
     * shortcut's picture belongs to the app that published it, and if the
     * shortcut goes away so does any way of asking for it.
     */
    fun pinAppShortcut(info: ShortcutInfo) {
        scope.launch {
            val pinned = withContext(Dispatchers.IO) { app.apps.pinShortcut(info) }
            if (!pinned) {
                Toast.makeText(
                    context,
                    "Android only lets the default home app add shortcuts",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            val label = info.shortLabel?.toString()
                ?: info.longLabel?.toString().orEmpty()
            val iconKey = withContext(Dispatchers.IO) {
                app.apps.shortcutIcon(info, context.resources.displayMetrics.densityDpi)
                    ?.let { IconCache.toPng(it, app.icons.sizePx) }
                    ?.let { app.icons.writeCustomIcon("pin_${info.`package`}_${info.id}.png", it) }
            }

            val (page, x, y) = freeSlot()
            app.layout.add(
                LauncherItem(
                    id = app.layout.nextId(),
                    type = ItemType.DEEP_SHORTCUT,
                    title = label,
                    component = info.activity?.flattenToString(),
                    packageName = info.`package`,
                    shortcutId = info.id,
                    userSerial = app.apps.serialFor(info.userHandle),
                    container = Container.DESKTOP,
                    screen = page,
                    cellX = x,
                    cellY = y,
                    iconKey = iconKey,
                )
            )
            closeDrawer()
            showPrivate = false
            Toast.makeText(context, "$label added", Toast.LENGTH_SHORT).show()
        }
    }

    fun applyDrop(target: DropTarget?) {
        val session = ui.drag ?: return
        val dragged = session.item

        // Something dragged out of the drawer has no record yet, so every
        // branch below that would move or edit one has to create it instead.
        // Writing it as one flag read here rather than four separate paths
        // keeps the two kinds of drop landing in exactly the same cell.
        val isNew = session.isNew
        fun place(item: LauncherItem) {
            if (isNew) app.layout.add(item) else app.layout.update(item)
        }

        when (target) {
            // Released somewhere meaningless. An item already on the home
            // screen stays where it was; one from the drawer was never added,
            // so it simply does not arrive.
            null -> Unit

            DropTarget.Remove -> {
                // A folder with anything in it asks first, exactly as its menu
                // does. Two ways to do one thing, and only one of them
                // checking, is worse than neither checking: whichever way the
                // user happens to reach for is the one that decides whether a
                // dozen icons survive.
                // Nothing to remove: it was never placed. Carrying an app from
                // the drawer to the remove target is a way of changing your
                // mind, not a request to delete anything.
                if (isNew) return

                val holds = items.count { it.container == dragged.id }
                if (dragged.type == ItemType.FOLDER && holds > 0) {
                    confirmRemoveFolder = dragged
                    return
                }
                if (dragged.type == ItemType.WIDGET && dragged.widgetId != -1) {
                    placement?.release(dragged.widgetId)
                }
                app.layout.remove(dragged.id)
            }

            is DropTarget.Cell -> {
                // Re-checked here, not trusted from the highlight.
                //
                // The hint is computed continuously during the drag, but the
                // drop applies it later, and a drag can end in ways that do not
                // go through the finger lifting -- a page appearing underneath
                // it, the layout changing. Moving onto an occupied cell does
                // not merge or refuse, it stacks: two icons in one square, one
                // of them unreachable. Cheap to verify, and the failure is
                // silent and permanent.
                val free = app.layout.areaIsFree(
                    target.container, target.screen, target.x, target.y,
                    dragged.spanX, dragged.spanY, dragged.id,
                )
                if (free) {
                    place(
                        dragged.copy(
                            container = target.container,
                            screen = target.screen,
                            cellX = target.x,
                            cellY = target.y,
                        )
                    )
                }
            }

            is DropTarget.IntoFolder -> {
                // The folder's own column count, not a 3 written here. They
                // agree today, and the one that is a literal is the one that
                // will still say three after the other changes.
                val count = items.count { it.container == target.folderId }
                place(
                    dragged.copy(
                        container = target.folderId,
                        screen = 0,
                        cellX = count % FOLDER_GRID_COLS,
                        cellY = count / FOLDER_GRID_COLS,
                    )
                )
            }

            is DropTarget.MakeFolder -> {
                val other = items.firstOrNull { it.id == target.withItemId } ?: return
                // Folders do not nest. Dropping a folder onto an icon is far
                // more likely to be a misplaced finger than a request to lose
                // the folder, so it is ignored rather than guessed at.
                if (dragged.type == ItemType.FOLDER || dragged.type == ItemType.WIDGET) return
                if (other.type == ItemType.WIDGET) return

                val folderId = app.layout.nextId()
                app.layout.add(
                    LauncherItem(
                        id = folderId,
                        type = ItemType.FOLDER,
                        title = "",
                        container = other.container,
                        screen = other.screen,
                        cellX = other.cellX,
                        cellY = other.cellY,
                    )
                )
                app.layout.update(other.copy(container = folderId, screen = 0, cellX = 0, cellY = 0))
                place(dragged.copy(container = folderId, screen = 0, cellX = 1, cellY = 0))
            }
        }
    }

    fun placeWidget(info: AppWidgetProviderInfo, widgetId: Int) {
        val cols = settings.desktopCols
        val rows = settings.desktopRows
        val (wantX, wantY) = widgetSpan(info, widgetCellW, widgetCellH, context.resources.displayMetrics.density)
        val spanX = wantX.coerceIn(1, cols)
        val spanY = wantY.coerceIn(1, rows)

        // Asked for as a block, not a cell. A widget dropped on the first free
        // single cell sits on top of whatever else is on that page.
        val (page, x, y) = freeSlot(spanX, spanY)
        app.layout.add(
            LauncherItem(
                id = app.layout.nextId(),
                type = ItemType.WIDGET,
                title = "",
                container = Container.DESKTOP,
                screen = page,
                cellX = x.coerceAtMost(cols - spanX),
                cellY = y.coerceAtMost(rows - spanY),
                spanX = spanX,
                spanY = spanY,
                widgetId = widgetId,
                widgetProvider = info.provider.flattenToString(),
            )
        )
    }

    // The system's consent screen for binding a widget. Only needed when the
    // silent bind is refused, which is the normal case until FreeLauncher is
    // actually the default launcher.
    var pendingWidget by remember { mutableStateOf<Pair<AppWidgetProviderInfo, Int>?>(null) }
    val bindConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingWidget ?: return@rememberLauncherForActivityResult
        pendingWidget = null
        val (info, id) = pending
        val p = placement
        if (result.resultCode != Activity.RESULT_OK) {
            p?.release(id)
            return@rememberLauncherForActivityResult
        }
        if (p?.needsConfigure(id) == true) {
            onConfigureWidget(id) { ok -> if (ok) placeWidget(info, id) else p.release(id) }
        } else {
            placeWidget(info, id)
        }
    }

    fun beginWidget(info: AppWidgetProviderInfo) {
        showWidgetPicker = false
        val p = placement ?: return
        val id = p.allocate()
        if (p.bindIfAllowed(id, info.provider, info.profile)) {
            if (p.needsConfigure(id)) {
                onConfigureWidget(id) { ok -> if (ok) placeWidget(info, id) else p.release(id) }
            } else {
                placeWidget(info, id)
            }
        } else {
            pendingWidget = info to id
            bindConsent.launch(p.bindIntent(id, info.provider))
        }
    }

    /**
     * A folder with nothing left in it stops being a folder.
     *
     * One app is not a folder either: it is one app wearing a folder as a hat,
     * and the way out of that state is not obvious to anyone who has not been
     * told. So the survivor is promoted into the cell the folder was in, which
     * is where the user would look for it anyway.
     *
     * Read back from the store rather than from `items`, which is the snapshot
     * this composition was built with and does not include the edit that just
     * happened.
     */
    /**
     * Close the gaps a removal leaves in a folder's stored positions.
     *
     * The panel draws its children in sorted order and packs them, so a hole in
     * the middle is invisible on screen -- which is exactly why it is worth
     * closing. What is stored is what gets backed up, exported and read back by
     * the next thing to touch this folder, and a folder whose apps sit at 0, 1
     * and 5 is a folder that will come back looking different from the one that
     * was put away.
     */
    fun renumberFolder(folderId: Long) {
        app.layout.items.value
            .filter { it.container == folderId }
            .sortedWith(compareBy({ it.cellY }, { it.cellX }))
            .forEachIndexed { index, child ->
                val x = index % FOLDER_GRID_COLS
                val y = index / FOLDER_GRID_COLS
                if (child.cellX != x || child.cellY != y) {
                    app.layout.update(child.copy(cellX = x, cellY = y))
                }
            }
    }

    fun collapseFolder(folderId: Long) {
        val all = app.layout.items.value
        val folder = all.firstOrNull { it.id == folderId } ?: return
        if (folder.type != ItemType.FOLDER) return
        val left = all.filter { it.container == folderId }
        if (left.size > 1) return

        left.firstOrNull()?.let { last ->
            app.layout.update(
                last.copy(
                    container = folder.container,
                    screen = folder.screen,
                    cellX = folder.cellX,
                    cellY = folder.cellY,
                )
            )
        }
        app.layout.remove(folder.id)
        if (ui.openFolder == folderId) ui.openFolder = null
    }

    fun removeItem(item: LauncherItem) {
        // A widget id has to go back to the host or it stays allocated for the
        // life of the install, and the system eventually refuses to issue more.
        if (item.type == ItemType.WIDGET && item.widgetId != -1) {
            placement?.release(item.widgetId)
        }
        val parent = item.container
        app.layout.remove(item.id)

        // Removing the second-to-last app in a folder has to leave the folder
        // in a sane state, and the menu is now a second way to do that -- the
        // drag-out path had this rule inline and would have been the only one
        // that honoured it.
        if (parent != Container.DESKTOP && parent != Container.DOCK) {
            renumberFolder(parent)
            collapseFolder(parent)
        }
    }

    /**
     * Android's own private space settings.
     *
     * The platform hands out an IntentSender for this rather than an Intent,
     * and on a device that has no private space it hands out nothing -- hence
     * the fall back to the privacy settings page, which is where private space
     * is set up in the first place.
     *
     * ## Why the flags matter
     *
     * FLAG_ACTIVITY_NEW_TASK is forced onto the sender through the mask and
     * value arguments, and without it this button did nothing at all.
     *
     * startIntentSender launches into the *caller's* task, and the caller here
     * is the home screen. A launcher's task is not an ordinary one -- it is the
     * HOME task, singleTask and clearTaskOnLaunch -- and a settings screen
     * pushed onto it is not something the system will show. The call still
     * reported success, so there was nothing to fall back from and nothing to
     * log: the gear was pressed, something was started, and the screen did not
     * change.
     *
     * The web intents below are tried in turn and only until one of them
     * starts, most specific first. If none does, the user is told rather than
     * left pressing a button that does nothing -- which is the failure this is
     * fixing, and it would be a poor showing to reintroduce it one layer down.
     */
    /**
     * The settings page, by whichever route actually works.
     *
     * Resolved rather than assumed. A settings action that no build of Android
     * on this phone answers throws ActivityNotFoundException, and catching that
     * and moving on would be fine -- except the first candidate is not an
     * Intent at all, and that is where this went wrong.
     */
    fun openSettingsFallback() {
        val candidates = listOf(
            // Not in the SDK as a constant, and not present on every build, but
            // it is the page the user actually wants when it exists.
            "android.settings.PRIVATE_SPACE_SETTINGS",
            android.provider.Settings.ACTION_PRIVACY_SETTINGS,
            android.provider.Settings.ACTION_SECURITY_SETTINGS,
            android.provider.Settings.ACTION_SETTINGS,
        )
        for (action in candidates) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        Toast.makeText(context, "Couldn't open Android's settings", Toast.LENGTH_SHORT).show()
    }

    /**
     * Android's own private space settings.
     *
     * Two things had to be fixed here, and the second is the reason the first
     * was not enough.
     *
     * **The task.** startIntentSender launches into the *caller's* task, and
     * the caller is the home screen. A launcher's task is not an ordinary one
     * -- HOME, singleTask, clearTaskOnLaunch -- and a settings screen pushed
     * onto it is not something the system will show. FLAG_ACTIVITY_NEW_TASK is
     * forced on through the mask and value arguments to get it out of there.
     *
     * **The lie.** startIntentSender then reports success whether or not
     * anything appeared. The platform hands out this IntentSender on any device
     * with a private profile, including ones where the page behind it does not
     * exist, and there is no way to ask an IntentSender what it points at. So
     * the call succeeded, the early return fired, the fallbacks never ran, and
     * the gear did nothing at all -- which is exactly what it looked like.
     *
     * So the outcome is checked rather than trusted: if this window still has
     * focus a moment later, nothing opened, and the ordinary settings intents
     * are tried instead. A wrong guess here costs a settings screen opening
     * twice, which is visible and harmless; the alternative costs a button that
     * silently does nothing, which is neither.
     */
    fun openPrivateSettings() {
        val sender = app.apps.privateSpaceSettingsIntent()
        val started = sender != null && activity != null && runCatching {
            activity.startIntentSender(
                sender,
                null,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                0,
            )
        }.isSuccess

        if (!started) {
            openSettingsFallback()
            return
        }

        scope.launch {
            delay(700)
            if (activity?.hasWindowFocus() == true) openSettingsFallback()
        }
    }

    fun resize(item: LauncherItem, dx: Int, dy: Int) {
        val spanX = (item.spanX + dx).coerceIn(1, settings.desktopCols - item.cellX)
        val spanY = (item.spanY + dy).coerceIn(1, settings.desktopRows - item.cellY)

        // Growing is refused when it would cover something; shrinking always
        // works. Without the check a widget can be stretched over a row of
        // icons, which does not delete them but does make them unreachable --
        // and the only way back is to guess that the widget is on top.
        val growing = spanX > item.spanX || spanY > item.spanY
        if (growing && !app.layout.areaIsFree(
                item.container, item.screen, item.cellX, item.cellY, spanX, spanY, item.id,
            )
        ) {
            return
        }
        app.layout.update(item.copy(spanX = spanX, spanY = spanY))
    }

    // ---- drag bookkeeping ------------------------------------------------

    LaunchedEffect(ui.isDragging) {
        if (!ui.isDragging) return@LaunchedEffect
        // Recomputed continuously while a drag is live. Doing it in the drag
        // callback instead would tie the highlight to how fast the finger
        // reports, so it would stall the moment the finger stopped moving.
        //
        // The page and the page's bounds are read here as well as the finger,
        // and that is the important part. Holding an item at the edge turns the
        // page *without the finger moving*, so keying on position alone left
        // the drop pointing at the page the drag came from: carrying a widget
        // onto a new page dropped it one page short, every time.
        snapshotFlow { Triple(ui.dragPosition, ui.currentPage, ui.pageBounds) }
            .collect { (pos, _, _) ->
                ui.dropHint = resolveDrop(pos, ui, items, settings)
            }
    }

    // Carrying an icon to the edge turns the page, and carrying it past the
    // last page makes a new one. That second half is the only way to create a
    // page with an icon already on it, which is what people reach for when a
    // page fills up.
    LaunchedEffect(ui.isDragging) {
        if (!ui.isDragging) return@LaunchedEffect
        val edgePx = with(density) { 44.dp.toPx() }

        // At most one new page per drag. Without this, resting against the
        // right edge of the last page creates a page, scrolls to it, finds
        // itself against the right edge of the new last page, and creates
        // another -- every 550 ms until the twelve-page cap. One held drag
        // could leave ten empty pages behind it.
        var madeAPage = false

        while (true) {
            delay(550)
            if (!ui.isDragging) break
            val bounds = ui.pageBounds
            if (bounds.width <= 0f) continue
            val x = ui.dragPosition.x
            when {
                x < bounds.left + edgePx && pagerState.currentPage > 0 ->
                    runCatching { pagerState.animateScrollToPage(pagerState.currentPage - 1) }

                x > bounds.right - edgePx -> when {
                    pagerState.currentPage < screenCount - 1 ->
                        runCatching { pagerState.animateScrollToPage(pagerState.currentPage + 1) }

                    !madeAPage && screenCount < MAX_SCREENS -> {
                        madeAPage = true
                        val added = app.layout.addScreen()
                        runCatching { pagerState.animateScrollToPage(added) }
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * Closing the drawer by dragging down inside it.
     *
     * The drawer's grid is a scrolling container, so it takes ownership of every
     * vertical drag that starts on it and the launcher's own drag handler never
     * sees one. That left no way to dismiss the drawer by swiping: the gesture
     * just scrolled a list that was already at the top, and did nothing.
     *
     * A nested scroll connection is how a child hands back what it could not
     * use. onPostScroll fires with whatever the grid did not consume, which at
     * the top of the list is the whole downward drag, and that is exactly the
     * amount the drawer should come down by.
     */
    val drawerNestedScroll = remember(screenHeightPx) {
        object : NestedScrollConnection {

            /**
             * Whether the list has used any of the gesture in progress.
             *
             * This is what keeps a scroll from turning into a dismiss. Swiping
             * down hard from near the bottom scrolls the list to the top and
             * still has travel left over; handing that leftover to the drawer
             * threw the whole drawer off the screen when the user was only
             * trying to get back to the top of their apps.
             *
             * So ownership is decided once per gesture and does not change: if
             * the list moved at all, the rest of that gesture is the list's,
             * and it simply stops at the top. Lifting off and swiping again --
             * now with the list already at the top, so it consumes nothing --
             * closes the drawer. That is the same rule every bottom sheet on
             * the platform follows, and the reason it is a rule is that the
             * alternative loses the user's place with no way to get it back.
             */
            private var listOwnsGesture = false

            /**
             * Whether this gesture is carrying an icon rather than moving the
             * drawer.
             *
             * Dragging an app out of the drawer starts as an ordinary drag on a
             * scrolling list, so everything here is offered it: the list is
             * already at the top, reports the whole downward movement as
             * unused, and this connection dutifully spent it on the drawer.
             *
             * That did two things, and the second was the visible one. It
             * cancelled the settle that was closing the drawer -- dragDrawer
             * cancels whatever animation is running -- and then, when the
             * finger lifted, onPreFling found a drawer still almost fully up,
             * read that as an attempt to open it, and animated it back over the
             * home screen the icon had just been dropped on.
             *
             * So the app really was placed, and the drawer really did stay in
             * front of it. Nothing failed anywhere; the drag simply appeared to
             * have done nothing at all.
             */
            private val carryingAnIcon: Boolean get() = ui.isDragging

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (carryingAnIcon) return Offset.Zero
                // Dragging back up while the drawer is part way down should
                // raise the drawer again, not scroll the list underneath it.
                if (available.y < 0f && drawerProgress < 1f && !listOwnsGesture) {
                    dragDrawer(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (carryingAnIcon) return Offset.Zero
                if (consumed.y != 0f) listOwnsGesture = true

                // Downward leftover only, only while the drawer is up, and only
                // if the list never claimed this gesture.
                if (listOwnsGesture) return Offset.Zero
                if (available.y <= 0f || drawerProgress <= 0f) return Offset.Zero

                dragDrawer(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (carryingAnIcon) return Velocity.Zero

                // The list had this one, or the drawer never moved. Either way
                // the fling is not ours; let it scroll.
                if (listOwnsGesture || drawerProgress >= 1f) return Velocity.Zero

                // Same thresholds as the home screen's own drawer drag, so a
                // flick means the same thing whichever surface it started on.
                val target = if (available.y > 450f || drawerProgress < 0.75f) 0f else 1f
                settleDrawer(target, -available.y / screenHeightPx)
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // The gesture is over -- including the list's own fling, which
                // is why the flag is cleared here and not on release. Resetting
                // any earlier would let the tail of a fling that overshot the
                // top start dragging the drawer down.
                listOwnsGesture = false
                return Velocity.Zero
            }
        }
    }

    // ---- back --------------------------------------------------------------

    /**
     * Back closes whatever is open, and otherwise does nothing at all.
     *
     * Always enabled, including when there is nothing to close. That is the
     * point: without a handler, Back on a home screen calls finish(), and since
     * this activity is HOME the system immediately restarts it. The user sees a
     * flash and the launcher rebuilds its whole composition for no reason.
     *
     * Dialogs and the menu popup are separate windows and dismiss themselves,
     * so the cases here are the ones drawn inside this window.
     */
    BackHandler(enabled = true) {
        when {
            showWidgetPicker -> showWidgetPicker = false
            showPageEditor -> showPageEditor = false
            ui.menu != null -> ui.menu = null
            showPrivate -> showPrivate = false
            // Back out of the picker reopens the folder it was launched from,
            // rather than dropping the user on the home screen having lost the
            // thing they were editing.
            ui.selectAppsFor != null -> {
                ui.openFolder = ui.selectAppsFor
                ui.selectAppsFor = null
            }

            ui.openFolder != null -> ui.openFolder = null
            ui.resizing != null -> ui.resizing = null
            drawerProgress > 0f -> closeDrawer()
            else -> Unit
        }
    }

    // ---- layout ----------------------------------------------------------

    Box(
        Modifier
            .fillMaxSize()
            /**
             * Follows the finger for the whole of a drag, and finishes it.
             *
             * The icon that started the drag only reports that one began; from
             * there this takes over. It has to, because the icon does not
             * survive: carrying an item to the edge of the last page creates a
             * page and scrolls to it, and the page the drag started on then
             * falls outside the pager's composed window and is disposed,
             * cancelling any gesture still running inside it. That left the
             * preview stuck to the finger with nothing able to put it down.
             *
             * This node is an ancestor of every icon, so it is in the hit path
             * for the gesture from the first touch and is never disposed part
             * way through. Nothing is consumed unless a drag is actually in
             * progress, so ordinary taps, page swipes and the drawer's own
             * vertical drag are unaffected.
             */
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var carried = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (ui.isDragging) {
                            carried = true
                            ui.dragPosition += change.positionChange()
                            change.consume()
                        }
                    }
                    if (carried || ui.isDragging) {
                        applyDrop(ui.dropHint)
                        ui.endDrag()
                    }
                }
            }
            .draggable(
                orientation = Orientation.Vertical,
                enabled = !ui.isDragging,
                state = rememberDraggableState { delta ->
                    // An icon is being carried; the finger belongs to it.
                    if (ui.isDragging) return@rememberDraggableState

                    if (drawerProgress <= 0f && delta > 0f) {
                        // Closed and pulling down: this is the swipe-down
                        // gesture, not a drawer drag. Accumulated and acted on
                        // at the end, so a stray downward wobble at the start
                        // of a swipe up does not trigger it.
                        pullDown += delta
                    } else {
                        dragDrawer(delta)
                    }
                },
                onDragStopped = { velocity ->
                    // Nothing to settle when an icon is being carried, and this
                    // is not a corner case -- it is what happens every time
                    // something is dragged out of the drawer.
                    //
                    // Starting that drag flips `enabled` to false, and a
                    // draggable disabled part way through a gesture ends it,
                    // which lands here. The settle below then read a drawer
                    // that was still most of the way up, decided the gesture
                    // had been an attempt to open it, and animated it back --
                    // over the home screen the icon had just been dropped on.
                    // The icon really had been placed; it was simply behind the
                    // drawer, so the drag looked like it had done nothing.
                    if (ui.isDragging) {
                        pullDown = 0f
                        return@draggable
                    }

                    val threshold = with(density) { 90.dp.toPx() }
                    if (pullDown > threshold && drawerProgress <= 0f) {
                        performSwipeDown(activity, settings.swipeDownAction) {
                            settleDrawer(1f)
                        }
                    } else {
                        // Deliberately easy to commit. At the first attempt a
                        // swipe had to cover 40% of the screen height or carry
                        // 900 px/s to open the drawer, and an ordinary
                        // thumb-flick from the middle of the display lands at
                        // about 39% -- right on the line, so the same gesture
                        // opened it or did nothing depending on the frame.
                        val target = when {
                            velocity < -450f -> 1f
                            velocity > 450f -> 0f
                            drawerProgress > 0.25f -> 1f
                            else -> 0f
                        }
                        // The fling's own velocity is handed to the spring, so
                        // a hard flick finishes fast and a lazy one drifts.
                        settleDrawer(target, -velocity / screenHeightPx)
                    }
                    pullDown = 0f
                },
            )
    ) {
        // Sits under everything the launcher draws and over the wallpaper the
        // window manager draws behind the window.
        if (settings.wallpaperDim > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = settings.wallpaperDim))
            )
        }

        // The workspace fades and sinks slightly as the drawer rises, so the
        // two read as one movement rather than a sheet sliding over a static
        // picture.
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 1f - drawerProgress
                    scaleX = 1f - 0.04f * drawerProgress
                    scaleY = 1f - 0.04f * drawerProgress
                }
                // The status bar's height even when it is hidden, so there is
                // always a strip above the grid for the remove target. With the
                // bar hidden the grid used to start at the very top, leaving
                // nowhere to drop an icon that was not also a cell.
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .navigationBarsPadding(),
        ) {
            Workspace(
                pagerState = pagerState,
                items = items,
                settings = settings,
                ui = ui,
                widgetHost = widgetHost,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onLaunch = ::launch,
                onOpenFolder = { item, at -> ui.folderAnchor = at; ui.openFolder = item.id },
                onLongPress = { item, anchor -> ui.menu = MenuTarget.Placed(item, anchor) },
                onBlankLongPress = { page -> ui.menu = MenuTarget.Blank(page) },
                onDragBegin = { session, at -> ui.beginDrag(session, at) },
                onResize = ::resize,
                showEmptyHint = items.none { it.container == Container.DESKTOP },
                onReplaceWidget = { showWidgetPicker = true },
            )

            if (settings.showPageIndicator) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { showPageEditor = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    PageIndicator(count = screenCount, current = ui.currentPage)
                }
            }

            if (settings.dockEnabled) {
                Dock(
                    items = items,
                    settings = settings,
                    ui = ui,
                    modifier = Modifier.padding(bottom = 8.dp),
                    onLaunch = ::launch,
                    onOpenFolder = { item, at -> ui.folderAnchor = at; ui.openFolder = item.id },
                    onLongPress = { item, anchor -> ui.menu = MenuTarget.Placed(item, anchor) },
                    onDragBegin = { session, at -> ui.beginDrag(session, at) },
                )
            }
        }

        // ---- drawer ------------------------------------------------------

        if (drawerVisible) {
            AppDrawer(
                apps = apps,
                settings = settings,
                isOpen = drawerOpen,
                nestedScroll = drawerNestedScroll,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, ((1f - drawerProgress) * screenHeightPx).roundToInt()) },
                onLaunch = ::launchApp,
                onLongPress = { entry, anchor -> ui.menu = MenuTarget.Drawer(entry, anchor) },
                onDragOut = ::dragOutOfDrawer,
                dragging = ui.isDragging,
                onDismiss = ::closeDrawer,
                // Null on a phone with no private profile, which is what keeps
                // the gesture from existing at all there.
                onPrivateSpace = if (privateProfile != null) ({ showPrivate = true }) else null,
            )
        }

        // A private space that has been deleted takes its panel with it.
        //
        // It can be deleted from system settings while the launcher sits in the
        // background, and the launcher is not told in any way it would
        // otherwise notice: no package in *this* user appears or disappears, so
        // the package callbacks never fire. Without this the panel stayed open
        // over a profile that no longer existed, still listing its apps from
        // memory -- which is the exact opposite of what a private space is for.
        LaunchedEffect(privateProfile) {
            if (privateProfile == null) showPrivate = false
        }

        if (showPrivate && privateProfile != null) {
            // Asked again on the way in, for the same reason and one more: the
            // lock can change while the launcher is in the background, because
            // the device locking re-locks private space, and nothing broadcasts
            // that to an app that was not running to hear it.
            LaunchedEffect(Unit) {
                app.apps.refreshPrivateLock()
                app.apps.refresh()
            }

            PrivateSpaceSheet(
                apps = orderedPrivateApps,
                locked = privateLocked,
                settings = settings,
                onLaunch = { entry, bounds ->
                    showPrivate = false
                    launchApp(entry, bounds)
                },
                // Straight to App info, with no menu in between.
                //
                // The drawer's menu is wrong here and one of its rows is
                // actively dangerous: "Add to home screen" would put a private
                // app's icon on the home screen, where the whole point is that
                // it does not appear. App info is the useful half of that menu
                // anyway, and it is the system page that carries Uninstall.
                onMenu = { entry, anchor -> ui.menu = MenuTarget.Private(entry, anchor) },

                // Written as the whole order rather than a moved pair, so the
                // stored list always says exactly what is on screen and never
                // has to be reconciled with it.
                onReorder = { keys ->
                    app.settings.update { it.copy(privateOrder = keys) }
                },
                onUnlock = {
                    // A refusal here means the launcher does not hold
                    // MODIFY_QUIET_MODE, which is granted with the
                    // default-launcher role. Sending the user to the system's
                    // own private space page is the honest fallback: it is the
                    // one place the lock can always be lifted.
                    if (!app.apps.setPrivateSpaceLocked(false)) openPrivateSettings()
                },
                onLock = { app.apps.setPrivateSpaceLocked(true) },
                onOpenSystemSettings = ::openPrivateSettings,
                onDismiss = { showPrivate = false },
            )
        }

        // ---- drag furniture ----------------------------------------------

        // In the strip above the grid, which is where a drop removes.
        val removeStrip = WindowInsets.statusBarsIgnoringVisibility
            .asPaddingValues().calculateTopPadding()
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onGloballyPositioned { ui.removeTargetBounds = it.boundsInWindow() },
        ) {
            RemoveTarget(
                active = removeActive,
                ui = ui,
                height = (removeStrip - 4.dp).coerceAtLeast(22.dp),
            )
        }

        ui.drag?.let { session ->
            val dragged = session.item
            val children = remember(items, dragged.id) {
                items.filter { it.container == dragged.id }
            }

            // A widget is carried as a ghost of the space it will occupy, not
            // as an icon.
            //
            // It used to be drawn through the icon path at `56.dp * iconScale`,
            // which was wrong twice over: a widget has no icon to draw, so the
            // preview was an empty square, and its size followed the icon size
            // slider -- so widgets appeared to grow when the user resized their
            // icons. Carrying a five-by-three clock as a blank 60dp box is also
            // why dropping one on another page was impossible to aim.
            val isWidget = dragged.type == ItemType.WIDGET
            val cellWpx = ui.pageBounds.width / settings.desktopCols
            val cellHpx = ui.pageBounds.height / settings.desktopRows
            val iconPx = with(density) { (56.dp * settings.iconScale * 1.1f).toPx() }

            val previewW = if (isWidget && cellWpx > 0f) cellWpx * dragged.spanX else iconPx
            val previewH = if (isWidget && cellHpx > 0f) cellHpx * dragged.spanY else iconPx

            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (ui.dragPosition.x - previewW / 2f).roundToInt(),
                            (ui.dragPosition.y - previewH / 2f).roundToInt(),
                        )
                    }
                    .alpha(0.85f)
            ) {
                if (isWidget) {
                    WidgetGhost(
                        width = with(density) { previewW.toDp() },
                        height = with(density) { previewH.toDp() },
                    )
                } else {
                    DragPreview(session, children, settings, 56.dp * settings.iconScale)
                }
            }
        }

        // ---- overlays ----------------------------------------------------

        ui.openFolder?.let { folderId ->
            val folder = items.firstOrNull { it.id == folderId }
            if (folder == null) {
                ui.openFolder = null
            } else {
                FolderOverlay(
                    folder = folder,
                    children = items.filter { it.container == folderId }
                        .sortedWith(compareBy({ it.cellY }, { it.cellX })),
                    settings = settings,
                    anchor = ui.folderAnchor,
                    onDismiss = { ui.openFolder = null },
                    onLaunch = { child, bounds ->
                        ui.openFolder = null
                        launch(child, bounds)
                    },
                    onRename = { app.layout.update(folder.copy(title = it)) },
                    onTakeOut = { child ->
                        val (page, x, y) = freeSlot()
                        app.layout.update(
                            child.copy(
                                container = Container.DESKTOP,
                                screen = page,
                                cellX = x,
                                cellY = y,
                            )
                        )
                        renumberFolder(folder.id)
                        collapseFolder(folder.id)
                    },

                    // Written back as positions, the same as a sort. The order
                    // a folder is in is just where its apps sit, so a rearrange
                    // and a sort are the same edit arriving from two places.
                    // A hold inside a folder, released without moving. The
                    // folder stays open behind the menu: the app is still in
                    // it, and closing the panel to answer a question about one
                    // of its icons loses the place the user was in.
                    onItemMenu = { child, at -> ui.menu = MenuTarget.Placed(child, at) },

                    onReorder = { ids ->
                        val byId = app.layout.items.value.associateBy { it.id }
                        ids.forEachIndexed { index, id ->
                            val child = byId[id] ?: return@forEachIndexed
                            val x = index % FOLDER_GRID_COLS
                            val y = index / FOLDER_GRID_COLS
                            if (child.cellX != x || child.cellY != y) {
                                app.layout.update(child.copy(cellX = x, cellY = y))
                            }
                        }
                    },

                    // Alphabetical, and written back as positions rather than
                    // held as a sort order. A folder's order is the user's to
                    // rearrange by dragging, so a stored "sorted" flag would
                    // have to decide what happens the next time they do -- this
                    // way sorting is a one-off edit that leaves the folder
                    // exactly as rearrangeable as it was.
                    onSort = {
                        val sorted = app.layout.items.value
                            .filter { it.container == folder.id }
                            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                        sorted.forEachIndexed { index, child ->
                            app.layout.update(
                                child.copy(
                                    cellX = index % FOLDER_GRID_COLS,
                                    cellY = index / FOLDER_GRID_COLS,
                                )
                            )
                        }
                    },
                    onSelectApps = {
                        ui.selectAppsFor = folder.id
                        ui.openFolder = null
                    },
                )
            }
        }

        ui.selectAppsFor?.let { folderId ->
            val folder = items.firstOrNull { it.id == folderId }
            if (folder == null) {
                ui.selectAppsFor = null
            } else {
                val inFolder = remember(items, folderId) {
                    items.filter { it.container == folderId }
                        .mapNotNull { child ->
                            child.component?.let { AppEntry.keyOf(it, child.userSerial) }
                        }
                        .toSet()
                }

                fun back() {
                    ui.selectAppsFor = null
                    ui.openFolder = folderId
                }

                FolderAppPicker(
                    folderTitle = folder.title,
                    apps = apps,
                    initiallyIn = inFolder,
                    shape = settings.iconShape,
                    onCancel = ::back,
                    onConfirm = { wanted ->
                        val current = app.layout.items.value.filter { it.container == folderId }

                        // Removed: dropped, not turned out onto the desktop.
                        // The app is still in the drawer, and a list of ticks
                        // says what the folder contains rather than where any
                        // one app should go next.
                        for (child in current) {
                            val key = child.component?.let { AppEntry.keyOf(it, child.userSerial) }
                            if (key == null || key !in wanted) app.layout.remove(child.id)
                        }

                        // Added: appended after whatever survived, in the order
                        // the list showed them, so the result matches what the
                        // user was just looking at.
                        val kept = app.layout.items.value
                            .filter { it.container == folderId }
                            .sortedWith(compareBy({ it.cellY }, { it.cellX }))
                        val keptKeys = kept.mapNotNull { child ->
                            child.component?.let { AppEntry.keyOf(it, child.userSerial) }
                        }.toSet()

                        var slot = kept.size
                        for (entry in apps) {
                            if (entry.key !in wanted || entry.key in keptKeys) continue
                            app.layout.add(
                                LauncherItem(
                                    id = app.layout.nextId(),
                                    type = ItemType.APP,
                                    title = entry.label,
                                    component = entry.component.flattenToString(),
                                    packageName = entry.packageName,
                                    userSerial = entry.userSerial,
                                    container = folderId,
                                    cellX = slot % FOLDER_GRID_COLS,
                                    cellY = slot / FOLDER_GRID_COLS,
                                )
                            )
                            slot++
                        }

                        // Renumber, so a folder edited down to three apps does
                        // not keep the holes the removed ones left behind.
                        app.layout.items.value
                            .filter { it.container == folderId }
                            .sortedWith(compareBy({ it.cellY }, { it.cellX }))
                            .forEachIndexed { index, child ->
                                app.layout.update(
                                    child.copy(
                                        cellX = index % FOLDER_GRID_COLS,
                                        cellY = index / FOLDER_GRID_COLS,
                                    )
                                )
                            }

                        // The same rule a take-out follows: a folder holding
                        // one app is not a folder, and an empty one is nothing
                        // at all.
                        val left = app.layout.items.value.filter { it.container == folderId }
                        ui.selectAppsFor = null
                        when (left.size) {
                            1 -> {
                                app.layout.update(
                                    left.first().copy(
                                        container = folder.container,
                                        screen = folder.screen,
                                        cellX = folder.cellX,
                                        cellY = folder.cellY,
                                    )
                                )
                                app.layout.remove(folder.id)
                            }

                            0 -> app.layout.remove(folder.id)
                            else -> ui.openFolder = folderId
                        }
                    },
                )
            }
        }

        ui.menu?.let { target ->
            // Both of these are remembered against the menu that is open, not
            // recomputed per recomposition: resolving the entry is a scan of
            // every installed app and canUninstall is a binder call, and a menu
            // stays open across plenty of recompositions.
            val entry = remember(target) {
                when (target) {
                    is MenuTarget.Drawer -> target.entry
                    is MenuTarget.Private -> target.entry
                    // Falling back to the package is what gives a shortcut a
                    // working menu. A shortcut records who published it and no
                    // component, so the component lookup finds nothing -- and
                    // "App info" sat there as a row that did nothing at all
                    // when tapped, on exactly the icons most likely to need it.
                    // The package resolves to the publishing app, which is the
                    // one whose settings page the user wants.
                    is MenuTarget.Placed ->
                        app.apps.entryFor(target.item.component, target.item.userSerial)
                            ?: app.apps.entryForPackage(
                                target.item.packageName,
                                target.item.userSerial,
                            )
                    is MenuTarget.Blank -> null
                }
            }
            val uninstallable = remember(entry) { entry != null && app.apps.canUninstall(entry) }
            val placedItem = (target as? MenuTarget.Placed)?.item

            /**
             * The app's own shortcuts, fetched before the menu is drawn.
             *
             * Null means "not asked yet", and the menu waits for it. That is
             * deliberate: where the menu opens is arithmetic on how many rows
             * it has, so a menu that appeared with four rows and then grew to
             * eight would jump away from the icon it belongs to, under the
             * finger that opened it. Waiting costs one binder round trip, and
             * the query is skipped outright -- resolving to an empty list in
             * the same frame -- when there is nothing to ask or nobody allowed
             * to ask it.
             */
            val shortcuts by produceState<List<AppShortcut>?>(null, target, entry) {
                val subject = entry
                val wants = subject != null &&
                    (target is MenuTarget.Placed || target is MenuTarget.Drawer) &&
                    (placedItem == null || placedItem.type == ItemType.APP)

                if (!wants || !app.apps.hasShortcutPermission()) {
                    value = emptyList()
                    return@produceState
                }

                value = withContext(Dispatchers.IO) {
                    app.apps.shortcutsFor(subject!!)
                        // Ranked by the publisher, and the platform's own menus
                        // show four. Past that the menu is taller than the
                        // screen and the rows at the bottom are the launcher's
                        // own, which is the wrong thing to push off the edge.
                        .sortedBy { it.rank }
                        .take(MAX_APP_SHORTCUTS)
                        .map { info ->
                            val bitmap = app.apps
                                .shortcutIcon(info, context.resources.displayMetrics.densityDpi)
                                ?.let {
                                    runCatching { IconCache.toBitmap(it, 96).asImageBitmap() }
                                        .getOrNull()
                                }
                            AppShortcut(
                                id = info.id,
                                label = info.shortLabel?.toString()
                                    ?: info.longLabel?.toString()
                                    ?: info.id,
                                icon = bitmap,
                                start = { bounds ->
                                    app.apps.startShortcutInfo(info, bounds.toAndroidRect())
                                    closeDrawer()
                                },
                                pin = { pinAppShortcut(info) },
                            )
                        }
                }
            }

            val ready = shortcuts
            val canChangeIcon = placedItem != null && (
                placedItem.type == ItemType.APP ||
                    placedItem.type == ItemType.SHORTCUT ||
                    placedItem.type == ItemType.DEEP_SHORTCUT
                )

            if (ready != null) ItemMenu(
                target = target,
                canUninstall = uninstallable,
                shortcuts = ready,
                canChangeIcon = canChangeIcon,
                canRename = placedItem != null && placedItem.type != ItemType.WIDGET,
                hasChosenIcon = placedItem?.chosenIconKey != null,
                // Only for a widget that allows it. One that declares no resize
                // mode would open a frame whose handles could do nothing.
                canResize = placedItem?.type == ItemType.WIDGET && runCatching {
                    android.appwidget.AppWidgetManager.getInstance(context)
                        .getAppWidgetInfo(placedItem.widgetId)
                        ?.resizeMode != android.appwidget.AppWidgetProviderInfo.RESIZE_NONE
                }.getOrDefault(false),
                onDismiss = { ui.menu = null },
                onAppInfo = {
                    ui.menu = null
                    entry?.let { app.apps.openAppInfo(it) }
                },
                onRemove = {
                    ui.menu = null
                    placedItem?.let { item ->
                        // Only a folder with something in it asks. An empty one
                        // has nothing to lose, and every other kind of item is
                        // one icon that can be put back.
                        val holds = items.count { it.container == item.id }
                        if (item.type == ItemType.FOLDER && holds > 0) {
                            confirmRemoveFolder = item
                        } else {
                            removeItem(item)
                        }
                    }
                },
                onUninstall = {
                    ui.menu = null
                    // Asked twice on purpose. Uninstall sits one row below
                    // Remove in a menu that opens under the finger that opened
                    // it, on a surface people spend their time dragging things
                    // around on -- and it is the only entry here that cannot be
                    // undone by putting the icon back.
                    entry?.let { confirmUninstall = it }
                },
                onHide = {
                    ui.menu = null
                    entry?.let { e -> app.settings.update { it.copy(hiddenApps = it.hiddenApps + e.key) } }
                },
                onAddToHome = {
                    ui.menu = null
                    entry?.let {
                        addToHome(it)
                        closeDrawer()
                        // A private app's icon is being put somewhere visible,
                        // so the panel it came from gets out of the way and
                        // lets the user see where it landed.
                        showPrivate = false
                    }
                },
                onResize = {
                    ui.menu = null
                    placedItem?.let { ui.resizing = it.id }
                },
                onChangeIcon = {
                    ui.menu = null
                    placedItem?.let { item ->
                        iconTarget = item
                        runCatching {
                            pickIcon.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }.onFailure {
                            iconTarget = null
                            Toast.makeText(
                                context,
                                "No app on this phone can pick a picture",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                onResetIcon = {
                    ui.menu = null
                    placedItem?.let { resetIcon(it) }
                },
                onRename = {
                    ui.menu = null
                    renameTarget = placedItem
                },
                onWallpaper = {
                    ui.menu = null
                    activity?.let { host ->
                        runCatching {
                            host.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SET_WALLPAPER),
                                    "Set wallpaper",
                                )
                            )
                        }
                    }
                },
                onWidgets = {
                    ui.menu = null
                    showWidgetPicker = true
                },
                onEditPages = {
                    ui.menu = null
                    showPageEditor = true
                },
                onSettings = {
                    ui.menu = null
                    onOpenSettings()
                },
            )
        }

        confirmUninstall?.let { entry ->
            ConfirmDialog(
                title = "Uninstall ${entry.label}?",
                message = "It will be removed from this phone along with its data. " +
                    "Android will ask you to confirm as well.",
                confirmLabel = "Uninstall",
                destructive = true,
                onConfirm = {
                    confirmUninstall = null
                    app.apps.uninstall(entry)
                },
                onDismiss = { confirmUninstall = null },
            )
        }

        deadShortcut?.let { item ->
            val name = item.title.ifBlank { "This shortcut" }

            // Asked once per dialog, not per recomposition: this is a binder
            // call into the shortcut service.
            val (state, disabledMessage) = remember(item.id) {
                if (item.type == ItemType.DEEP_SHORTCUT) {
                    app.apps.describeShortcut(item)
                } else {
                    AppRepository.ShortcutState.UNKNOWN to null
                }
            }

            val explanation = when (state) {
                AppRepository.ShortcutState.MISSING ->
                    "The app that made it no longer has this shortcut. That usually means " +
                        "the page or item behind it was deleted inside that app."

                AppRepository.ShortcutState.DISABLED ->
                    disabledMessage?.takeIf { it.isNotBlank() }
                        ?: "The app that made it has turned this shortcut off."

                AppRepository.ShortcutState.AVAILABLE ->
                    "The app that made it still lists this shortcut but refused to open it. " +
                        "Opening that app once and trying again often clears this."

                AppRepository.ShortcutState.UNKNOWN ->
                    "The app that made this shortcut would not start it. If FreeLauncher is " +
                        "not set as your home app, Android blocks shortcuts from opening."
            }

            ConfirmDialog(
                title = "$name didn't open",
                message = explanation,
                confirmLabel = "Remove icon",
                destructive = true,
                onConfirm = {
                    deadShortcut = null
                    removeItem(item)
                },
                onDismiss = { deadShortcut = null },
            )
        }

        renameTarget?.let { item ->
            // What the app itself is called, to offer as the way back. A
            // shortcut has no entry to ask, and a folder's own name is nothing
            // but what somebody typed, so both fall back to something plain
            // rather than to an empty prompt.
            val original = remember(item.id) {
                app.apps.entryFor(item.component, item.userSerial)?.label
                    ?: app.apps.entryForPackage(item.packageName, item.userSerial)?.label
                    ?: item.title.ifBlank { "Folder" }
            }
            RenameDialog(
                current = item.title,
                original = original,
                onConfirm = { name ->
                    renameTarget = null
                    val fresh = app.layout.items.value.firstOrNull { it.id == item.id } ?: return@RenameDialog
                    app.layout.update(fresh.copy(title = name.ifBlank { original }))
                },
                onDismiss = { renameTarget = null },
            )
        }

        confirmRemoveFolder?.let { folder ->
            val holds = items.count { it.container == folder.id }
            val name = folder.title.ifBlank { "this folder" }
            ConfirmDialog(
                title = "Remove $name?",
                message = "The $holds ${if (holds == 1) "app" else "apps"} inside it come off " +
                    "the home screen too. They stay installed and are still in the app drawer.",
                confirmLabel = "Remove",
                destructive = true,
                onConfirm = {
                    confirmRemoveFolder = null
                    removeItem(folder)
                },
                onDismiss = { confirmRemoveFolder = null },
            )
        }

        confirmDeletePage?.let { page ->
            val onPage = items.count { it.container == Container.DESKTOP && it.screen == page }
            ConfirmDialog(
                title = "Delete page ${page + 1}?",
                message = if (onPage == 0) {
                    "The page is empty, so nothing on it will be lost."
                } else {
                    "Everything on it goes with it: $onPage " +
                        "${if (onPage == 1) "item" else "items"}, including anything inside " +
                        "folders on this page. This cannot be undone."
                },
                confirmLabel = "Delete page",
                destructive = true,
                onConfirm = {
                    confirmDeletePage = null

                    // Widgets on the page go back to the host before the
                    // records vanish, or their ids leak.
                    items.filter {
                        it.container == Container.DESKTOP && it.screen == page &&
                            it.type == ItemType.WIDGET && it.widgetId != -1
                    }.forEach { placement?.release(it.widgetId) }

                    app.layout.removeScreen(page)

                    // The default page, kept pointing at the page the user
                    // chose rather than reset.
                    //
                    // Pages are addressed by index and the ones after the
                    // deleted one all shift down by one, so a stored index is
                    // about a different page the moment a page before it goes.
                    // Resetting to zero was the old answer and was wrong twice:
                    // it moved the user's Home target for them, and it did
                    // nothing at all in the common case where the index stayed
                    // in range but now meant somewhere else.
                    val current = settings.defaultPage
                    val next = when {
                        current == page -> page.coerceAtMost(screenCount - 2).coerceAtLeast(0)
                        current > page -> current - 1
                        else -> current
                    }
                    if (next != current) {
                        app.settings.update { it.copy(defaultPage = next) }
                    }
                },
                onDismiss = { confirmDeletePage = null },
            )
        }

        missing?.let { item ->
            val name = item.title.ifBlank { "This app" }
            val pkg = item.packageName ?: item.componentName?.packageName
            ConfirmDialog(
                title = "$name isn't installed",
                message = "The icon is still here, but the app it points at is not on this " +
                    "phone any more. Get it back from the Play Store, or take the icon off.",
                confirmLabel = "Install",
                neutralLabel = "Remove icon",
                onNeutral = {
                    missing = null
                    removeItem(item)
                },
                onConfirm = {
                    missing = null
                    val opened = pkg != null && app.apps.openInStore(pkg)
                    if (!opened) {
                        Toast.makeText(context, "No app store on this phone", Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                onDismiss = { missing = null },
            )
        }

        if (showPageEditor) {
            PageEditor(
                screenCount = screenCount,
                items = items,
                settings = settings,
                currentPage = ui.currentPage,
                defaultPage = settings.defaultPage,
                onGoToPage = { page ->
                    scope.launch { runCatching { pagerState.animateScrollToPage(page) } }
                },
                onAddPage = {
                    val added = app.layout.addScreen()
                    scope.launch { runCatching { pagerState.animateScrollToPage(added) } }
                },
                onDeletePage = { page -> confirmDeletePage = page },
                onMovePage = { from, to -> app.layout.movePage(from, to) },
                onSetDefaultPage = { page -> app.settings.update { it.copy(defaultPage = page) } },
                onDismiss = { showPageEditor = false },
            )
        }

        if (showWidgetPicker) {
            WidgetPicker(
                cellW = widgetCellW,
                cellH = widgetCellH,
                onDismiss = { showWidgetPicker = false },
                onPick = ::beginWidget,
            )
        }
    }
}

// ---- drop resolution -----------------------------------------------------

/**
 * Where the finger currently is, expressed as something droppable.
 *
 * Pure: it reads positions and returns an intent, and changes nothing. Keeping
 * it that way is what lets the same call drive the live highlight and the
 * actual drop, so what the user sees highlighted is by construction what
 * happens when they let go.
 */
private fun resolveDrop(
    pos: Offset,
    ui: HomeUiState,
    items: List<LauncherItem>,
    settings: LauncherSettings,
): DropTarget? {
    val dragged = ui.drag?.item ?: return null

    // Remove lives above the grid, never on it.
    //
    // The target used to be a pill drawn just under the status bar, which put it
    // on top of the first row of cells, and it was checked before anything else.
    // Moving an icon along the top row with the finger in the upper part of the
    // icon - which is where people grip it - dropped it on Remove, and the icon
    // vanished from the home screen as though it had gone back to the drawer.
    // Now anything over a cell is a cell. Removing means carrying the icon up
    // past the grid, into the strip the target is drawn in.
    val grid = ui.pageBounds
    val aboveGrid = grid.height > 0f && pos.y < grid.top
    val onTarget = ui.removeTargetBounds.width > 0f && ui.removeTargetBounds.contains(pos)
    if (aboveGrid || (grid.height <= 0f && onTarget)) {
        return DropTarget.Remove
    }

    fun targetFor(container: Long, screen: Int, x: Int, y: Int): DropTarget? {
        // What the dragged item would cover if dropped here, not just the cell
        // under the finger. Dragging a four-cell widget by its left edge has to
        // check all four, or it lands half on top of something.
        val overlaps = items.filter { other ->
            other.id != dragged.id && other.container == container && other.screen == screen &&
                (0 until dragged.spanX).any { dx ->
                    (0 until dragged.spanY).any { dy -> other.occupies(x + dx, y + dy) }
                }
        }
        return when {
            overlaps.isEmpty() -> DropTarget.Cell(container, screen, x, y)

            // Covering more than one thing is never a folder and never a move.
            overlaps.size > 1 -> null

            // A widget cannot go into a folder and nothing can be dropped onto
            // one, so the drop is simply refused rather than quietly landing
            // on top of it.
            overlaps[0].type == ItemType.WIDGET || dragged.type == ItemType.WIDGET -> null

            overlaps[0].type == ItemType.FOLDER -> DropTarget.IntoFolder(overlaps[0].id)

            else -> DropTarget.MakeFolder(overlaps[0].id)
        }
    }

    val dock = ui.dockBounds
    if (settings.dockEnabled && dock.width > 0f && dock.contains(pos)) {
        // A folder in the dock is fine; a widget is not, because the dock is
        // one cell tall and every widget wants at least one and usually more.
        if (dragged.type == ItemType.WIDGET) return null
        val cellW = dock.width / settings.dockCols
        val x = ((pos.x - dock.left) / cellW).toInt().coerceIn(0, settings.dockCols - 1)
        return targetFor(Container.DOCK, 0, x, 0)
    }

    val page = ui.pageBounds
    if (page.width > 0f && page.contains(pos)) {
        val cellW = page.width / settings.desktopCols
        val cellH = page.height / settings.desktopRows
        // Clamped so the item's whole footprint stays on the grid, not just its
        // top-left cell. Otherwise a wide widget dragged to the right edge is
        // "placed" with most of itself past the end of the page.
        val maxX = (settings.desktopCols - dragged.spanX).coerceAtLeast(0)
        val maxY = (settings.desktopRows - dragged.spanY).coerceAtLeast(0)
        val x = ((pos.x - page.left) / cellW).toInt().coerceIn(0, maxX)
        val y = ((pos.y - page.top) / cellH).toInt().coerceIn(0, maxY)
        return targetFor(Container.DESKTOP, ui.currentPage, x, y)
    }

    return null
}

/**
 * Swipe down on the home screen.
 *
 * Pulling the notification shade has never had a public API. The reflective
 * call below is what every third-party launcher uses and it is explicitly not
 * guaranteed: it reaches a non-SDK interface, and on the versions where the
 * platform blocks those it throws rather than working. That failure is caught
 * and the gesture simply does nothing, which is the honest outcome -- quietly
 * substituting a different action would leave the user unable to tell whether
 * their setting had taken effect at all.
 */
private fun performSwipeDown(activity: Activity?, action: SwipeDownAction, openSearch: () -> Unit) {
    when (action) {
        SwipeDownAction.NONE -> Unit
        SwipeDownAction.SEARCH -> openSearch()
        SwipeDownAction.NOTIFICATIONS -> {
            val service = activity?.getSystemService("statusbar") ?: return
            runCatching {
                Class.forName("android.app.StatusBarManager")
                    .getMethod("expandNotificationsPanel")
                    .invoke(service)
            }
        }
    }
}

private fun Rect.toAndroidRect(): android.graphics.Rect? {
    if (width <= 0f || height <= 0f) return null
    return android.graphics.Rect(
        left.roundToInt(),
        top.roundToInt(),
        right.roundToInt(),
        bottom.roundToInt(),
    )
}
