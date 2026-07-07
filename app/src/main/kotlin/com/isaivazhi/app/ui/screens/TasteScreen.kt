package com.isaivazhi.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.HistoryEngine
import com.isaivazhi.app.engine.SignalTimelineEngine
import com.isaivazhi.app.engine.Song
import com.isaivazhi.app.engine.TasteEngine
import com.isaivazhi.app.engine.SessionBlendInterval
import com.isaivazhi.app.engine.TasteEventKind
import com.isaivazhi.app.engine.buildOutboundSimilarityIndex
import com.isaivazhi.app.engine.signedDirectScore
import kotlin.math.abs

private const val DEFAULT_LIMIT = 10

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasteScreen(
    tuning: TasteEngine.Tuning,
    songs: List<Song>,
    stats: Map<String, HistoryEngine.Stats>,
    signals: Map<String, TasteEngine.TasteSignal>,
    // Push #63: decorated signal map carrying chips (Favorite/Disliked/Mixed/
    // Similarity/Top ±30/Short-listened/Up-Next-excluded/Neutral).
    decoratedSignals: TasteEngine.DecoratedSignals,
    /** Hard-block set used by Up Next at the current Negative guard slider (not display-only). */
    upNextHardBlockedCount: Int = 0,
    /** Overall-score blocks passed to active Up Next generation. */
    upNextExcludedFilenames: Set<String> = emptySet(),
    favoritesSet: Set<String>,
    dislikedSet: Set<String>,
    timelineEvents: List<SignalTimelineEngine.Event>,
    sessionBlendIntervals: List<SessionBlendInterval> = emptyList(),
    engineSnapshot: EngineSnapshot,
    onBack: () -> Unit,
    onAdventurousChange: (Float) -> Unit,
    onTasteInfluenceChange: (Float) -> Unit,
    onManualBlendChange: (current: Float, session: Float) -> Unit,
    onBlendAutoReset: () -> Unit,
    onNegativeStrengthChange: (Float) -> Unit,
    onAdventurousReset: () -> Unit,
    onTasteInfluenceReset: () -> Unit,
    onNegativeStrengthReset: () -> Unit,
    onResetSongStats: (filename: String) -> Unit,
    onResetEngineConfirmed: () -> Unit,
    onCopyTimelineText: (text: String) -> Unit,
    onCopyTimelineSummary: (text: String) -> Unit,
    onPlayOrderedList: (queue: List<Song>, startIndex: Int) -> Unit,
    /** Long-press Taste header → Diagnostics (Activity tab). */
    onOpenActivityLog: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current

    // Signals + UI filter/sort state
    var filterAll by remember { mutableStateOf(false) }  // false=Active only, true=All embedded
    var sortDescending by remember { mutableStateOf(true) } // true=Top positive, false=Top negative
    var snapshotExpanded by remember { mutableStateOf(true) }
    val hasSessionBlendIntervals = sessionBlendIntervals.isNotEmpty()
    var sessionBlendExpanded by remember(hasSessionBlendIntervals) {
        mutableStateOf(hasSessionBlendIntervals)
    }
    var timelineExpanded by remember { mutableStateOf(false) }
    // Push #72: paginate "Last 30 Playback Signal Updates" — show 10
    // initially, then Load More bumps by 10 up to the 30 the backend caps at.
    var timelineVisibleCount by remember { mutableStateOf(10) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val outboundSimilarityIndex = remember(signals) {
        buildOutboundSimilarityIndex(signals)
    }
    val songsByFilename = remember(songs) { songs.associateBy { it.filename } }

    // Build one row per song in the library. For embedded songs with a
    // taste signal, use the DecoratedRow (carries chips + recency-applied
    // breakdown). For songs with only HistoryEngine stats and no signal
    // entry, fall back to a synthetic SignalRowData with score = plays×avgFrac.
    val rows: List<SignalRowData> = remember(
        songs,
        decoratedSignals,
        stats,
        favoritesSet,
        dislikedSet,
        upNextExcludedFilenames,
        outboundSimilarityIndex,
    ) {
        val decorRows = decoratedSignals.rows
        songs.map { song ->
            val fn = song.filename
            val isFavorite = fn in favoritesSet
            val isDisliked = fn in dislikedSet
            val excludedFromUpNext = fn in upNextExcludedFilenames && !isDisliked
            val dec = decorRows[fn]
            if (dec != null) {
                val bd = dec.breakdown
                var favoritePrior = dec.signal.favoritePrior
                var dislikePrior = dec.signal.dislikePrior
                SignalRowData(
                    song = song,
                    plays = dec.signal.plays,
                    avgFraction = dec.signal.avgFraction,
                    xScore = (bd.negativeWeight - dislikePrior).coerceAtLeast(0f),
                    similarityBoost = bd.score - bd.directScore,
                    favoritePrior = favoritePrior,
                    dislikePrior = dislikePrior,
                    isFavorite = isFavorite,
                    isDisliked = isDisliked,
                    daysSince = daysSinceFrom(dec.signal.lastPlayedAt),
                    directScore = bd.directScore,
                    score = bd.score,
                    positiveWeight = bd.positiveWeight,
                    negativeWeight = bd.negativeWeight,
                    effectivePositive = bd.effectivePositive,
                    effectiveNegative = bd.effectiveNegative,
                    isActive = dec.isActive || isFavorite || isDisliked,
                    isMixed = dec.isMixed,
                    isShortListened = dec.isShortListened,
                    isHardBlocked = excludedFromUpNext,
                    inTopPositive30 = dec.inTopPositive30,
                    inTopNegative30 = dec.inTopNegative30,
                    inTop30 = dec.inTop30,
                    encounters = dec.signal.encounters,
                    earlyCount = dec.signal.earlyCount,
                    partialCount = dec.signal.partialCount,
                    listenCount = dec.signal.listenCount,
                    strongCount = dec.signal.strongCount,
                    queueRemovals = dec.signal.queueRemovals,
                    avgAllFraction = dec.signal.avgAllFraction,
                    positiveEvidence = dec.signal.positiveEvidence,
                    similarityContributors = dec.signal.similarityContributors,
                    outboundImpacts = outboundSimilarityIndex[fn].orEmpty(),
                    isSuspicious = dec.isSuspicious,
                    suspiciousReason = dec.suspiciousReason,
                )
            } else if (isFavorite || isDisliked) {
                val favoritePrior = if (isFavorite) TasteEngine.MANUAL_FAVORITE_PRIOR_BASE else 0f
                val dislikePrior = if (isDisliked) TasteEngine.MANUAL_DISLIKE_PRIOR_BASE else 0f
                val direct = favoritePrior - dislikePrior
                SignalRowData(
                    song = song,
                    plays = 0,
                    avgFraction = 0f,
                    xScore = 0f,
                    similarityBoost = 0f,
                    favoritePrior = favoritePrior,
                    dislikePrior = dislikePrior,
                    isFavorite = isFavorite,
                    isDisliked = isDisliked,
                    daysSince = null,
                    directScore = direct,
                    score = direct,
                    positiveWeight = favoritePrior,
                    negativeWeight = dislikePrior,
                    effectivePositive = favoritePrior,
                    effectiveNegative = dislikePrior,
                    isActive = true,
                    isMixed = false,
                    isShortListened = false,
                    isHardBlocked = excludedFromUpNext,
                    inTopPositive30 = false,
                    inTopNegative30 = false,
                    inTop30 = false,
                    encounters = 0,
                    avgAllFraction = 0f,
                    outboundImpacts = outboundSimilarityIndex[fn].orEmpty(),
                )
            } else {
                val st = stats[song.filename]
                val plays = st?.plays ?: 0
                val avg = st?.avgFraction ?: 0f
                val direct = signedDirectScore(plays, avg)
                val active = abs(direct) >= 0.05f
                SignalRowData(
                    song = song,
                    plays = plays,
                    avgFraction = avg,
                    xScore = 0f,
                    similarityBoost = 0f,
                    favoritePrior = 0f,
                    dislikePrior = 0f,
                    isFavorite = false,
                    isDisliked = false,
                    daysSince = null,
                    directScore = direct,
                    score = direct,
                    positiveWeight = if (direct > 0f) direct else 0f,
                    negativeWeight = if (direct < 0f) -direct else 0f,
                    effectivePositive = if (direct > 0f) direct else 0f,
                    effectiveNegative = if (direct < 0f) -direct else 0f,
                    isActive = active,
                    isMixed = false,
                    isShortListened = false,
                    isHardBlocked = fn in upNextExcludedFilenames,
                    inTopPositive30 = false,
                    inTopNegative30 = false,
                    inTop30 = false,
                    encounters = plays,
                    avgAllFraction = avg,
                    outboundImpacts = outboundSimilarityIndex[fn].orEmpty(),
                )
            }
        }
    }
    val activeCount = remember(rows) { rows.count { it.isActive } }
    val totalCount = rows.size

    // Push #63: strict positive/negative separation. "Top Positive" hides
    // negative-scoring songs entirely (not just sorts them to the bottom).
    // "All Embedded" still shows everything regardless of sign.
    // Filters on `score` (directScore + similarityBoost) — the same overall
    // value shown in the badge/color and used everywhere else (ranking,
    // Up Next exclusion via overallScoresByFilename()). Filtering on
    // directScore alone let a song with negative directScore but a strong
    // positive similarity halo show up in "Top Negative" displaying a
    // positive score, and let disliked songs' propagated neighbors (which
    // only ever move via similarity, never directScore) go permanently
    // invisible in "Top Negative" no matter how negative their score got.
    val filteredRows = when {
        filterAll -> rows
        sortDescending -> rows.filter { it.isActive && it.score > 0f }
        else -> rows.filter { it.isActive && it.score < 0f }
    }
    val sortedRows = remember(filteredRows, sortDescending, filterAll) {
        if (filterAll) {
            filteredRows.sortedByDescending { if (sortDescending) it.score else -it.score }
        } else {
            filteredRows.sortedByDescending { if (sortDescending) it.score else -it.score }
        }
    }
    var visibleCount by remember(filterAll, sortDescending) { mutableStateOf(DEFAULT_LIMIT) }
    val visibleRows = sortedRows.take(visibleCount)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
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
                        text = "Taste Signal",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = if (onOpenActivityLog != null) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = onOpenActivityLog,
                            )
                        } else Modifier,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            // Headline — Up Next exclusion counts match Negative guard slider.
            item {
                val guardPct = (tuning.negativeStrength * 100).toInt()
                val blockedClause = buildString {
                    if (upNextHardBlockedCount > 0) {
                        append(" · Up Next excludes")
                        append(" $upNextHardBlockedCount negative")
                        if (upNextHardBlockedCount == 1) append(" song") else append(" songs")
                        append(" at $guardPct% Negative guard")
                    }
                    if (dislikedSet.isNotEmpty()) {
                        append(" · ${dislikedSet.size} currently marked disliked")
                    }
                }
                Text(
                    text = "$activeCount active signals across $totalCount embedded songs$blockedClause. " +
                        "Tap any row to start playback from that order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            // 3 tuning knobs — compact one-line rows since push #39.
            item {
                Text(
                    text = "Recommendation Tuning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                TuningRow(
                    label = "Adventurous",
                    value = tuning.adventurous,
                    // Push #78: tooltip text copied verbatim from the
                    // Capacitor build (`app-taste-ui.js:390`) so the user
                    // gets the same explanation they saw in the working
                    // baseline. Surfaced via the info ⓘ icon in TuningRow.
                    description = "Higher = more diverse Up Next picks (spread across your library). Lower = stick closer to what you just played. Internally drives MMR's diversity weight (engine maps adventurous → 1−lambda so the label matches the math).",
                    onChange = onAdventurousChange,
                    onReset = onAdventurousReset,
                )
            }
            item {
                TuningRow(
                    label = "Taste influence",
                    value = tuning.tasteInfluence,
                    description = "Controls overall taste score versus CLAP audio similarity inside each positive, neutral, or negative candidate tier. 30% taste means 70% audio similarity.",
                    onChange = onTasteInfluenceChange,
                    onReset = onTasteInfluenceReset,
                    valueRange = 0f..TasteEngine.MAX_TASTE_INFLUENCE,
                    steps = 9,
                )
            }
            item {
                val latest = sessionBlendIntervals.firstOrNull()
                val autoCurrent = (latest?.currentPercent ?: 0) / 100f
                val autoSession = (latest?.sessionPercent ?: 0) / 100f
                BlendTuningRow(
                    tuning = tuning,
                    effectiveCurrent = autoCurrent,
                    effectiveSession = autoSession,
                    onManualChange = onManualBlendChange,
                    onAutoReset = onBlendAutoReset,
                )
            }
            item {
                val guard = tuning.negativeStrength.coerceIn(0f, 1f)
                val threshold = if (guard > 0f) 0.25f / guard else null
                TuningRow(
                    label = "Negative guard",
                    value = tuning.negativeStrength,
                    description = if (threshold == null) {
                        "Learned hard blocking is off. Negative songs remain fallback candidates behind positive and neutral songs."
                    } else {
                        "At ${(guard * 100).toInt()}%, overall scores at or below -${"%.2f".format(threshold)} are blocked. Examples: 100% blocks -0.25, 50% blocks -0.50, and 25% blocks -1.00."
                    },
                    onChange = onNegativeStrengthChange,
                    onReset = onNegativeStrengthReset,
                )
            }

            // Engine snapshot (collapsible, 4 cards)
            item {
                CollapsibleHeader(
                    label = "Engine Snapshot",
                    expanded = snapshotExpanded,
                    onToggle = { snapshotExpanded = !snapshotExpanded },
                )
            }
            if (snapshotExpanded) {
                item { EngineSnapshotGrid(engineSnapshot) }
            }

            item {
                CollapsibleHeader(
                    label = "Session blend change",
                    expanded = sessionBlendExpanded,
                    onToggle = { sessionBlendExpanded = !sessionBlendExpanded },
                )
            }
            if (sessionBlendExpanded) {
                if (sessionBlendIntervals.isEmpty()) {
                    item {
                        Text(
                            text = "No AI queue blend has been applied in this app session yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    val text = buildSessionBlendCopyText(sessionBlendIntervals)
                                    copyToClipboard(ctx, "IsaiVazhi Session Blend Change", text)
                                },
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy all blend stats")
                            }
                        }
                    }
                    items(
                        sessionBlendIntervals.take(20),
                        key = { "blend_${it.startedAt}" },
                    ) { interval ->
                        SessionBlendRow(interval)
                    }
                }
            }

            item {
                CollapsibleHeader(
                    label = "Last 30 Playback Signal Updates (${timelineEvents.size})",
                    expanded = timelineExpanded,
                    onToggle = { timelineExpanded = !timelineExpanded },
                )
            }
            if (timelineExpanded) {
                item {
                    Text(
                        text = "Playback results and explicit taste actions. The badge describes what happened; " +
                            "the score line shows the recommendation evidence that actually changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                if (timelineEvents.isEmpty()) {
                    item {
                        Text(
                            text = "No recent playback signals yet. After songs are skipped or completed, each change will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    // Push #72: only render the first [timelineVisibleCount]
                    // events. Load More bumps by 10. Troubleshooting copy
                    // always includes every retained event and every field.
                    val timelineVisible = timelineEvents.take(timelineVisibleCount)
                    val timelineRemaining = (timelineEvents.size - timelineVisible.size).coerceAtLeast(0)
                    items(timelineVisible, key = { "ev_${it.timestamp}_${it.filename}" }) { ev ->
                        TimelineEventRow(ev)
                    }
                    if (timelineRemaining > 0) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                TextButton(onClick = {
                                    timelineVisibleCount = (timelineVisibleCount + 10).coerceAtMost(timelineEvents.size)
                                }) {
                                    val nextBatch = minOf(10, timelineRemaining)
                                    Text("Show $nextBatch more (${timelineRemaining} remaining)")
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    val text = buildTimelineCopyText(timelineEvents)
                                    onCopyTimelineText(text)
                                    copyToClipboard(ctx, "IsaiVazhi Last ${timelineEvents.size} Full Signals", text)
                                },
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy all ${timelineEvents.size} full signals")
                            }
                        }
                    }
                }
            }

            // Reset Engine action with confirm dialog
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    TextButton(onClick = { showResetConfirm = true }) {
                        Text("Reset Engine", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Filter + sort toggles. Push #63: counters now reflect strict
            // positive/negative split when in "Active Only" mode.
            item {
                val positiveActive = decoratedSignals.positiveActiveCount
                val negativeActive = decoratedSignals.negativeActiveCount
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip(
                        active = !filterAll,
                        label = "Active Only ($activeCount)",
                        onClick = { filterAll = false },
                    )
                    Chip(
                        active = filterAll,
                        label = "All Embedded ($totalCount)",
                        onClick = { filterAll = true },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip(
                        active = sortDescending,
                        label = "Top Positive ($positiveActive)",
                        onClick = { sortDescending = true },
                    )
                    Chip(
                        active = !sortDescending,
                        label = "Top Negative ($negativeActive)",
                        onClick = { sortDescending = false },
                    )
                }
            }

            // Signal rows
            item {
                Text(
                    text = "Library Signals (${sortedRows.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (sortedRows.isEmpty()) {
                item {
                    Text(
                        text = "No songs match. Play a few songs through or skip a few — the engine learns from each transition.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            items(visibleRows, key = { "row_${it.song.id}" }) { row ->
                SignalRowView(
                    row = row,
                    songsByFilename = songsByFilename,
                    onPlay = {
                        val idx = sortedRows.indexOfFirst { it.song.id == row.song.id }.coerceAtLeast(0)
                        onPlayOrderedList(sortedRows.map { it.song }, idx)
                    },
                    onResetSong = onResetSongStats,
                )
            }
            if (sortedRows.size > visibleCount) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        TextButton(onClick = { visibleCount += DEFAULT_LIMIT }) {
                            Text("Show ${DEFAULT_LIMIT.coerceAtMost(sortedRows.size - visibleCount)} more (${sortedRows.size - visibleCount} remaining)")
                        }
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset engine?") },
            text = {
                Column {
                    Text("Clears: Up Next, playback history, avoidance, session logs, similarity boost, and saved playback state.")
                    Spacer(Modifier.height(8.dp))
                    Text("Keeps: song files, embeddings, playlists, and your liked and disliked songs.")
                    Spacer(Modifier.height(8.dp))
                    Text("Playback will stop. This cannot be undone.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showResetConfirm = false; onResetEngineConfirmed() }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

data class EngineSnapshot(
    val blendCurrent: String,        // e.g. "60%"
    val blendSession: String,
    val blendProfile: String,
    val blendMode: String,           // e.g. "Strong session blend"
    val lastRefreshLabel: String? = null,
    val queueSize: Int,
    val queueMode: String,           // "AI" / "Shuffle"
    val embeddingsCovered: Int,
    val embeddingsTotal: Int,
    val embeddingsPercentage: String,
    val nowPlayingTitle: String,
    val nowPlayingArtist: String,
)

/**
 * Push #63: enriched row data — carries per-row decoration so the chip
 * layer doesn't have to re-look-up the DecoratedRow on every render.
 */
private data class SignalRowData(
    val song: Song,
    val plays: Int,
    val avgFraction: Float,
    val xScore: Float,
    val similarityBoost: Float,
    val favoritePrior: Float,
    val dislikePrior: Float,
    val isFavorite: Boolean,
    val isDisliked: Boolean,
    val daysSince: Int?,
    val directScore: Float,
    val score: Float,
    val positiveWeight: Float,
    val negativeWeight: Float,
    val effectivePositive: Float,
    val effectiveNegative: Float,
    val isActive: Boolean,
    val isMixed: Boolean,
    val isShortListened: Boolean,
    val isHardBlocked: Boolean,
    val inTopPositive30: Boolean,
    val inTopNegative30: Boolean,
    val inTop30: Boolean,
    val encounters: Int = 0,
    val earlyCount: Int = 0,
    val partialCount: Int = 0,
    val listenCount: Int = 0,
    val strongCount: Int = 0,
    val queueRemovals: Int = 0,
    val avgAllFraction: Float = 0f,
    val positiveEvidence: Float = 0f,
    val similarityContributors: List<TasteEngine.SimilarityContributor> = emptyList(),
    val outboundImpacts: List<TasteEngine.OutboundSimilarityImpact> = emptyList(),
    val isSuspicious: Boolean = false,
    val suspiciousReason: String = "",
)

private fun daysSinceFrom(lastPlayedAt: Long): Int? {
    if (lastPlayedAt <= 0L) return null
    val ms = System.currentTimeMillis() - lastPlayedAt
    if (ms <= 0L) return 0
    return (ms / TasteEngine.PROFILE_DAY_MS).toInt()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BlendTuningRow(
    tuning: TasteEngine.Tuning,
    effectiveCurrent: Float,
    effectiveSession: Float,
    onManualChange: (Float, Float) -> Unit,
    onAutoReset: () -> Unit,
) {
    val sourceCurrent = if (tuning.manualBlend) tuning.manualCurrentWeight else effectiveCurrent
    val sourceSession = if (tuning.manualBlend) tuning.manualSessionWeight else effectiveSession
    val baseRange = sourceCurrent.coerceIn(0f, 1f)..
        (sourceCurrent + sourceSession).coerceIn(sourceCurrent, 1f)
    var drag by remember(baseRange.start, baseRange.endInclusive) {
        mutableStateOf<ClosedFloatingPointRange<Float>?>(null)
    }
    val displayed = drag ?: baseRange
    val current = displayed.start
    val session = (displayed.endInclusive - displayed.start).coerceAtLeast(0f)
    val profile = (1f - displayed.endInclusive).coerceAtLeast(0f)
    val currentPct = (current * 100).toInt()
    val sessionPct = (session * 100).toInt()
    val profilePct = (profile * 100).toInt()
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(if (tuning.manualBlend) "Blend (Manual)" else "Blend (Auto)") },
            text = {
                Text(
                    "Current $currentPct%, Session $sessionPct%, Long-term $profilePct%.\n\n" +
                        "Current = songs similar to the song you explicitly started.\n" +
                        "Session = songs similar to the positively-heard songs from this app run.\n" +
                        "Long-term = songs similar to your saved taste profile built from your strongest long-term positives.\n\n" +
                        "Auto moves these weights as session evidence grows. Dragging either divider switches to Manual.\n\n" +
                        "Manual uses these as target weights. If one source is unavailable, its share is reassigned across the sources that do exist. " +
                        "Example: if you set 20/60/20 and reopen later with no session listens yet, Session becomes 0 and the live blend becomes 50/0/50."
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("Got it") }
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Blend",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(84.dp),
            maxLines = 1,
        )
        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "About Blend",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        RangeSlider(
            value = displayed,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { range ->
                    onManualChange(
                        range.start,
                        (range.endInclusive - range.start).coerceAtLeast(0f),
                    )
                }
                drag = null
            },
            valueRange = 0f..1f,
            steps = 19,
            colors = androidx.compose.material3.SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                activeTickColor = MaterialTheme.colorScheme.primary,
                inactiveTickColor = MaterialTheme.colorScheme.surfaceVariant,
                thumbColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .weight(1f)
                .height(16.dp),
        )
        Text(
            text = "$currentPct/$sessionPct/$profilePct",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(56.dp).padding(start = 4.dp),
            maxLines = 1,
        )
        IconButton(
            onClick = onAutoReset,
            enabled = tuning.manualBlend,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Reset Blend to Auto",
                tint = if (tuning.manualBlend) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TuningRow(
    label: String,
    value: Float,
    description: String,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 9,
) {
    var drag by remember(value) { mutableStateOf<Float?>(null) }
    val displayed = drag ?: value
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // Push #78: tooltip dialog (Capacitor parity). Tap the ⓘ icon to see
    // what this slider does without leaving the page.
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(label) },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("Got it") }
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(96.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "About $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Slider(
            value = displayed,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { onChange(it) }
                drag = null
            },
            valueRange = valueRange,
            steps = steps,
            interactionSource = interactionSource,
            modifier = Modifier
                .weight(1f)
                .height(16.dp),
            thumb = {
                androidx.compose.material3.SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 14.dp),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
            track = { state ->
                androidx.compose.material3.SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(2.dp),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            },
        )
        Text(
            text = "${(displayed * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(38.dp).padding(start = 8.dp),
        )
        IconButton(
            onClick = {
                android.util.Log.i("TasteScreen", "reset clicked: label=$label")
                onReset()
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Reset $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CollapsibleHeader(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EngineSnapshotGrid(snap: EngineSnapshot) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            EngineCard(
                modifier = Modifier.weight(1f),
                key = "Current Blend",
                value = "${snap.blendCurrent} / ${snap.blendSession} / ${snap.blendProfile}",
                meta = snap.lastRefreshLabel ?: snap.blendMode,
            )
            EngineCard(
                modifier = Modifier.weight(1f),
                key = "Up Next",
                value = "${snap.queueSize} songs",
                meta = snap.queueMode,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            EngineCard(
                modifier = Modifier.weight(1f),
                key = "Library AI",
                value = snap.embeddingsPercentage,
                meta = "${snap.embeddingsCovered}/${snap.embeddingsTotal} embedded",
            )
            EngineCard(
                modifier = Modifier.weight(1f),
                key = "Now Playing",
                value = snap.nowPlayingTitle.ifBlank { "Nothing active" },
                meta = snap.nowPlayingArtist.ifBlank { "Start a song to see live blend" },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun EngineCard(modifier: Modifier = Modifier, key: String, value: String, meta: String) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 12.dp, horizontal = 10.dp),
    ) {
        Text(text = key, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(text = meta, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Chip(active: Boolean, label: String, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

private val ScorePositiveColor = Color(0xFF34D399)
private val ScoreNegativeColor = Color(0xFFF87171)
private val ChipNeutralColor = Color(0xFF9CA3AF)

/**
 * Push #63: single chip pill — colored to match its semantics. Used to
 * surface Favorite / Disliked / Mixed / X / Similarity / Rec-blocked /
 * Short-listened / Top ±30 / Neutral states next to each signal row.
 */
@Composable
private fun StatusChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.20f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SignalRowView(
    row: SignalRowData,
    songsByFilename: Map<String, Song>,
    onPlay: () -> Unit,
    onResetSong: (filename: String) -> Unit,
) {
    val score = row.score
    val scoreColor = when {
        score > 0.05f -> ScorePositiveColor
        score < -0.05f -> ScoreNegativeColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scoreText = (if (score >= 0f) "+" else "") + "%.2f".format(score)
    val topImpacts = remember(row.outboundImpacts) {
        row.outboundImpacts
            .sortedByDescending { abs(it.amount) }
            .take(3)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ArtThumbnail(filePath = row.song.filePath, size = 40.dp, cornerRadius = 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = row.song.title.ifBlank { row.song.filename },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(scoreColor.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(text = scoreText, style = MaterialTheme.typography.labelSmall, color = scoreColor)
                }
            }
            if (row.song.artist.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (row.isFavorite) StatusChip("Favorite", ScorePositiveColor)
                if (row.isDisliked) StatusChip("Dislike", ScoreNegativeColor)
                if (abs(row.similarityBoost) > 0.001f) {
                    val sign = if (row.similarityBoost > 0f) "+" else ""
                    val col = if (row.similarityBoost > 0f) ScorePositiveColor else ScoreNegativeColor
                    StatusChip("Similarity $sign${"%.2f".format(row.similarityBoost)}", col)
                }
                if (row.isSuspicious) StatusChip("Review", Color(0xFFFBBF24))
            }
            Spacer(Modifier.height(4.dp))
            OutboundSimilaritySummary(
                impacts = topImpacts,
                songsByFilename = songsByFilename,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play song",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = { onResetSong(row.song.filename) }) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Reset signal",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun OutboundSimilaritySummary(
    impacts: List<TasteEngine.OutboundSimilarityImpact>,
    songsByFilename: Map<String, Song>,
) {
    if (impacts.isEmpty()) {
        Text(
            text = "No stored neighbor score changes yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = "Top influenced neighbors",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        impacts.forEach { impact ->
            val song = songsByFilename[impact.targetFilename]
            val title = song?.title?.takeIf { it.isNotBlank() } ?: impact.targetFilename
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = fmt(impact.amount),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (impact.amount >= 0f) ScorePositiveColor else ScoreNegativeColor,
                )
            }
        }
    }
}

internal enum class TimelineSignalTone { POSITIVE, NEGATIVE, NEUTRAL }

internal fun timelineSignalTone(ev: SignalTimelineEngine.Event): TimelineSignalTone =
    when (ev.kind) {
        TasteEventKind.PLAYBACK -> when {
            ev.avoidanceDelta > 0.001f -> TimelineSignalTone.NEGATIVE
            ev.contribution > 0.001f -> TimelineSignalTone.POSITIVE
            else -> TimelineSignalTone.NEUTRAL
        }
        TasteEventKind.FAVORITE,
        TasteEventKind.UNDISLIKE -> TimelineSignalTone.POSITIVE
        TasteEventKind.DISLIKE,
        TasteEventKind.QUEUE_REMOVE -> TimelineSignalTone.NEGATIVE
        TasteEventKind.UNFAVORITE -> TimelineSignalTone.NEUTRAL
    }

internal data class TimelineEventPresentation(
    val effectText: String,
    val latestPlaybackScoreText: String,
    val similarityScoreText: String,
    val avoidanceText: String,
    val totalScoreText: String,
    val historyLine: String,
    val showFavoriteTag: Boolean,
    val showDislikeTag: Boolean,
)

internal fun buildTimelineEventPresentation(ev: SignalTimelineEngine.Event): TimelineEventPresentation {
    val latestPlaybackDelta = ev.tasteAfter.direct - ev.tasteBefore.direct
    val effectText = if (ev.kind == TasteEventKind.PLAYBACK) {
        "Evidence ${fmt(ev.contribution)}"
    } else {
        "Song score change ${fmt(latestPlaybackDelta)}"
    }
    return TimelineEventPresentation(
        effectText = effectText,
        latestPlaybackScoreText = "Latest playback score ${fmt(ev.tasteAfter.direct)} (Δ ${fmt(latestPlaybackDelta)})",
        similarityScoreText = "Similarity score ${fmt(ev.tasteAfter.similarity)}",
        avoidanceText = "Avoidance ${fmt(-ev.tasteAfter.avoidance)}",
        totalScoreText = "Total score ${fmt(ev.tasteAfter.score)}",
        historyLine =
            "Played ${ev.tasteAfter.encounters} · Early ${ev.tasteAfter.early} · " +
                "Partial ${ev.tasteAfter.partial} · Listen ${ev.tasteAfter.listens} · " +
                "Strong ${ev.tasteAfter.strong} · Avg heard ${(ev.tasteAfter.avgAllFraction * 100).toInt()}%",
        showFavoriteTag = ev.tasteAfter.favorite > 0.001f,
        showDislikeTag = ev.tasteAfter.dislike > 0.001f,
    )
}

@Suppress("unused")
private fun buildLegacyDetailLine(row: SignalRowData): String {
    val parts = mutableListOf<String>()
    parts += "${row.plays} starts"
    parts += "avg ${(row.avgFraction * 100).toInt()}%"
    if (abs(row.directScore) > 0.001f) {
        val s = if (row.directScore >= 0f) "+" else ""
        parts += "direct $s${"%.2f".format(row.directScore)}"
    }
    if (abs(row.similarityBoost) > 0.001f) {
        val s = if (row.similarityBoost >= 0f) "+" else ""
        parts += "similarity $s${"%.2f".format(row.similarityBoost)}"
    }
    if (row.favoritePrior > 0.001f) parts += "fav +${"%.2f".format(row.favoritePrior)}"
    if (row.dislikePrior > 0.001f) parts += "dislike −${"%.2f".format(row.dislikePrior)}"
    row.daysSince?.let { if (it > 0) parts += "${it}d ago" }
    return parts.joinToString(" · ")
}

@Composable
private fun SessionBlendRow(interval: SessionBlendInterval) {
    val formatter = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US) }
    val from = formatter.format(java.util.Date(interval.startedAt))
    val to = interval.endedAt?.let { formatter.format(java.util.Date(it)) } ?: "now"
    Text(
        text = "${interval.currentPercent}/${interval.sessionPercent}/${interval.profilePercent}  •  ${interval.reason}  •  $from-$to",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TimelineEventRow(ev: SignalTimelineEngine.Event) {
    val whenStr = remember(ev.timestamp) {
        java.text.SimpleDateFormat("MMM d • HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(ev.timestamp))
    }
    val presentation = buildTimelineEventPresentation(ev)
    val badge = when (ev.kind) {
        TasteEventKind.PLAYBACK -> ev.outcome.name
        TasteEventKind.FAVORITE -> "FAVORITED"
        TasteEventKind.UNFAVORITE -> "UNFAVORITED"
        TasteEventKind.DISLIKE -> "DISLIKED"
        TasteEventKind.UNDISLIKE -> "UNDISLIKED"
        TasteEventKind.QUEUE_REMOVE -> "REMOVED"
    }
    val badgeColor = when (timelineSignalTone(ev)) {
        TimelineSignalTone.POSITIVE -> ScorePositiveColor
        TimelineSignalTone.NEGATIVE -> ScoreNegativeColor
        TimelineSignalTone.NEUTRAL -> ChipNeutralColor
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = ev.title.ifBlank { ev.filename },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor.copy(alpha = 0.20f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(badge, style = MaterialTheme.typography.labelSmall, color = badgeColor)
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (presentation.showFavoriteTag) StatusChip("Fav", ScorePositiveColor)
            if (presentation.showDislikeTag) StatusChip("Dislike", ScoreNegativeColor)
        }
        val eventDetail = if (ev.kind == TasteEventKind.PLAYBACK) {
            "$whenStr • ${(ev.fraction * 100).toInt()}% • ${ev.endReason.label}"
        } else {
            "$whenStr • ${ev.kind.name.lowercase().replace('_', ' ')}"
        }
        Text(
            text = eventDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        TimelineMetricLine(text = presentation.effectText, color = MaterialTheme.colorScheme.onSurface)
        TimelineMetricLine(
            text = presentation.latestPlaybackScoreText,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TimelineMetricLine(
            text = presentation.similarityScoreText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TimelineMetricLine(
            text = presentation.avoidanceText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TimelineMetricLine(
            text = presentation.totalScoreText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = presentation.historyLine,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineMetricLine(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun TimelineDetail(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Suppress("unused")
@Composable
private fun LegacyTimelineEventRow(ev: SignalTimelineEngine.Event) {
    val whenStr = remember(ev.timestamp) {
        java.text.SimpleDateFormat("MMM d • HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(ev.timestamp))
    }
    val badgeColor = if (ev.classification == SignalTimelineEngine.Classification.SKIP)
        ScoreNegativeColor else ScorePositiveColor
    val badgeText = if (ev.classification == SignalTimelineEngine.Classification.SKIP) "SKIP" else "LISTEN"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = ev.title.ifBlank { ev.filename },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor.copy(alpha = 0.20f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(text = badgeText, style = MaterialTheme.typography.labelSmall, color = badgeColor)
            }
        }
        Text(
            text = "$whenStr • listened ${(ev.fraction * 100).toInt()}% • source ${ev.source.replace('_', ' ')}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LineKV("Direct play", "${fmt(ev.tasteBefore.direct)} → ${fmt(ev.tasteAfter.direct)} (Δ ${fmt(ev.tasteAfter.direct - ev.tasteBefore.direct)})")
        LineKV("Similarity Δ", fmt(ev.tasteAfter.similarity))
        LineKV("Total score", "${fmt(ev.tasteBefore.score)} → ${fmt(ev.tasteAfter.score)}")
        // Push #63: session-level rolling counters from SessionEngine.
        LineKV(
            "Session counts",
            "enc ${ev.sessionEncountersBefore} → ${ev.sessionEncountersAfter}  " +
                "skips ${ev.sessionSkipsBefore} → ${ev.sessionSkipsAfter}  " +
                "pos ${ev.sessionPositivesBefore} → ${ev.sessionPositivesAfter}",
        )
        LineKV("Library avg", "${(ev.libraryAvgBefore * 100).toInt()}% → ${(ev.libraryAvgAfter * 100).toInt()}%")
        LineKV("Library counts", "starts ${ev.tasteBefore.plays} → ${ev.tasteAfter.plays}  skips ${ev.tasteBefore.skips} → ${ev.tasteAfter.skips}")
        LineKV("X-score", "${"%.1f".format(ev.xScoreBefore)} → ${"%.1f".format(ev.xScoreAfter)}")
    }
}

@Composable
private fun LineKV(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun fmt(v: Float): String = (if (v >= 0f) "+" else "") + "%.2f".format(v)

private fun buildSessionBlendCopyText(intervals: List<SessionBlendInterval>): String =
    intervals.joinToString("\n") { interval ->
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val from = formatter.format(java.util.Date(interval.startedAt))
        val to = interval.endedAt?.let { formatter.format(java.util.Date(it)) } ?: "now"
        "${interval.currentPercent}/${interval.sessionPercent}/${interval.profilePercent} | ${interval.reason} | $from -> $to | ${interval.playbackResults} playback results"
    }

private fun buildTimelineCopyText(events: List<SignalTimelineEngine.Event>): String =
    events.mapIndexed { index, event ->
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            java.util.Locale.US,
        ).format(java.util.Date(event.timestamp))
        val label = if (event.kind == TasteEventKind.PLAYBACK) event.outcome.name
            else event.kind.name
        val delta = event.tasteAfter.direct - event.tasteBefore.direct
        buildString {
            appendLine("${index + 1}. ${event.title.ifBlank { event.filename }} | $label")
            appendLine("Time: $timestamp")
            if (event.kind == TasteEventKind.PLAYBACK) {
                appendLine("Heard: ${(event.fraction * 100).toInt()}% | Ended by: ${event.endReason.label}")
            }
            if (event.kind == TasteEventKind.PLAYBACK) {
                appendLine("Evidence: ${fmt(event.contribution)}")
            } else {
                appendLine("Song score change: ${fmt(delta)}")
            }
            appendLine(
                "Direct score: ${fmt(event.tasteBefore.direct)} -> ${fmt(event.tasteAfter.direct)} " +
                    "(${fmt(event.tasteAfter.direct - event.tasteBefore.direct)})"
            )
            appendLine(
                "Similarity: ${fmt(event.tasteBefore.similarity)} -> ${fmt(event.tasteAfter.similarity)}"
            )
            appendLine("Total score: ${fmt(event.tasteBefore.score)} -> ${fmt(event.tasteAfter.score)}")
            if (abs(event.avoidanceDelta) > 0.001f) {
                val avoidanceLabel = if (event.avoidanceDelta > 0f) {
                    "Avoidance"
                } else {
                    "Avoidance repaired"
                }
                appendLine("$avoidanceLabel: ${fmt(-event.avoidanceDelta)}")
            }
            if (event.relatedSongsChanged > 0) {
                appendLine("${event.relatedSongsChanged} similar songs adjusted ${fmt(event.relatedScoreDelta)}")
            }
            if (event.tasteAfter.detailVersion >= 2) {
                appendLine(
                    "Song history before: Played ${event.tasteBefore.encounters} | " +
                    "Early ${event.tasteBefore.early} | Partial ${event.tasteBefore.partial} | " +
                    "Listen ${event.tasteBefore.listens} | Strong ${event.tasteBefore.strong} | " +
                    "Avg heard ${(event.tasteBefore.avgAllFraction * 100).toInt()}%"
            )
            appendLine(
                "Song history after: Played ${event.tasteAfter.encounters} | " +
                    "Early ${event.tasteAfter.early} | Partial ${event.tasteAfter.partial} | " +
                    "Listen ${event.tasteAfter.listens} | Strong ${event.tasteAfter.strong} | " +
                    "Avg heard ${(event.tasteAfter.avgAllFraction * 100).toInt()}%"
            )
            appendLine(
                "Components before: Own ${fmt(event.tasteBefore.ownPositive)} | " +
                    "Favorite ${fmt(event.tasteBefore.favorite)} | " +
                    "Similar ${fmt(event.tasteBefore.similarity)} | " +
                    "Avoidance ${fmt(-event.tasteBefore.avoidance)} | " +
                    "Dislike ${fmt(-event.tasteBefore.dislike)} | " +
                    "Total ${fmt(event.tasteBefore.score)}"
            )
            appendLine(
                "Components after: Own ${fmt(event.tasteAfter.ownPositive)} | " +
                    "Favorite ${fmt(event.tasteAfter.favorite)} | " +
                    "Similar ${fmt(event.tasteAfter.similarity)} | " +
                    "Avoidance ${fmt(-event.tasteAfter.avoidance)} | " +
                    "Dislike ${fmt(-event.tasteAfter.dislike)} | " +
                    "Total ${fmt(event.tasteAfter.score)}"
            )
            appendLine(
                "Queue removals: ${event.tasteBefore.queueRemovals} -> ${event.tasteAfter.queueRemovals}"
            )
            } else {
                appendLine(
                    "Legacy capture: full outcome counters were not stored for this older event."
                )
                appendLine(
                    "Available history: Qualified listens ${event.tasteBefore.plays} -> " +
                        "${event.tasteAfter.plays} | Average " +
                        "${(event.tasteBefore.avgFraction * 100).toInt()}% -> " +
                        "${(event.tasteAfter.avgFraction * 100).toInt()}%"
                )
            }
            appendLine(
                "Session results: ${event.sessionEncountersBefore} -> ${event.sessionEncountersAfter} | " +
                    "Positive ${event.sessionPositivesBefore} -> ${event.sessionPositivesAfter}"
            )
            appendLine("Diagnostic source: ${event.source}")
            if (event.eventId.isNotBlank()) appendLine("Event ID: ${event.eventId}")
        }.trimEnd()
    }.joinToString("\n\n")

@Suppress("unused")
private fun buildLegacyTimelineCopyText(events: List<SignalTimelineEngine.Event>): String {
    return events.mapIndexed { idx, e ->
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp))
        val cls = if (e.classification == SignalTimelineEngine.Classification.SKIP) "skip" else "positive listen"
        buildString {
            appendLine("${idx + 1}. ${e.title.ifBlank { e.filename }} | ${e.artist.ifBlank { "-" }}")
            appendLine("Time: $ts")
            appendLine("Classification: $cls | Listened: ${(e.fraction * 100).toInt()}% | Source: ${e.source}")
            appendLine("Direct play effect: ${fmt(e.tasteBefore.direct)} -> ${fmt(e.tasteAfter.direct)} (Δ ${fmt(e.tasteAfter.direct - e.tasteBefore.direct)})")
            appendLine("Similarity delta effect: ${fmt(e.tasteAfter.similarity)}")
            appendLine("Total score: ${fmt(e.tasteBefore.score)} -> ${fmt(e.tasteAfter.score)}")
            appendLine("Session counts: enc ${e.sessionEncountersBefore} -> ${e.sessionEncountersAfter} | skips ${e.sessionSkipsBefore} -> ${e.sessionSkipsAfter} | pos ${e.sessionPositivesBefore} -> ${e.sessionPositivesAfter}")
            appendLine("Library counts: starts ${e.tasteBefore.plays} -> ${e.tasteAfter.plays} | skips ${e.tasteBefore.skips} -> ${e.tasteAfter.skips}")
            appendLine("Library avg: ${(e.libraryAvgBefore * 100).toInt()}% -> ${(e.libraryAvgAfter * 100).toInt()}%")
            append("X-score: ${"%.1f".format(e.xScoreBefore)} -> ${"%.1f".format(e.xScoreAfter)}")
        }
    }.joinToString("\n\n")
}

private fun copyToClipboard(ctx: android.content.Context, label: String, text: String) {
    val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText(label, text))
}
