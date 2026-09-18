package com.freelauncher.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.freelauncher.app.data.Container
import com.freelauncher.app.data.LauncherItem
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.ui.theme.LocalLauncherPalette

/** Most of the screen height the dock may take, labels included. */
private const val DOCK_MAX_SHARE = 0.17f

/**
 * The fixed row of icons above the gesture bar.
 *
 * Laid out on the same absolute-cell principle as a workspace page rather than
 * as a Row, so that an item keeps the slot it was put in. In a Row, removing
 * the second of five icons slides the last three left; on a dock that is wrong,
 * because the user arranged those positions on purpose and expects a gap.
 */
@Composable
fun Dock(
    items: List<LauncherItem>,
    settings: LauncherSettings,
    ui: HomeUiState,
    modifier: Modifier = Modifier,
    onLaunch: (LauncherItem, Rect) -> Unit,
    onOpenFolder: (LauncherItem, Rect) -> Unit,
    onLongPress: (LauncherItem, Rect) -> Unit,
    onDragBegin: (DragSession, Offset) -> Unit,
) {
    val palette = LocalLauncherPalette.current
    val inDock = remember(items) { items.filter { it.container == Container.DOCK } }
    val cols = settings.dockCols

    // See the note in Workspace: cells are sized for the portrait width even
    // when the screen is turned, and the surplus becomes margin.
    val config = LocalConfiguration.current
    val portraitWidth = minOf(config.screenWidthDp, config.screenHeightDp).dp

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
    ) {
        // Capped against the portrait width, which is the whole fix for a dock
        // that swallowed a landscape screen.
        //
        // The cell is square and its height follows its width, so on a screen
        // turned sideways "one fifth of the width" was around 180dp -- and the
        // dock became a 180dp band across a screen only 350dp tall. The shape
        // was right and the input was wrong.
        // Capped a second time, against the height rather than the width.
        //
        // A square cell one fifth of the portrait width is about 78dp, which is
        // a tenth of a portrait screen and a quarter of a landscape one. The
        // dock is one row of five and has no business taking a quarter of the
        // display, so it also gets a ceiling as a share of whatever height it
        // is on. In portrait the ceiling is nowhere near and nothing changes.
        val labelRoom = if (settings.dockShowLabels) 16.dp else 0.dp
        val heightCap = (config.screenHeightDp.dp * DOCK_MAX_SHARE - labelRoom)
            .coerceAtLeast(44.dp)
        val cellW: Dp = minOf(maxWidth / cols, (portraitWidth - 20.dp) / cols, heightCap)
        val cellH: Dp = cellW + labelRoom
        // Slightly larger in proportion than a workspace icon: the dock is one
        // row, so there is no vertical neighbour to crowd, and these are the
        // five icons the user reaches for most.
        val iconSize = (cellW * 0.66f * settings.iconScale).coerceIn(28.dp, 84.dp)

        Box(
            Modifier
                // As wide as its cells and centred, not the whole screen. The
                // bounds recorded here are what the drop maths divides by the
                // column count, so the box has to be the row of cells itself
                // or a drop in landscape lands in the wrong slot.
                .width(cellW * cols)
                .align(Alignment.Center)
                .height(cellH)
                .onGloballyPositioned { ui.dockBounds = it.boundsInWindow() }
                .then(
                    if (settings.dockBackground) {
                        Modifier
                            .clip(RoundedCornerShape(26.dp))
                            .background(palette.glass)
                            .border(1.dp, palette.glassBorder, RoundedCornerShape(26.dp))
                    } else {
                        Modifier
                    }
                )
        ) {
            DockDropHighlight(ui, cellW, cellH)

            for (item in inDock) {
                key(item.id) {
                    WorkspaceCell(
                        item = item,
                        items = items,
                        settings = settings,
                        ui = ui,
                        iconSize = iconSize,
                        showLabel = settings.dockShowLabels,
                        modifier = Modifier
                            .offset(x = cellW * item.cellX)
                            .size(cellW, cellH),
                        onLaunch = onLaunch,
                        onOpenFolder = onOpenFolder,
                        onLongPress = onLongPress,
                        onDragBegin = onDragBegin,
                    )
                }
            }
        }
    }
}

@Composable
private fun DockDropHighlight(ui: HomeUiState, cellW: Dp, cellH: Dp) {
    val hint = ui.dropHint
    val palette = LocalLauncherPalette.current
    if (hint !is DropTarget.Cell || hint.container != Container.DOCK) return

    Box(
        Modifier
            .offset(x = cellW * hint.x)
            .size(cellW, cellH)
            .padding(4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.glass.copy(alpha = 0.3f))
    )
}
