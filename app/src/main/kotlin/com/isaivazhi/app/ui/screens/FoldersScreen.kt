package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.FolderEntry

/**
 * Full-screen overlay for managing which source directories are visible in
 * the app. Each folder row shows name + song count with a Switch toggle;
 * both directions require a confirmation dialog before applying.
 *
 * "Add folder" at the bottom triggers the SAF folder picker via [onAddFolder].
 */
@Composable
fun FoldersScreen(
    folders: List<FolderEntry>,
    onBack: () -> Unit,
    onExcludeFolder: (path: String) -> Unit,
    onIncludeFolder: (path: String) -> Unit,
    onAddFolder: () -> Unit,
) {
    // Dialog state: we track the folder whose toggle was just tapped
    var pendingExclude by remember { mutableStateOf<FolderEntry?>(null) }
    var pendingInclude by remember { mutableStateOf<FolderEntry?>(null) }

    // Confirmation dialog — excluding a folder
    pendingExclude?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingExclude = null },
            title = { Text("Exclude \"${folder.displayName}\"?") },
            text = {
                Text(
                    "${folder.songCount} song${if (folder.songCount == 1) "" else "s"} will be " +
                        "removed from your library and all playback queues. " +
                        "Their AI embeddings are kept — you can re-add them anytime.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onExcludeFolder(folder.path)
                        pendingExclude = null
                    },
                ) {
                    Text("Exclude", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingExclude = null }) { Text("Cancel") }
            },
        )
    }

    // Confirmation dialog — including a folder
    pendingInclude?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingInclude = null },
            title = { Text("Add \"${folder.displayName}\" back?") },
            text = {
                Text(
                    "${folder.songCount} song${if (folder.songCount == 1) "" else "s"} will " +
                        "reappear across your library, albums, and recommendations. " +
                        "Their listening history will be reset so they start fresh in recommendations.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onIncludeFolder(folder.path)
                        pendingInclude = null
                    },
                ) {
                    Text("Include")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingInclude = null }) { Text("Cancel") }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Folders",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (folders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No folders found yet.\nScan your library or add a folder below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(folders, key = { it.path }) { folder ->
                        FolderRow(
                            folder = folder,
                            onToggle = {
                                if (folder.isExcluded) pendingInclude = folder
                                else pendingExclude = folder
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            modifier = Modifier.padding(start = 56.dp),
                        )
                    }
                }
            }

            // "Add folder" button pinned at the bottom
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            OutlinedButton(
                onClick = onAddFolder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CreateNewFolder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Add folder")
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: FolderEntry,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (folder.isExcluded) Icons.Filled.FolderOff else Icons.Filled.Folder,
            contentDescription = null,
            tint = if (folder.isExcluded)
                MaterialTheme.colorScheme.onSurfaceVariant
            else
                MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = folder.displayName,
                    style = MaterialTheme.typography.titleSmall.let {
                        if (folder.isExcluded) it.copy(textDecoration = TextDecoration.LineThrough) else it
                    },
                    color = if (folder.isExcluded)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (folder.isManual) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "manual",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append("${folder.songCount} song${if (folder.songCount == 1) "" else "s"}")
                    if (folder.isExcluded) append(" · excluded")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(
            checked = !folder.isExcluded,
            onCheckedChange = { onToggle() },
        )
    }
}
