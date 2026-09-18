package com.freelauncher.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freelauncher.app.data.AppEntry
import com.freelauncher.app.data.IconShape
import com.freelauncher.app.ui.common.AppIcon

/**
 * Choosing a folder's contents from the whole app list.
 *
 * The other way into a folder is dragging one icon onto another, which is fine
 * for two apps and painful for ten. This is the same job done as a list: tick
 * what belongs, untick what does not.
 *
 * Unticking removes the item from the folder outright rather than turning it
 * out onto the desktop, which is what a long press does. The distinction is
 * deliberate: a long press is aimed at one app and means "put this somewhere
 * else", while a list of ticks is aimed at the folder and means "this is what
 * is in it". Nothing is lost either way -- every app is still in the drawer.
 *
 * This one does dim what is behind it, unlike the folder panel. A folder is a
 * part of the home screen opened up; a full-height list of every installed app
 * is a place you have gone to, and it needs the separation.
 */
@Composable
fun FolderAppPicker(
    folderTitle: String,
    apps: List<AppEntry>,
    initiallyIn: Set<String>,
    shape: IconShape,
    onCancel: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    // Seeded once from what the folder holds, then owned by the sheet. The
    // layout is not touched until Done, so backing out leaves the folder as it
    // was rather than as it was half way through being edited.
    val checked = remember(initiallyIn) {
        mutableStateMapOf<String, Boolean>().apply {
            for (key in initiallyIn) put(key, true)
        }
    }

    // Members first, then everything else, each alphabetically. A list of two
    // hundred apps with six of them ticked somewhere inside it makes the user
    // scroll to find out what is already in the folder.
    val ordered = remember(apps, initiallyIn) {
        apps.sortedWith(
            compareByDescending<AppEntry> { it.key in initiallyIn }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
    }

    val count = checked.count { it.value }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onCancel,
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Text(
                    text = folderTitle.ifEmpty { "Folder" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    text = if (count == 1) "1 app selected" else "$count apps selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(ordered, key = { it.key }) { entry ->
                        val isIn = checked[entry.key] == true
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { checked[entry.key] = !isIn }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(entry = entry, shape = shape, size = 40.dp)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Checkbox(
                                checked = isIn,
                                onCheckedChange = { checked[entry.key] = it },
                            )
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            onConfirm(checked.filterValues { it }.keys.toSet())
                        }
                    ) { Text("Done") }
                }
            }
        }
    }
}

