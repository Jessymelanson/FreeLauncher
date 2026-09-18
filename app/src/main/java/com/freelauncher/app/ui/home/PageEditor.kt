package com.freelauncher.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.freelauncher.app.data.Container
import com.freelauncher.app.data.ItemType
import com.freelauncher.app.data.LauncherItem
import com.freelauncher.app.data.LauncherSettings
import com.freelauncher.app.ui.common.ItemIcon
import kotlin.math.roundToInt

/**
 * The page overview.
 *
 * Reached by long-pressing empty space or pinching the home screen, and it is
 * the only place where pages can be reordered or a different one made the
 * default. Doing that from the home screen itself is possible but awful: pages
 * are full-screen, so moving page five before page two means dragging across
 * four screens of scrolling with an icon already in hand.
 */
@Composable
fun PageEditor(
    screenCount: Int,
    items: List<LauncherItem>,
    settings: LauncherSettings,
    currentPage: Int,
    defaultPage: Int,
    onGoToPage: (Int) -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: (Int) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onSetDefaultPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // The card being dragged, and how far it has travelled. Held here rather
    // than in each card because a reorder is a fact about the row, not about
    // one card, and two cards both believing they are being dragged is the
    // usual way a reorder ends up applying twice.
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val cardWidth = 132.dp
    val cardGap = 14.dp
    val stride = with(density) { (cardWidth + cardGap).toPx() }

    LaunchedEffect(currentPage) {
        runCatching { listState.scrollToItem(currentPage.coerceAtMost(screenCount)) }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Nearly opaque. At 0.82 the home screen behind stayed legible
            // through it, so the page thumbnails were competing with a
            // full-size copy of the page they were thumbnails of.
            .background(Color.Black.copy(alpha = 0.94f))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Pages",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap to open, hold to reorder",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(20.dp))

            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(cardGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(screenCount) { page ->
                    // While a card is being dragged, the ones it has passed
                    // slide over to show where it would land.
                    val shift = when {
                        dragIndex < 0 -> 0f
                        page == dragIndex -> 0f
                        else -> {
                            val target = dragIndex + (dragOffset / stride).roundToInt()
                            when {
                                page in (dragIndex + 1)..target -> -stride
                                page in target until dragIndex -> stride
                                else -> 0f
                            }
                        }
                    }
                    val animatedShift by animateFloatAsState(shift, label = "shift")

                    PageCard(
                        page = page,
                        items = items,
                        settings = settings,
                        isCurrent = page == currentPage,
                        isDefault = page == defaultPage,
                        canDelete = screenCount > 1,
                        width = cardWidth,
                        dragging = page == dragIndex,
                        modifier = Modifier.offset {
                            val x = if (page == dragIndex) dragOffset else animatedShift
                            IntOffset(x.roundToInt(), 0)
                        },
                        onClick = {
                            onGoToPage(page)
                            onDismiss()
                        },
                        onDelete = { onDeletePage(page) },
                        onSetDefault = { onSetDefaultPage(page) },
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragIndex = page
                            dragOffset = 0f
                        },
                        onDrag = { dragOffset += it },
                        onDragEnd = {
                            val moved = (dragOffset / stride).roundToInt()
                            val from = dragIndex
                            val to = (from + moved).coerceIn(0, screenCount - 1)
                            dragIndex = -1
                            dragOffset = 0f
                            if (from != to && from >= 0) onMovePage(from, to)
                        },
                    )
                }

                item {
                    AddPageCard(width = cardWidth, onClick = onAddPage)
                }
            }
        }
    }
}

@Composable
private fun PageCard(
    page: Int,
    items: List<LauncherItem>,
    settings: LauncherSettings,
    isCurrent: Boolean,
    isDefault: Boolean,
    canDelete: Boolean,
    width: androidx.compose.ui.unit.Dp,
    dragging: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val height = width * 1.7f
    val border = when {
        isCurrent -> MaterialTheme.colorScheme.primary
        else -> Color.White.copy(alpha = 0.22f)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier
                .size(width, height)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = if (dragging) 0.20f else 0.10f))
                .border(if (isCurrent) 2.dp else 1.dp, border, RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .pointerInput(page) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount.x)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    )
                }
        ) {
            PagePreview(page, items, settings, Modifier.fillMaxSize().padding(8.dp))

            // Delete sits on the card, top-right, because it acts on this card
            // and nothing else. A shared delete button with a selection model
            // would be one more mode to be in by mistake.
            if (canDelete) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Delete page ${page + 1}",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDefault) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.14f)
                    )
                    .clickable(onClick = onSetDefault),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Home,
                    contentDescription = if (isDefault) "Default page" else "Make default page",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                "${page + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * A page shrunk to thumbnail size.
 *
 * Real icons at a tiny size rather than grey boxes: at 130dp wide the icons are
 * about 12dp each, which is small but still recognisable by colour and shape,
 * and that is exactly how someone picks out "the page with the bank app on it".
 */
@Composable
private fun PagePreview(
    page: Int,
    items: List<LauncherItem>,
    settings: LauncherSettings,
    modifier: Modifier = Modifier,
) {
    val onPage = remember(items, page) {
        items.filter { it.container == Container.DESKTOP && it.screen == page }
    }
    BoxWithConstraints(modifier) {
        val cellW = maxWidth / settings.desktopCols
        val cellH = maxHeight / settings.desktopRows
        val size = minOf(cellW, cellH) * 0.8f

        for (item in onPage) {
            val children = if (item.type == ItemType.FOLDER) {
                items.filter { it.container == item.id }
            } else {
                emptyList()
            }
            Box(
                Modifier
                    .offset(x = cellW * item.cellX, y = cellH * item.cellY)
                    .size(cellW * item.spanX, cellH * item.spanY),
                contentAlignment = Alignment.Center,
            ) {
                if (item.type == ItemType.WIDGET) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(1.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                    )
                } else {
                    ItemIcon(item, settings.iconShape, size, folderChildren = children)
                }
            }
        }
    }
}

@Composable
private fun AddPageCard(width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val height = width * 1.7f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width, height)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Add page",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "New",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}
