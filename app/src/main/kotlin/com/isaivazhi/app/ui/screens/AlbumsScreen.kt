package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.AlbumGroup
import com.isaivazhi.app.engine.Song
import com.isaivazhi.app.engine.groupIntoAlbums
import com.isaivazhi.app.ui.formatDuration
import com.isaivazhi.app.ui.songHasEmbedding

@Composable
fun AlbumsScreen(
    songs: List<Song>,
    currentMediaId: String?,
    // Push #59: filepath-based red-dot lookup (was filename, which
    // misclassified songs with DISPLAY_NAME drift).
    embeddedFilepaths: Set<String> = emptySet(),
    embeddingsRowCount: Int? = null,
    initialExpandedAlbum: String? = null,
    revealRequestId: Int = 0,
    onPlayAlbum: (albumTracks: List<Song>, startIndex: Int) -> Unit,
    onSongLongPress: (Song) -> Unit = {},
    // Push #62: album-level long-press → opens a menu (Play / Shuffle /
    // Delete). The shuffle variant plays the album tracks in random
    // order; delete triggers a batch delete request through the
    // standard scoped-storage flow. Caller orchestrates the menu UI
    // and decides what each action does.
    onAlbumLongPress: (albumTracks: List<Song>, albumName: String) -> Unit = { _, _ -> },
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val albums = remember(songs) { groupIntoAlbums(songs) }
    var expandedAlbum by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(initialExpandedAlbum, revealRequestId, albums) {
        val target = initialExpandedAlbum ?: return@LaunchedEffect
        val idx = albums.indexOfFirst { it.name == target }
        if (idx >= 0) {
            expandedAlbum = target
            runCatching { listState.animateScrollToItem(idx) }
        }
    }

    if (albums.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No albums found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        items(albums, key = { it.name }) { album ->
            val isExpanded = expandedAlbum == album.name
            AlbumHeader(
                album = album,
                expanded = isExpanded,
                onToggle = { expandedAlbum = if (isExpanded) null else album.name },
                onLongPress = { onAlbumLongPress(album.tracks, album.name) },
            )
            if (isExpanded) {
                album.tracks.forEachIndexed { i, t ->
                    AlbumTrackRow(
                        index = i + 1,
                        track = t,
                        isCurrent = t.filename == currentMediaId,
                        hasEmbedding = songHasEmbedding(
                            filePath = t.filePath,
                            embeddingsRowCount = embeddingsRowCount,
                            embeddedFilepaths = embeddedFilepaths,
                        ),
                        onTap = { onPlayAlbum(album.tracks, i) },
                        onLongPress = { onSongLongPress(t) },
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumHeader(
    album: AlbumGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumbnail(filePath = album.firstArtPath, size = 56.dp, cornerRadius = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${album.artist} • ${album.tracks.size} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    index: Int,
    track: Song,
    isCurrent: Boolean,
    hasEmbedding: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val rowBg = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.background
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .background(rowBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!hasEmbedding) {
                    NoEmbeddingDot()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = track.title.ifBlank { track.filename },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.artist.ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
