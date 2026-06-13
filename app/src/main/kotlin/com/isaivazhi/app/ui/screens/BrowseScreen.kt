package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.HistoryEngine
import com.isaivazhi.app.engine.PlaylistsEngine
import com.isaivazhi.app.engine.RecentlySurfacedTracker
import com.isaivazhi.app.engine.Song

enum class BrowseCategory(val title: String) {
    MostPlayed("Most Played"),
    RecentlyPlayed("Recently Played"),
    ResumesIn("Resumes In"),
    NeverPlayed("Never Played"),
    LastAdded("Last Added"),
    Favorites("Favorites"),
    Disliked("Disliked Songs"),
}

data class BrowseTile(val category: BrowseCategory, val count: Int, val previewArt: List<String?>)

@Composable
fun BrowseScreen(
    tiles: List<BrowseTile>,
    playlists: List<PlaylistsEngine.Playlist>,
    onOpenCategory: (BrowseCategory) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (id: String, name: String) -> Unit,
    onOpenPlaylist: (id: String) -> Unit,
    onDeletePlaylist: (id: String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renamePlaylistId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var pendingDeletePlaylistId by remember { mutableStateOf<String?>(null) }
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.error,
        unfocusedBorderColor = MaterialTheme.colorScheme.primary,
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tiles, key = { it.category.name }) { tile ->
            TileCard(tile = tile, onClick = { onOpenCategory(tile.category) })
        }

        // Playlists section — spans both columns.
        item(span = { GridItemSpan(2) }, key = "playlists_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Playlists",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("New")
                }
            }
        }
        if (playlists.isEmpty()) {
            item(span = { GridItemSpan(2) }, key = "playlists_empty") {
                Text(
                    text = "No playlists yet. Tap “New” to create one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        } else {
            items(playlists, key = { "pl_${it.id}" }, span = { GridItemSpan(2) }) { pl ->
                PlaylistRow(
                    playlist = pl,
                    onOpen = { onOpenPlaylist(pl.id) },
                    onRename = {
                        renamePlaylistId = pl.id
                        renameDraft = pl.name
                    },
                    onDelete = { pendingDeletePlaylistId = pl.id },
                )
            }
        }
    }

    if (showCreateDialog) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = inputColors,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = draft.trim().ifBlank { "New playlist" }
                    onCreatePlaylist(name)
                    showCreateDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (renamePlaylistId != null) {
        AlertDialog(
            onDismissRequest = { renamePlaylistId = null },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = inputColors,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = renamePlaylistId ?: return@TextButton
                    val name = renameDraft.trim().ifBlank { "New playlist" }
                    onRenamePlaylist(id, name)
                    renamePlaylistId = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamePlaylistId = null }) { Text("Cancel") }
            },
        )
    }

    if (pendingDeletePlaylistId != null) {
        val playlistName = playlists.firstOrNull { it.id == pendingDeletePlaylistId }?.name ?: "this playlist"
        AlertDialog(
            onDismissRequest = { pendingDeletePlaylistId = null },
            title = { Text("Delete playlist?") },
            text = { Text("Delete \"$playlistName\"? This removes the playlist entry but does not delete song files.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeletePlaylistId?.let { onDeletePlaylist(it) }
                    pendingDeletePlaylistId = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePlaylistId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistsEngine.Playlist,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.songFilenames.size} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRename) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Rename playlist",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "Delete playlist",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TileCard(tile: BrowseTile, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tile.category.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tile.count > 0) {
                Text(
                    text = tile.count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (tile.previewArt.isEmpty() || tile.previewArt.all { it == null }) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Empty",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TileArtCell(tile.previewArt.getOrNull(0), Modifier.weight(1f).fillMaxSize())
                        TileArtCell(tile.previewArt.getOrNull(1), Modifier.weight(1f).fillMaxSize())
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TileArtCell(tile.previewArt.getOrNull(2), Modifier.weight(1f).fillMaxSize())
                        TileArtCell(tile.previewArt.getOrNull(3), Modifier.weight(1f).fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun TileArtCell(path: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            ArtThumbnail(filePath = path, size = 72.dp, cornerRadius = 0.dp)
        }
    }
}

/** Songs with on-disk paths, newest file modification first. */
fun lastAddedSongs(songs: List<Song>): List<Song> =
    songs.filter { it.filePath != null }
        .sortedWith(compareByDescending<Song> { it.dateModified }.thenBy { it.filename })

/** Format remaining cooldown time for display (e.g. "4h 23m" or "12m"). */
fun formatRemainingMs(remainingMs: Long): String {
    val totalMinutes = (remainingMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** Compute the browse tiles in display order. */
fun buildBrowseTiles(
    songs: List<Song>,
    historyStats: Map<String, HistoryEngine.Stats>,
    historyEvents: List<HistoryEngine.Event>,
    favorites: Set<String>,
    disliked: Set<String>,
    cooldownEntries: List<RecentlySurfacedTracker.ActiveEntry> = emptyList(),
): List<BrowseTile> {
    val byFilename = songs.associateBy { it.filename }

    val mostPlayedAll = historyStats.entries
        .filter { it.value.plays > 0 }
        .sortedByDescending { it.value.plays }
        .mapNotNull { byFilename[it.key] }

    val recentlyPlayedAll = historyEvents
        .distinctBy { it.filename }
        .mapNotNull { byFilename[it.filename] }

    val resumesInSongs = cooldownEntries.mapNotNull { byFilename[it.filename] }

    val neverPlayed = songs.filter { it.filename !in historyStats.keys && it.filePath != null }
    val lastAddedAll = lastAddedSongs(songs)
    val favSongs = songs.filter { it.filename in favorites }
    val disSongs = songs.filter { it.filename in disliked }

    return listOf(
        BrowseTile(BrowseCategory.MostPlayed, mostPlayedAll.size, mostPlayedAll.take(4).map { it.filePath }),
        BrowseTile(BrowseCategory.RecentlyPlayed, recentlyPlayedAll.size, recentlyPlayedAll.take(4).map { it.filePath }),
        BrowseTile(BrowseCategory.ResumesIn, cooldownEntries.size,
            resumesInSongs.take(4).map { it.filePath }),
        BrowseTile(BrowseCategory.NeverPlayed, neverPlayed.size,
            neverPlayed.shuffled().take(4).map { it.filePath }),
        BrowseTile(BrowseCategory.LastAdded, lastAddedAll.size, lastAddedAll.take(4).map { it.filePath }),
        BrowseTile(BrowseCategory.Favorites, favSongs.size,
            favSongs.take(4).map { it.filePath }),
        BrowseTile(BrowseCategory.Disliked, disSongs.size,
            disSongs.take(4).map { it.filePath }),
    )
}

/** Expand a tile into its full song list (for ViewAllScreen). */
fun browseCategorySongs(
    category: BrowseCategory,
    songs: List<Song>,
    historyStats: Map<String, HistoryEngine.Stats>,
    historyEvents: List<HistoryEngine.Event>,
    favorites: Set<String>,
    disliked: Set<String>,
    cooldownEntries: List<RecentlySurfacedTracker.ActiveEntry> = emptyList(),
): List<Song> {
    val byFilename = songs.associateBy { it.filename }
    return when (category) {
        BrowseCategory.MostPlayed -> historyStats.entries
            .filter { it.value.plays > 0 }
            .sortedByDescending { it.value.plays }
            .mapNotNull { byFilename[it.key] }
        BrowseCategory.RecentlyPlayed -> historyEvents
            .distinctBy { it.filename }
            .mapNotNull { byFilename[it.filename] }
        BrowseCategory.ResumesIn -> cooldownEntries.mapNotNull { byFilename[it.filename] }
        BrowseCategory.NeverPlayed -> songs.filter { it.filename !in historyStats.keys && it.filePath != null }
        BrowseCategory.LastAdded -> lastAddedSongs(songs)
        BrowseCategory.Favorites -> songs.filter { it.filename in favorites }
        BrowseCategory.Disliked -> songs.filter { it.filename in disliked }
    }
}
