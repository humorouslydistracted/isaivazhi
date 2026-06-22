package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.isaivazhi.app.engine.PlaybackEngine
import com.isaivazhi.app.engine.Song

@Composable
fun NowPlayingScreen(
    state: PlaybackEngine.PlaybackState,
    positionMs: Long,
    durationMs: Long,
    currentSongFilePath: String?,
    isFavorite: Boolean,
    isDisliked: Boolean,
    onClose: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDislike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onTogglePause: () -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = false,
    refreshInProgress: Boolean = false,
    similarSongs: List<Song> = emptyList(),
    similarLoading: Boolean = false,
    similarFrozen: Boolean = false,
    similarSeedTitle: String? = null,
    queueAllSimilarEnabled: Boolean = false,
    onSimilarTap: (Song) -> Unit = {},
    onSimilarPlayNext: (Song) -> Unit = {},
    onSimilarLongPress: (Song) -> Unit = {},
    onQueueAllSimilar: () -> Unit = {},
    onToggleSimilarFrozen: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Now playing",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Balance close button — favorite lives in the bottom control row.
                Spacer(Modifier.width(48.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtThumbnail(
                        filePath = currentSongFilePath,
                        size = 280.dp,
                        cornerRadius = 16.dp,
                        sampleSize = 1,
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = state.title.ifBlank { state.currentMediaId ?: "" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = listOf(state.artist, state.album).filter { it.isNotBlank() }
                        .joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))

                ScrubBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = onSeek,
                )

                Spacer(Modifier.height(6.dp))

                TransportControlsRow(
                    state = state,
                    onSkipPrev = onSkipPrev,
                    onTogglePause = onTogglePause,
                    onSkipNext = onSkipNext,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                SecondaryControlsRow(
                    state = state,
                    isFavorite = isFavorite,
                    isDisliked = isDisliked,
                    onCycleRepeat = onCycleRepeat,
                    onToggleShuffle = onToggleShuffle,
                    onRefresh = onRefresh,
                    refreshEnabled = refreshEnabled,
                    refreshInProgress = refreshInProgress,
                    onToggleDislike = onToggleDislike,
                    onToggleFavorite = onToggleFavorite,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                if (similarLoading || similarSongs.isNotEmpty()) {
                    SimilarSongsRow(
                        songs = similarSongs,
                        loading = similarLoading,
                        frozen = similarFrozen,
                        seedTitle = similarSeedTitle,
                        queueAllEnabled = queueAllSimilarEnabled,
                        onTap = onSimilarTap,
                        onPlayNext = onSimilarPlayNext,
                        onLongPress = onSimilarLongPress,
                        onQueueAll = onQueueAllSimilar,
                        onToggleFrozen = onToggleSimilarFrozen,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private val FullPlayerSecondaryIconSize = 24.dp
private val FullPlayerTransportSkipSize = 32.dp
private val FullPlayerTransportPlaySize = 48.dp

@Composable
private fun TransportControlsRow(
    state: PlaybackEngine.PlaybackState,
    onSkipPrev: () -> Unit,
    onTogglePause: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inactiveTint = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onSkipPrev) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                tint = inactiveTint,
                modifier = Modifier.size(FullPlayerTransportSkipSize),
            )
        }
        IconButton(onClick = onTogglePause) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(FullPlayerTransportPlaySize),
            )
        }
        IconButton(onClick = onSkipNext) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next (neutral)",
                tint = inactiveTint,
                modifier = Modifier.size(FullPlayerTransportSkipSize),
            )
        }
    }
}

/** loop / shuffle / refresh / dislike / favorite — mini-player order minus transport */
@Composable
private fun SecondaryControlsRow(
    state: PlaybackEngine.PlaybackState,
    isFavorite: Boolean,
    isDisliked: Boolean,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onRefresh: (() -> Unit)?,
    refreshEnabled: Boolean,
    refreshInProgress: Boolean,
    onToggleDislike: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inactiveTint = MaterialTheme.colorScheme.onBackground
    val repeatIcon = when (state.repeatMode) {
        Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
        else -> Icons.Filled.Repeat
    }
    val repeatActive = state.repeatMode != Player.REPEAT_MODE_OFF

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlSlot {
            IconButton(onClick = onCycleRepeat) {
                Icon(
                    imageVector = repeatIcon,
                    contentDescription = "Repeat",
                    tint = if (repeatActive) MaterialTheme.colorScheme.primary else inactiveTint,
                    modifier = Modifier.size(FullPlayerSecondaryIconSize),
                )
            }
        }
        ControlSlot {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else inactiveTint,
                    modifier = Modifier.size(FullPlayerSecondaryIconSize),
                )
            }
        }
        if (onRefresh != null) {
            ControlSlot {
                IconButton(
                    onClick = onRefresh,
                    enabled = refreshEnabled && !refreshInProgress,
                ) {
                    if (refreshInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(FullPlayerSecondaryIconSize),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh Up Next",
                            tint = if (refreshEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(FullPlayerSecondaryIconSize),
                        )
                    }
                }
            }
        }
        ControlSlot {
            IconButton(onClick = onToggleDislike) {
                Icon(
                    imageVector = if (isDisliked) Icons.Filled.ThumbDownAlt else Icons.Filled.ThumbDown,
                    contentDescription = if (isDisliked) "Remove dislike" else "Dislike",
                    tint = if (isDisliked) MaterialTheme.colorScheme.error else inactiveTint,
                    modifier = Modifier.size(FullPlayerSecondaryIconSize),
                )
            }
        }
        ControlSlot {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else inactiveTint,
                    modifier = Modifier.size(FullPlayerSecondaryIconSize),
                )
            }
        }
    }
}

@Composable
private fun RowScope.ControlSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ScrubBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val durationSafe = if (durationMs > 0) durationMs else 1L
    val displayValue = if (dragging) dragValue else
        (positionMs.toFloat() / durationSafe.toFloat()).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayValue,
            onValueChange = { v ->
                dragging = true
                dragValue = v
            },
            onValueChangeFinished = {
                onSeek((dragValue * durationMs).toLong())
                dragging = false
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(if (dragging) (dragValue * durationMs).toLong() else positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val s = ms / 1000
    val m = s / 60
    val r = s % 60
    return "$m:${r.toString().padStart(2, '0')}"
}
