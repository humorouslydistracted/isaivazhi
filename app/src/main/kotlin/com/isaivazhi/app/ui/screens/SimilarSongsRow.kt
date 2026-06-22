package com.isaivazhi.app.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.Song

enum class SimilarSongsLayout {
    HorizontalCards,
    VerticalList,
}

@Composable
fun SimilarSongsRow(
    songs: List<Song>,
    loading: Boolean,
    frozen: Boolean,
    seedTitle: String?,
    queueAllEnabled: Boolean,
    onTap: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onLongPress: (Song) -> Unit,
    onQueueAll: () -> Unit,
    onToggleFrozen: () -> Unit,
    modifier: Modifier = Modifier,
    layout: SimilarSongsLayout = SimilarSongsLayout.HorizontalCards,
    listMaxHeight: Dp? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val title = if (!loading && songs.isNotEmpty()) {
                    "Similar songs · ${songs.size}"
                } else {
                    "Similar songs"
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (frozen && !seedTitle.isNullOrBlank()) {
                    Text(
                        text = "Pinned · similar to $seedTitle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onQueueAll,
                enabled = queueAllEnabled && !loading,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlaylistPlay,
                    contentDescription = "Queue all similar after current",
                    tint = if (queueAllEnabled && !loading) MaterialTheme.colorScheme.onBackground
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onToggleFrozen) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = if (frozen) "Unfreeze similar songs" else "Freeze similar songs",
                    tint = if (frozen) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        when (layout) {
            SimilarSongsLayout.HorizontalCards -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    if (songs.isEmpty() && loading) {
                        items(5) {
                            SimilarCard(song = null, onTap = {}, onPlayNext = {}, onLongPress = {})
                        }
                    } else {
                        items(songs, key = { it.filename }) { song ->
                            SimilarCard(
                                song = song,
                                onTap = { onTap(song) },
                                onPlayNext = { onPlayNext(song) },
                                onLongPress = { onLongPress(song) },
                            )
                        }
                    }
                }
            }
            SimilarSongsLayout.VerticalList -> {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (listMaxHeight != null) {
                                Modifier
                                    .heightIn(max = listMaxHeight)
                                    .verticalScroll(scrollState)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    if (songs.isEmpty() && loading) {
                        repeat(5) {
                            SimilarListItemSkeleton()
                        }
                    } else {
                        songs.forEach { song ->
                            SimilarListItem(
                                song = song,
                                onTap = { onTap(song) },
                                onPlayNext = { onPlayNext(song) },
                                onLongPress = { onLongPress(song) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SimilarListItem(
    song: Song,
    onTap: () -> Unit,
    onPlayNext: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumbnail(
            filePath = song.filePath,
            size = 44.dp,
            cornerRadius = 4.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = song.title.ifBlank { song.filename },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        IconButton(onClick = onPlayNext) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Play next",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun SimilarListItemSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.width(40.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SimilarCard(
    song: Song?,
    onTap: () -> Unit,
    onPlayNext: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(modifier = Modifier.width(112.dp)) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    enabled = song != null,
                    onClick = onTap,
                    onLongClick = onLongPress,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (song?.filePath != null) {
                    ArtThumbnail(
                        filePath = song.filePath,
                        size = 112.dp,
                        cornerRadius = 10.dp,
                        sampleSize = 4,
                    )
                }
            }
            if (song != null) {
                IconButton(
                    onClick = onPlayNext,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(32.dp)
                        .padding(2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Play next",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(20.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = song?.title ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
