package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.media3.common.Player
import com.isaivazhi.app.engine.PlaybackEngine

/**
 * Mini player — 3 visible sections matching the Capacitor reference layout:
 *
 *   Row 1: art | title + artist + mode chip | favorite
 *   Row 2: draggable progress Slider
 *   Row 3: loop / shuffle / prev / play-pause / next / refresh / dislike
 *
 * Tapping anywhere on Row 1 (outside the buttons) expands to NowPlayingScreen.
 */
@Composable
fun MiniPlayer(
    state: PlaybackEngine.PlaybackState,
    positionMs: Long,
    durationMs: Long,
    currentSongFilePath: String?,
    isFavorite: Boolean,
    isDisliked: Boolean,
    aiMode: Boolean,
    /** Library has at least one embedding row — when false, mode chip shows Shuffle. */
    hasEmbeddingsInLibrary: Boolean,
    hasEmbedding: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleDislike: () -> Unit,
    onExpand: () -> Unit,
    onTogglePause: () -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,         // neutral skip
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSeek: (Long) -> Unit,
    // Phase E (2026-06-01): Refresh-Up-Next button. Replaces the lockscreen-
    // /mini-player-only "Up Next refresh" feature that previously lived only
    // on the UpNext tab. When null OR refreshEnabled=false the icon is
    // hidden — callers pass null on contexts where AI refresh wouldn't make
    // sense (e.g. ALBUM / BROWSE_SECTION queues).
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = false,
    refreshInProgress: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (state.currentMediaId == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Row 1 — info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtThumbnail(
                filePath = currentSongFilePath,
                size = 44.dp,
                cornerRadius = 4.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!hasEmbedding) {
                        NoEmbeddingDot()
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = state.title.ifBlank { state.currentMediaId },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.artist.isNotBlank() || state.album.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = listOf(state.artist, state.album).filter { it.isNotBlank() }
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Up Next mode: Shuffle when the library has no embeddings; otherwise
            // show AI/Shuffle for the current song only when it is embedded.
            when {
                !hasEmbeddingsInLibrary -> ModeChip(aiMode = false)
                hasEmbedding -> ModeChip(aiMode = aiMode)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Row 2 — draggable progress
        DraggableProgress(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeek = onSeek,
        )

        // Row 3 — controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Loop
            val repeatIcon = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                else -> Icons.Filled.Repeat
            }
            val repeatActive = state.repeatMode != Player.REPEAT_MODE_OFF
            IconButton(onClick = onCycleRepeat) {
                Icon(
                    imageVector = repeatIcon,
                    contentDescription = "Repeat",
                    tint = if (repeatActive) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
            // Shuffle (queue order)
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
            // Previous
            IconButton(onClick = onSkipPrev) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(26.dp),
                )
            }
            // Play/Pause (primary, larger — center slot)
            IconButton(onClick = onTogglePause) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
            // Neutral skip (next)
            IconButton(onClick = onSkipNext) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next (neutral)",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(26.dp),
                )
            }
            // Refresh Up Next (AI) — only when caller supplied a handler.
            // Greyed when no current song / no embeddings.
            if (onRefresh != null) {
                IconButton(
                    onClick = onRefresh,
                    enabled = refreshEnabled && !refreshInProgress,
                ) {
                    if (refreshInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh Up Next",
                            tint = if (refreshEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            // Dislike (thumbs down) — skips with penalty
            IconButton(onClick = onToggleDislike) {
                Icon(
                    imageVector = if (isDisliked) Icons.Filled.ThumbDownAlt else Icons.Filled.ThumbDown,
                    contentDescription = if (isDisliked) "Remove dislike" else "Dislike",
                    tint = if (isDisliked) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Draggable progress slider — local state tracks the drag, commits via
 * onSeek when the user releases. Reads from playback state otherwise so
 * the slider stays in sync during normal playback.
 *
 * Push #39: thinner track + start/end time labels on either side. The
 * thumb is a narrow vertical pill (4 × 14 dp) rather than the default
 * 20 dp circle, and the track is 2 dp tall instead of 4 dp.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DraggableProgress(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val dur = durationMs.coerceAtLeast(0L)
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val livePos = positionMs.coerceIn(0L, dur.coerceAtLeast(1L))
    val sliderValue = dragValue ?: livePos.toFloat()
    // Show the in-progress drag time in the start label, otherwise the
    // live position so the user sees the seekbar position update as
    // they drag.
    val displayedPositionMs: Long = (dragValue?.toLong() ?: livePos).coerceIn(0L, dur.coerceAtLeast(1L))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatPlaybackTime(displayedPositionMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Slider(
            value = sliderValue,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeek(it.toLong()) }
                dragValue = null
            },
            valueRange = 0f..(dur.coerceAtLeast(1L).toFloat()),
            interactionSource = interactionSource,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(16.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 14.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(2.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            },
        )
        Text(
            text = formatPlaybackTime(dur),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** mm:ss for the mini-player time labels. Songs over an hour show h:mm:ss. */
private fun formatPlaybackTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun ModeChip(aiMode: Boolean) {
    val (label, color) = if (aiMode) "AI" to MaterialTheme.colorScheme.primary
    else "Shuffle" to MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
