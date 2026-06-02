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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.Song
import com.isaivazhi.app.ui.songHasEmbedding

@Composable
fun SongsScreen(
    songs: List<Song>,
    currentMediaId: String?,
    permissionGranted: Boolean = true,
    // Push #59: red-dot lookup switched from filename to filepath. The
    // filename-based check in earlier pushes misclassified songs whose
    // MediaStore DISPLAY_NAME differs from the on-disk filename — they
    // showed a red "not embedded" dot even though they WERE embedded.
    embeddedFilepaths: Set<String> = emptySet(),
    embeddingsRowCount: Int? = null,
    onSongTap: (Song) -> Unit,
    onSongLongPress: (Song) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (songs.isEmpty()) {
        val emptyMessage = when {
            !permissionGranted ->
                "Grant storage permission to scan your music library."
            else ->
                "No songs found on this device. Open Settings → Rescan music library to try again."
        }
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        // Composite key (index + id) defends against duplicate IDs returned
        // from MediaStore or a stale cache that would crash LazyColumn with
        // "Key was already used" (push #37 fix).
        itemsIndexed(songs, key = { i, s -> "song_${i}_${s.id}" }) { _, song ->
            SongRow(
                song = song,
                isCurrent = song.filename == currentMediaId,
                hasEmbedding = songHasEmbedding(
                    filePath = song.filePath,
                    embeddingsRowCount = embeddingsRowCount,
                    embeddedFilepaths = embeddedFilepaths,
                ),
                onClick = { onSongTap(song) },
                onLongClick = { onSongLongPress(song) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    hasEmbedding: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val rowBg = if (isCurrent) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.background

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(rowBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumbnail(filePath = song.filePath, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!hasEmbedding) {
                    NoEmbeddingDot()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = song.title.ifBlank { song.filename },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(song.artist, song.album).filter { it.isNotBlank() }
                    .joinToString(" • ").ifEmpty { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
