package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownAlt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.isaivazhi.app.engine.HistoryEngine
import com.isaivazhi.app.engine.PlaylistsEngine
import com.isaivazhi.app.engine.Song
import com.isaivazhi.app.engine.TasteEngine

/**
 * Long-press song menu. Full 10-action menu matching the Capacitor build.
 * Actions in order:
 *   1. Play Next
 *   2. Add to playlist…
 *   3. Remove from {currentPlaylist}    — only when invoked from a playlist view
 *   4. Toggle Favorite
 *   5. Toggle Dislike
 *   6. View Details                     — opens metadata + per-song stats modal
 *   7. View Album                       — auto-expand the album in the Albums tab
 *   8. Embed/Re-embed this song
 *   9. Delete from device               — with native confirm dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(
    song: Song,
    isFavorite: Boolean,
    isDisliked: Boolean,
    hasEmbedding: Boolean,
    currentSplitCount: Int,
    currentPlaylistName: String?,
    songStats: HistoryEngine.Stats?,
    tasteSignal: TasteEngine.TasteSignal?,
    playlists: List<PlaylistsEngine.Playlist>,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDislike: () -> Unit,
    onAddToPlaylist: (playlistId: String) -> Unit,
    onCreatePlaylistAndAdd: (playlistName: String) -> Unit,
    onRemoveFromCurrentPlaylist: () -> Unit,
    onViewDetails: () -> Unit,
    onViewAlbum: () -> Unit,
    onResetTasteSignal: () -> Unit,
    onEmbedSong: () -> Unit,
    onDeleteFromDevice: () -> Unit,
    // Push #42 Tier 2I: optional "Play in order" entry — shown when the
    // menu is invoked from the Songs tab (library list). Null means the
    // entry is hidden (e.g. when invoked from a Discover card or album,
    // where "play in order" is already implicit in the section tap).
    onPlayInOrder: (() -> Unit)? = null,
) {
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showEmbedConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.error,
        unfocusedBorderColor = MaterialTheme.colorScheme.primary,
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtThumbnail(filePath = song.filePath, size = 56.dp, cornerRadius = 6.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title.ifBlank { song.filename },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = listOf(song.artist, song.album).filter { it.isNotBlank() }
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            MenuItem(icon = Icons.Filled.SkipNext, label = "Play Next",
                onClick = { onDismiss(); onPlayNext() })
            // Push #42 Tier 2I: "Play in order" — Songs tab tap defaults to
            // recommendation mode (50-song AI tail). This entry lets the
            // user opt into linear playback from the tapped position.
            // Only shown when the caller passes a non-null onPlayInOrder.
            if (onPlayInOrder != null) {
                MenuItem(
                    icon = Icons.Filled.PlaylistPlay,
                    label = "Play in order",
                    onClick = { onPlayInOrder(); onDismiss() },
                )
            }
            MenuItem(icon = Icons.Filled.PlaylistAdd, label = "Add to playlist…",
                onClick = { showPlaylistPicker = true })
            if (!currentPlaylistName.isNullOrBlank()) {
                MenuItem(icon = Icons.Filled.PlaylistRemove, label = "Remove from $currentPlaylistName",
                    onClick = { onRemoveFromCurrentPlaylist(); onDismiss() })
            }
            MenuItem(
                icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label = if (isFavorite) "Remove from favorites" else "Add to favorites",
                onClick = { onToggleFavorite(); onDismiss() },
            )
            MenuItem(
                icon = if (isDisliked) Icons.Filled.ThumbDownAlt else Icons.Filled.ThumbDown,
                label = if (isDisliked) "Remove dislike" else "Dislike",
                onClick = { onToggleDislike(); onDismiss() },
            )
            MenuItem(icon = Icons.Filled.Info, label = "View details",
                onClick = { showDetails = true })
            MenuItem(icon = Icons.Filled.Album, label = "View album",
                onClick = { onViewAlbum(); onDismiss() })
            // Push #40 Tier 1C: explicit per-song embed action. Always
            // kicks off `embedSongs(listOf(song))` regardless of current
            // embedding state. Mirrors Capacitor's
            // `engine.readdSongEmbedding(songId)` from
            // `backups/.../src/app-song-menu.js:142`.
            MenuItem(
                icon = Icons.Filled.AutoAwesome,
                label = if (hasEmbedding) "Re-embed this song" else "Embed this song",
                onClick = { showEmbedConfirm = true },
            )
            MenuItem(
                icon = Icons.Filled.DeleteOutline,
                label = "Delete from device",
                tintError = true,
                onClick = { showDeleteConfirm = true },
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showPlaylistPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPlaylistPicker = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    text = "Add to which playlist?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                MenuItem(
                    icon = Icons.Filled.Add,
                    label = "New playlist…",
                    onClick = {
                        showPlaylistPicker = false
                        showCreatePlaylistDialog = true
                    },
                )
                if (playlists.isEmpty()) {
                    Text(
                        text = "No existing playlists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    LazyColumn {
                        items(playlists, key = { it.id }) { pl ->
                            MenuItem(
                                icon = Icons.Filled.PlaylistPlay,
                                label = pl.name,
                                subtitle = "${pl.songFilenames.size} songs",
                                onClick = {
                                    onAddToPlaylist(pl.id)
                                    showPlaylistPicker = false
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        var playlistName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Name") },
                    placeholder = { Text("New playlist") },
                    singleLine = true,
                    colors = inputColors,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = playlistName.trim().ifBlank { "New playlist" }
                    onCreatePlaylistAndAdd(name)
                    showCreatePlaylistDialog = false
                    onDismiss()
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showEmbedConfirm) {
        val actionLabel = if (hasEmbedding) "Re-embed" else "Embed"
        AlertDialog(
            onDismissRequest = { showEmbedConfirm = false },
            title = { Text("$actionLabel song?") },
            text = {
                Text(
                    "$actionLabel this song using split count $currentSplitCount? " +
                        "To use a different split count, change it in AI settings first."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEmbedConfirm = false
                    onEmbedSong()
                    onDismiss()
                }) { Text("Proceed") }
            },
            dismissButton = {
                TextButton(onClick = { showEmbedConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete song?") },
            text = {
                Text("Delete \"${song.title.ifBlank { song.filename }}\" by ${song.artist.ifBlank { "Unknown" }}? " +
                    "This permanently removes the file from your device. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDeleteFromDevice(); onDismiss() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showDetails) {
        SongDetailsDialog(
            song = song,
            isFavorite = isFavorite,
            isDisliked = isDisliked,
            hasEmbedding = hasEmbedding,
            stats = songStats,
            tasteSignal = tasteSignal,
            onResetTasteSignal = onResetTasteSignal,
            onClose = { showDetails = false },
        )
    }
}

@Composable
private fun SongDetailsDialog(
    song: Song,
    isFavorite: Boolean,
    isDisliked: Boolean,
    hasEmbedding: Boolean,
    stats: HistoryEngine.Stats?,
    tasteSignal: TasteEngine.TasteSignal?,
    onResetTasteSignal: () -> Unit,
    onClose: () -> Unit,
) {
    val avgStr = stats?.let { "${(it.avgFraction * 100).toInt()}%" } ?: "—"
    val playCountStr = stats?.plays?.toString() ?: "0"
    val lastPlayedStr = stats?.lastPlayedAt?.takeIf { it > 0L }?.let {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it))
    } ?: "—"
    val format = song.filePath?.substringAfterLast('.', "")?.uppercase() ?: "—"
    // Push #62: read on-disk metadata once at sheet-open so the user
    // can see the actual file size + modification date. Helps tell
    // otherwise-identical duplicates apart.
    val fileMeta = remember(song.filePath) {
        com.isaivazhi.app.engine.FileMeta.read(song.filePath)
    }
    val sizeStr = if (fileMeta.exists)
        com.isaivazhi.app.engine.FileMeta.formatSize(fileMeta.sizeBytes)
    else "— (file missing)"
    val mtimeStr = if (fileMeta.exists && fileMeta.lastModified > 0L)
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(fileMeta.lastModified))
    else "—"
    var showResetTasteConfirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Song details") },
        text = {
            Column {
                DetailRow("Title", song.title.ifBlank { "—" })
                DetailRow("Artist", song.artist.ifBlank { "—" })
                DetailRow("Album", song.album.ifBlank { "—" })
                DetailRow("Format", format.ifBlank { "—" })
                DetailRow("File size", sizeStr)
                DetailRow("File modified", mtimeStr)
                DetailRow("Filename", song.filename)
                DetailRow("Filepath", song.filePath ?: "—")
                DetailRow("Content hash", song.contentHash ?: "—")
                DetailRow("Embedding", if (hasEmbedding) "Yes" else "No")
                DetailRow("Start count", playCountStr)
                DetailRow("Avg listen", avgStr)
                DetailRow("Last played", lastPlayedStr)
                DetailRow("Favorite", if (isFavorite) "Yes" else "No")
                DetailRow("Disliked", if (isDisliked) "Yes" else "No")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Taste signal",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                DetailRow("Plays", (tasteSignal?.plays ?: 0).toString())
                DetailRow("Skips", (tasteSignal?.skips ?: 0).toString())
                DetailRow("Avg fraction", tasteSignal?.avgFraction?.let { "%.2f".format(it) } ?: "0.00")
                DetailRow("X score", tasteSignal?.xScore?.let { "%.2f".format(it) } ?: "0.00")
                DetailRow("Direct score", tasteSignal?.directScore?.let { "%.2f".format(it) } ?: "0.00")
                DetailRow("Similarity boost", tasteSignal?.similarityBoost?.let { "%.2f".format(it) } ?: "0.00")
                DetailRow("Favorite prior", tasteSignal?.favoritePrior?.let { "%.2f".format(it) } ?: "0.00")
                DetailRow("Dislike prior", tasteSignal?.dislikePrior?.let { "%.2f".format(it) } ?: "0.00")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = { showResetTasteConfirm = true }) {
                Text("Reset taste signal", color = MaterialTheme.colorScheme.error)
            }
        },
    )
    if (showResetTasteConfirm) {
        AlertDialog(
            onDismissRequest = { showResetTasteConfirm = false },
            title = { Text("Reset taste signal?") },
            text = { Text("Reset taste signal for this song? This clears learned taste score history for this track.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetTasteConfirm = false
                    onResetTasteSignal()
                    onClose()
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetTasteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    tintError: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (tintError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
