package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.Song

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewAllScreen(
    title: String,
    songs: List<Song>,
    currentMediaId: String?,
    onBack: () -> Unit,
    onPlay: (queue: List<Song>, startIndex: Int) -> Unit,
    onLongPress: (Song) -> Unit,
    subtitleForSong: ((Song) -> String)? = null,
    onTrailingAction: ((Song) -> Unit)? = null,
    trailingIcon: ImageVector = Icons.Filled.Close,
    trailingContentDescription: String = "Release early",
    emptyMessage: String = "Nothing here yet.",
    onClearAll: (() -> Unit)? = null,
    clearAllConfirmMessage: String? = null,
) {
    var showClearAllConfirm by remember { mutableStateOf(false) }

    if (showClearAllConfirm && onClearAll != null) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Clear all?") },
            text = {
                Text(
                    clearAllConfirmMessage
                        ?: "Release all songs from recommendation cooldown? They will be eligible for AI recommendations again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearAllConfirm = false
                }) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("Cancel") }
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onClearAll != null && songs.isNotEmpty()) {
                    TextButton(onClick = { showClearAllConfirm = true }) {
                        Text("Clear all")
                    }
                }
                Text(
                    text = "${songs.size} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = if (onClearAll != null && songs.isNotEmpty()) 4.dp else 12.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (songs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                itemsIndexed(songs, key = { i, s -> "view_${i}_${s.id}" }) { i, song ->
                    val isCurrent = song.filename == currentMediaId
                    val rowBg = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.background
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onPlay(songs, i) },
                                onLongClick = { onLongPress(song) },
                            )
                            .background(rowBg)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtThumbnail(filePath = song.filePath, size = 44.dp, cornerRadius = 4.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(
                                text = song.title.ifBlank { song.filename },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            val subtitle = subtitleForSong?.invoke(song)
                                ?: listOf(song.artist, song.album).filter { it.isNotBlank() }
                                    .joinToString(" • ")
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (onTrailingAction != null) {
                            IconButton(
                                onClick = { onTrailingAction(song) },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = trailingIcon,
                                    contentDescription = trailingContentDescription,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
