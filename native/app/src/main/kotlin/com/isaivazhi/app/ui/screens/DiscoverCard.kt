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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.Recommender
import com.isaivazhi.app.engine.Song

/**
 * Section header for Discover. Optional trailing chip ("Freeze" toggle,
 * "View all", etc.).
 */
@Composable
fun DiscoverSectionHeader(
    title: String,
    subtitle: String? = null,
    onOpenAll: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpenAll != null) Modifier.clickable(onClick = onOpenAll) else Modifier)
            // Push #39: drop top padding from 18 dp → 4 dp so the first
            // section header (Most Similar) sits flush against the
            // "Discover" top bar instead of behind a ~22 dp empty band.
            // Later sections still get breathing room from the card row
            // above them.
            .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
        if (onOpenAll != null && trailing == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "View all",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Horizontal-scrolling row of song cards. Used for For You / Because You
 * Played / Unexplored / Most Similar sections.
 *
 * Each card: art (square 140dp), title, artist, similarity % (when provided).
 * Tap → onTap(song, index); long-press → onLongPress(song).
 */
@Composable
fun DiscoverCardRow(
    songs: List<Pair<Song, Float?>>,  // (song, optional similarity 0..1)
    currentMediaId: String?,
    // Push #59: filepath-based red-dot lookup (was filename).
    embeddedFilepaths: Set<String> = emptySet(),
    onTap: (Song, Int) -> Unit,
    onLongPress: (Song) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(songs) { index, (song, sim) ->
            DiscoverCard(
                song = song,
                similarity = sim,
                isCurrent = song.filename == currentMediaId,
                hasEmbedding = embeddedFilepaths.isEmpty() || song.filePath in embeddedFilepaths,
                onClick = { onTap(song, index) },
                onLongClick = { onLongPress(song) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverCard(
    song: Song,
    similarity: Float?,
    isCurrent: Boolean,
    hasEmbedding: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(108.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.background
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        Box(modifier = Modifier.size(92.dp)) {
            ArtThumbnail(filePath = song.filePath, size = 92.dp, cornerRadius = 6.dp)
            if (!hasEmbedding) {
                // Red dot overlay top-left for songs without embeddings —
                // they don't participate in recommendations.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    NoEmbeddingDot(size = 8.dp)
                }
            }
            if (similarity != null) {
                // Similarity chip in the top-right corner.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "${(similarity * 100).toInt().coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.title.ifBlank { song.filename },
            style = MaterialTheme.typography.titleSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = song.artist.ifBlank { "Unknown artist" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact pill-style header chip — used for "Freeze" toggle next to section titles. */
@Composable
fun HeaderChip(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
