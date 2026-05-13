package com.isaivazhi.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.Recommender
import com.isaivazhi.app.engine.Song
import kotlinx.coroutines.launch

/**
 * Section identifier — used by the section header tap callback so MainActivity
 * can open the matching ViewAll list with the right title + songs.
 */
sealed class DiscoverSectionRef {
    data object MostSimilar : DiscoverSectionRef()
    data object ForYou : DiscoverSectionRef()
    data class BecauseYouPlayed(val sourceId: Int, val sourceTitle: String) : DiscoverSectionRef()
    data class Unexplored(val clusterIndex: Int, val label: String) : DiscoverSectionRef()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    mostSimilar: List<Recommender.ScoredSong>,
    forYou: List<Recommender.ScoredSong>,
    becauseYouPlayed: List<Pair<Song, List<Recommender.ScoredSong>>>,
    unexploredClusters: List<List<Song>>,
    freezeMostSimilar: Boolean,
    onToggleFreeze: () -> Unit,
    embeddingProgress: String?,
    currentMediaId: String?,
    // Push #59: filepath-based red-dot lookup (was filename, which
    // misclassified songs whose MediaStore DISPLAY_NAME differs from
    // their on-disk filename).
    embeddedFilepaths: Set<String> = emptySet(),
    currentSongHasEmbedding: Boolean = true,
    // Push #64: compact blend/engine info strip at the top of the page.
    // Null hides the strip (e.g. before tuning has loaded).
    engineSnapshot: EngineSnapshot? = null,
    onOpenTaste: () -> Unit = {},
    onOpenAiPage: () -> Unit = {},
    onCardTap: (Song) -> Unit,
    onSongLongPress: (Song) -> Unit = {},
    onRefresh: suspend () -> Unit,
    onOpenSection: (DiscoverSectionRef) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val unexploredLabels = listOf(
        "Sound you rarely visit",
        "Another pocket of your library",
        "Off the beaten path",
    )

    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                try { onRefresh() } finally { refreshing = false }
            }
        },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                CustomPullIndicator(state = pullState, isRefreshing = refreshing)
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            // Push #64: compact blend / queue-mode strip at top. Tapping
            // navigates to the Taste page where the full snapshot lives.
            if (engineSnapshot != null) {
                item("insights_strip") {
                    DiscoverInsightsStrip(snapshot = engineSnapshot, onClick = onOpenTaste)
                }
            }
            if (embeddingProgress != null) {
                item("emb_progress") {
                    Text(
                        text = embeddingProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            // Most Similar — always render the header when there's a current
            // song so the section doesn't flicker/vanish when transitioning
            // between embedded and non-embedded tracks. Body is either cards
            // or a "no embedding for this song" placeholder.
            if (currentMediaId != null) {
                item("most_similar") {
                    DiscoverSectionHeader(
                        title = "Most Similar",
                        onOpenAll = if (mostSimilar.isNotEmpty())
                            ({ onOpenSection(DiscoverSectionRef.MostSimilar) }) else null,
                        trailing = if (mostSimilar.isNotEmpty()) {{
                            HeaderChip(
                                label = if (freezeMostSimilar) "Frozen" else "Freeze",
                                active = freezeMostSimilar,
                                onClick = onToggleFreeze,
                            )
                        }} else null,
                    )
                    when {
                        currentSongHasEmbedding && mostSimilar.isNotEmpty() -> {
                            DiscoverCardRow(
                                songs = mostSimilar.map { it.song to it.similarity },
                                currentMediaId = currentMediaId,
                                embeddedFilepaths = embeddedFilepaths,
                                onTap = { song, _ -> onCardTap(song) },
                                onLongPress = onSongLongPress,
                            )
                        }
                        !currentSongHasEmbedding -> {
                            NoEmbeddingPlaceholder(onOpenAiPage = onOpenAiPage)
                        }
                        else -> {
                            // Embedded but no results yet — usually means the
                            // recommender is still computing. Show a brief
                            // status row instead of flicker.
                            Text(
                                text = "Computing similar songs…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    }
                }
            }

            // For You
            if (forYou.isNotEmpty()) {
                item("for_you") {
                    DiscoverSectionHeader(
                        title = "For You",
                        subtitle = "Personalized from your top-played picks.",
                        onOpenAll = { onOpenSection(DiscoverSectionRef.ForYou) },
                    )
                    DiscoverCardRow(
                        songs = forYou.map { it.song to it.similarity },
                        currentMediaId = currentMediaId,
                        embeddedFilepaths = embeddedFilepaths,
                        onTap = { song, _ -> onCardTap(song) },
                        onLongPress = onSongLongPress,
                    )
                }
            }

            // Because You Played [X] — between For You and Unexplored
            for ((idx, pair) in becauseYouPlayed.withIndex()) {
                val (src, sims) = pair
                if (sims.isEmpty()) continue
                item(key = "byp_${src.id}") {
                    DiscoverSectionHeader(
                        title = "Because you played",
                        subtitle = src.title.ifBlank { src.filename },
                        onOpenAll = {
                            onOpenSection(
                                DiscoverSectionRef.BecauseYouPlayed(
                                    sourceId = src.id,
                                    sourceTitle = src.title.ifBlank { src.filename },
                                )
                            )
                        },
                    )
                    DiscoverCardRow(
                        songs = sims.map { it.song to it.similarity },
                        currentMediaId = currentMediaId,
                        embeddedFilepaths = embeddedFilepaths,
                        onTap = { song, _ -> onCardTap(song) },
                        onLongPress = onSongLongPress,
                    )
                }
            }

            // Unexplored Sounds — 3 labeled clusters
            for ((i, cluster) in unexploredClusters.withIndex()) {
                if (cluster.isEmpty()) continue
                val label = unexploredLabels.getOrElse(i) { "Unexplored" }
                item(key = "unexp_$i") {
                    DiscoverSectionHeader(
                        title = "Unexplored Sounds",
                        subtitle = label,
                        onOpenAll = { onOpenSection(DiscoverSectionRef.Unexplored(i, label)) },
                    )
                    DiscoverCardRow(
                        songs = cluster.map { it to null },
                        currentMediaId = currentMediaId,
                        embeddedFilepaths = embeddedFilepaths,
                        onTap = { song, _ -> onCardTap(song) },
                        onLongPress = onSongLongPress,
                    )
                }
            }

            if (mostSimilar.isEmpty() && forYou.isEmpty() &&
                becauseYouPlayed.all { it.second.isEmpty() } &&
                unexploredClusters.all { it.isEmpty() }
            ) {
                item("empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Pull down to refresh once you've played some songs.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoEmbeddingPlaceholder(onOpenAiPage: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpenAiPage)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column {
            Text(
                text = "AI embeddings not available for this song.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Embed it on the AI page to see similar tracks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Push #64: one-line blend/engine info at the top of Discover. Capacitor
 * parity: surfaces `getInsights().blend` (Current/Session/Profile split +
 * mode label) plus the queue mode (AI/Shuffle) without forcing users to
 * navigate to the Taste page to see the recommender's current state.
 *
 * Tap → opens the Taste page where the full collapsible snapshot lives.
 */
@Composable
private fun DiscoverInsightsStrip(snapshot: EngineSnapshot, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Blend",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${snapshot.blendCurrent} / ${snapshot.blendSession} / ${snapshot.blendProfile}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "· ${snapshot.blendMode}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Up Next: ${snapshot.queueSize} ${snapshot.queueMode}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Custom pull-to-refresh indicator: a pill that tracks pull distance.
 *   - Off-screen at rest
 *   - Gray → green color interpolation as the user drags
 *   - Green + "Release to refresh" once distanceFraction ≥ 1.0
 *   - Spinner + "Refreshing…" once the user releases past threshold
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomPullIndicator(state: PullToRefreshState, isRefreshing: Boolean) {
    val rawFrac = state.distanceFraction.coerceIn(0f, 1f)
    val animatedFrac by animateFloatAsState(
        targetValue = if (isRefreshing) 1f else rawFrac,
        label = "pullFrac",
    )
    // No indicator while completely at rest and not refreshing.
    if (animatedFrac <= 0.01f && !isRefreshing) return

    val startColor = Color(0xFF6B7280)   // gray
    val endColor = Color(0xFF34D399)     // green at threshold
    val pillColor = lerp(startColor, endColor, animatedFrac)

    val label = when {
        isRefreshing -> "Refreshing…"
        animatedFrac >= 1f -> "Release to refresh"
        else -> "Pull to refresh"
    }

    // Pill sits below the system bar; offset grows with pull distance.
    val maxOffset = 56.dp
    val offsetY = maxOffset * animatedFrac

    Row(
        modifier = Modifier
            .offset(y = offsetY)
            .clip(RoundedCornerShape(20.dp))
            .background(pillColor)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
