package com.freelauncher.app.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.freelauncher.app.data.AppEntry
import com.freelauncher.app.data.LauncherItem

/** What the long-press menu was opened on. */
sealed interface MenuTarget {
    /** An icon already placed on the home screen, dock or in a folder. */
    class Placed(val item: LauncherItem, val anchor: Rect) : MenuTarget

    /** An app in the drawer, which is not placed anywhere yet. */
    class Drawer(val entry: AppEntry, val anchor: Rect) : MenuTarget

    /**
     * An app inside private space.
     *
     * Separate from [Drawer] because two of that menu's rows are wrong here.
     * "Hide from drawer" does nothing to an app that is not in the drawer, and
     * Uninstall would aim at the personal profile's copy of the package rather
     * than the private one -- App info opens in the right profile and carries
     * the uninstall the user actually wants.
     */
    class Private(val entry: AppEntry, val anchor: Rect) : MenuTarget

    /** Empty space on the home screen: wallpaper, widgets, settings. */
    class Blank(val screen: Int) : MenuTarget
}

/**
 * An icon being carried by the finger.
 *
 * Only the item, which is all anything ever asked for. It used to carry the
 * cell the drag started from as well, described as being there so a cancelled
 * drag could put the item back -- but nothing read those fields, and nothing
 * needed to: a drag does not move anything until it is dropped, so a cancelled
 * one has nothing to undo. It also carried the grab point inside the icon, for
 * a pick-up that did not jump, and the preview is drawn centred on the finger
 * regardless.
 *
 * A class rather than a bare LauncherItem so that the drag has a name of its
 * own at the call sites, and somewhere obvious to put the next thing a drag
 * genuinely has to remember.
 */
class DragSession(
    val item: LauncherItem,

    /**
     * True for something dragged out of the app drawer, which has no record in
     * the layout yet.
     *
     * The drop has to add it rather than move it. Without the distinction the
     * drop applied a move to an id that did not exist, which changes nothing
     * and reports nothing -- the icon simply did not arrive.
     */
    val isNew: Boolean = false,
)

/**
 * Everything the home screen knows that is not persisted.
 *
 * One object rather than a dozen separate remembered values, because most of it
 * is interdependent -- a drag has to close an open folder, opening the drawer
 * has to cancel a drag -- and coordinating that across scattered state holders
 * is how a launcher ends up with a drag preview stuck on screen.
 *
 * Deliberately not a ViewModel. None of this should survive the activity: if
 * the user presses Home while dragging an icon, the right outcome is a clean
 * home screen, not the drag resuming.
 */
@Stable
class HomeUiState {

    /** Which page the pager is on, tracked for the page indicator and drops. */
    var currentPage by mutableIntStateOf(0)

    /** Open folder, by item id. */
    var openFolder by mutableStateOf<Long?>(null)

    /**
     * Where the open folder's icon sits, in window coordinates.
     *
     * Kept beside [openFolder] rather than derived from the item, because by
     * the time the overlay is composed the cell it came from may be off-screen
     * on another page and no longer reporting a position at all. Captured at
     * the moment of the tap, when it is certainly correct.
     */
    var folderAnchor by mutableStateOf(Rect.Zero)

    /**
     * Folder whose contents are being picked from the full app list, by id.
     *
     * Separate from [openFolder] because the picker replaces the open folder
     * rather than sitting on top of it. Two panels covering each other, one of
     * them a scrolling list of every installed app, is a stack of modals on a
     * home screen ; the folder reopens when the picker is done.
     */
    var selectAppsFor by mutableStateOf<Long?>(null)

    var menu by mutableStateOf<MenuTarget?>(null)

    /**
     * The widget currently showing resize handles, by item id.
     *
     * A mode rather than a permanent affordance: handles drawn on every widget
     * all the time would sit on top of the widget's own content, and widgets
     * are the one thing on a home screen that people tap in several places.
     */
    var resizing by mutableStateOf<Long?>(null)

    var drag by mutableStateOf<DragSession?>(null)
        private set

    /** Finger position in window coordinates, while [drag] is non-null. */
    var dragPosition by mutableStateOf(Offset.Zero)

    /** The cell the drag is currently over, for the drop highlight. */
    var dropHint by mutableStateOf<DropTarget?>(null)

    // Measured once per layout pass and read during drags. Window coordinates,
    // because a drag crosses between the pager and the dock and the two have no
    // common local coordinate space.
    var pageBounds by mutableStateOf(Rect.Zero)
    var dockBounds by mutableStateOf(Rect.Zero)
    var removeTargetBounds by mutableStateOf(Rect.Zero)

    val isDragging: Boolean get() = drag != null

    fun beginDrag(session: DragSession, at: Offset) {
        openFolder = null
        menu = null
        drag = session
        dragPosition = at
    }

    fun endDrag() {
        drag = null
        dropHint = null
    }

    fun dismissOverlays() {
        menu = null
        openFolder = null
        resizing = null
    }
}

/** Where a drag would land if it were released now. */
sealed interface DropTarget {
    class Cell(val container: Long, val screen: Int, val x: Int, val y: Int) : DropTarget
    class IntoFolder(val folderId: Long) : DropTarget
    class MakeFolder(val withItemId: Long) : DropTarget
    data object Remove : DropTarget
}
