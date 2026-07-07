package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.Song
import com.isaivazhi.app.engine.RecommendationBlendInfo
import com.isaivazhi.app.engine.RecommendationExplanation
import com.isaivazhi.app.engine.RecommendationInfluence
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Up Next queue view. Renders below the tab bar's "Up Next" title — no
 * duplicate header inside this composable. Single control row at the top
 * holds the AI/Shuffle toggle, track count, and Refresh button inline.
 *
 * Sections:
 *   Previously Played | Now Playing | Coming Up
 *
 * Per-row: tap to jump, long-press for the 3-dot menu, × button (upcoming
 * only) to remove from the queue.
 */
@Composable
fun UpNextScreen(
    queue: List<Song>,
    currentMediaId: String?,
    currentIndex: Int,
    aiMode: Boolean,
    /** When false, AI chip is disabled (e.g. no embeddings imported yet). */
    aiModeEnabled: Boolean = true,
    onToggleMode: (aiMode: Boolean) -> Unit,
    onJumpTo: (index: Int) -> Unit,
    onLongPress: (Song) -> Unit,
    onRemove: (index: Int) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean = true,
    refreshInProgress: Boolean = false,
    explanations: List<RecommendationExplanation> = emptyList(),
    blendInfo: RecommendationBlendInfo? = null,
    adventurous: Float = 0.4f,
    negativeGuard: Float = 0.5f,
    tasteInfluence: Float = 0.3f,
    playNextFilenames: Set<String> = emptySet(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val safeIndex = currentIndex.coerceAtLeast(0)
    // Queue truth: tracks before currentIndex (includes skip-ahead items not yet played).
    val previously = if (safeIndex > 0) {
        queue.subList(0, safeIndex.coerceAtMost(queue.size))
    } else {
        emptyList()
    }
    val current = queue.getOrNull(safeIndex)
    val upcoming = if (safeIndex + 1 < queue.size) queue.subList(safeIndex + 1, queue.size) else emptyList()
    val visiblePrev = previously.takeLast(10)
    val hiddenPrevCount = (previously.size - visiblePrev.size).coerceAtLeast(0)
    val queueExplanations = remember(queue, explanations) {
        alignQueueExplanations(queue, explanations)
    }
    val upcomingExplanations = remember(queueExplanations, safeIndex) {
        if (safeIndex + 1 >= queueExplanations.size) {
            emptyList()
        } else {
            queueExplanations.subList(safeIndex + 1, queueExplanations.size)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Single control row: AI/Shuffle chips + count + Refresh.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChip(
                label = "AI",
                icon = Icons.Filled.AutoAwesome,
                active = aiMode,
                enabled = aiModeEnabled,
                onClick = { if (aiModeEnabled) onToggleMode(true) },
            )
            Spacer(Modifier.width(6.dp))
            ModeChip(
                label = "Shuffle",
                icon = Icons.Filled.Shuffle,
                active = !aiMode,
                enabled = true,
                onClick = { onToggleMode(false) },
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${queue.size} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = onRefresh,
                enabled = refreshEnabled && !refreshInProgress,
            ) {
                if (refreshInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh upcoming queue with current mode",
                        tint = if (refreshEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (aiMode && blendInfo != null) {
            BlendSummary(
                blendInfo = blendInfo,
                adventurous = adventurous,
                negativeGuard = negativeGuard,
                tasteInfluence = tasteInfluence,
                filteredCount = explanations.maxOfOrNull { it.negativeFilteredCount } ?: 0,
                queueExplanations = upcomingExplanations.filterNotNull(),
            )
        }

        if (queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "No queue. Play a song from Songs, Albums, or Playlists to start one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val listState = rememberLazyListState()
            val view = LocalView.current
            // Stable duplicate-safe keys for upcoming rows. We key by
            // song identity + occurrence index within the upcoming
            // snapshot (e.g. "up_123_0", "up_123_1"). This stays stable
            // across drag swaps while still supporting repeated songs.
            val occurrenceBySongId = HashMap<Int, Int>()
            val upcomingKeys: List<String> = upcoming.map { s ->
                val occ = occurrenceBySongId[s.id] ?: 0
                occurrenceBySongId[s.id] = occ + 1
                "up_${s.id}_$occ"
            }
            // Drag-to-reorder for the Coming Up section only.
            // Resolve from/to keys to current queue indices by looking up
            // their key position in the latest `upcomingKeys` snapshot.
            val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
                val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
                val toKey = to.key as? String ?: return@rememberReorderableLazyListState
                if (!fromKey.startsWith("up_") || !toKey.startsWith("up_")) return@rememberReorderableLazyListState
                val fromLocal = upcomingKeys.indexOf(fromKey)
                val toLocal = upcomingKeys.indexOf(toKey)
                if (fromLocal < 0 || toLocal < 0) return@rememberReorderableLazyListState
                val fromQueueIdx = safeIndex + 1 + fromLocal
                val toQueueIdx = safeIndex + 1 + toLocal
                onMove(fromQueueIdx, toQueueIdx)
                runCatching {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                }
            }
            // Compute the LazyColumn item index that holds "Now Playing".
            // Section anatomy: [h_prev header] [visiblePrev rows...] [h_now] [now row] [h_next] [upcoming rows...]
            // If visiblePrev is empty, no "h_prev" header is added.
            val nowPlayingItemIndex = when {
                current == null -> 0
                visiblePrev.isEmpty() -> 1 // h_now is item 0; now-row is item 1
                else -> 1 + visiblePrev.size + 1 // h_prev + prev rows + h_now ; the row itself follows
            }
            // Scroll so the Now Playing row sits a few rows from the top.
            LaunchedEffect(safeIndex, queue.size) {
                val scrollTarget = (nowPlayingItemIndex - 1).coerceAtLeast(0)
                runCatching { listState.animateScrollToItem(scrollTarget) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                if (visiblePrev.isNotEmpty()) {
                    item("h_prev") {
                    SectionTitle(
                        if (hiddenPrevCount > 0)
                            "Previously Played (last ${visiblePrev.size} of ${previously.size})"
                        else "Previously Played"
                    )
                }
                // Key includes the queue index so a song that appears in the
                // queue more than once (legitimate when AI rec brings it back
                // or user replays) doesn't collide and crash the LazyColumn.
                itemsIndexed(
                    visiblePrev,
                    key = { i, s -> "prev_${(previously.size - visiblePrev.size) + i}_${s.id}" },
                ) { i, song ->
                    val realIndex = (previously.size - visiblePrev.size) + i
                    QueueRow(
                        index = realIndex + 1,
                        song = song,
                        role = QueueRole.Previous,
                        currentMediaId = currentMediaId,
                        onTap = { onJumpTo(realIndex) },
                        onLongPress = { onLongPress(song) },
                        onRemove = null,
                        explanation = queueExplanations.getOrNull(realIndex),
                    )
                }
            }
            if (current != null) {
                item("h_now") { SectionTitle("Now Playing") }
                item("now_${safeIndex}_${current.id}") {
                    QueueRow(
                        index = safeIndex + 1,
                        song = current,
                        role = QueueRole.Current,
                        currentMediaId = currentMediaId,
                        onTap = { onJumpTo(safeIndex) },
                        onLongPress = { onLongPress(current) },
                        onRemove = null,
                        explanation = queueExplanations.getOrNull(safeIndex),
                    )
                }
            }
            if (upcoming.isNotEmpty()) {
                item("h_next") { SectionTitle("Coming Up") }
                itemsIndexed(
                    upcoming,
                    // Identity + duplicate occurrence key stays stable while
                    // dragging and remains unique for repeated songs.
                    key = { i, _ -> upcomingKeys[i] },
                ) { i, song ->
                    val realIndex = safeIndex + 1 + i
                    val itemKey = upcomingKeys[i]
                    ReorderableItem(reorderableState, key = itemKey) { isDragging ->
                        // `Modifier.draggableHandle` is a scoped extension
                        // on this ReorderableCollectionItemScope — capture
                        // it as a plain Modifier so the inner dragHandle
                        // composable doesn't need access to that scope.
                        val handleModifier = Modifier.draggableHandle(
                            onDragStarted = {
                                runCatching {
                                    view.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.GESTURE_START
                                    )
                                }
                            },
                            onDragStopped = {
                                runCatching {
                                    view.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.GESTURE_END
                                    )
                                }
                            },
                        )
                        // Subtle lift effect while dragging so the user
                        // sees which row is being moved.
                        val elevation = if (isDragging) 6.dp else 0.dp
                        QueueRow(
                            index = realIndex + 1,
                            song = song,
                            role = QueueRole.Upcoming,
                            currentMediaId = currentMediaId,
                            onTap = { onJumpTo(realIndex) },
                            onLongPress = { onLongPress(song) },
                            onRemove = { onRemove(realIndex) },
                            explanation = queueExplanations.getOrNull(realIndex),
                            fallbackLabel = if (song.filename in playNextFilenames) "Play Next" else null,
                            isDragging = isDragging,
                            dragHandle = {
                                IconButton(
                                    onClick = {},
                                    modifier = handleModifier,
                                ) {
                                    Icon(
                                        Icons.Filled.DragIndicator,
                                        contentDescription = "Drag to reorder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                            modifier = Modifier.graphicsLayer {
                                shadowElevation = elevation.toPx()
                            },
                        )
                    }
                }
            }
            }
        }
    }
}

private enum class QueueRole { Previous, Current, Upcoming }

@Composable
private fun ModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
private fun SectionTitle(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private val CurrentInfluenceColor = Color(0xFFF59E0B)
private val SessionInfluenceColor = Color(0xFF0F9D8A)
private val ProfileInfluenceColor = Color(0xFF3B82F6)
private val DiversityInfluenceColor = Color(0xFF65A30D)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlendSummary(
    blendInfo: RecommendationBlendInfo,
    adventurous: Float,
    negativeGuard: Float,
    tasteInfluence: Float,
    filteredCount: Int,
    queueExplanations: List<RecommendationExplanation>,
) {
    val weights = blendInfo.weights
    val total = (weights.wCurrent + weights.wSession + weights.wProfile)
        .coerceAtLeast(0.0001f)
    val current = (weights.wCurrent / total * 100f).toInt()
    val session = (weights.wSession / total * 100f).toInt()
    val profile = (100 - current - session).coerceAtLeast(0)
    fun count(influence: RecommendationInfluence) =
        queueExplanations.count { it.dominantInfluence == influence }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(
            text = "AI blend • Current $current% • Session $session% • Long-term $profile%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            InfluenceLegend(CurrentInfluenceColor, count(RecommendationInfluence.CURRENT), "Current")
            InfluenceLegend(SessionInfluenceColor, count(RecommendationInfluence.SESSION), "Session")
            InfluenceLegend(ProfileInfluenceColor, count(RecommendationInfluence.PROFILE), "Long-term")
            InfluenceLegend(DiversityInfluenceColor, count(RecommendationInfluence.DIVERSITY), "Diverse")
            val other = count(RecommendationInfluence.NONE)
            if (other > 0) {
                InfluenceLegend(MaterialTheme.colorScheme.outline, other, "Other")
            }
        }
        Text(
            text = "Taste ${(tasteInfluence * 100).toInt()}% • Diversity ${(adventurous * 100).toInt()}% • Guard ${(negativeGuard * 100).toInt()}%" +
                if (filteredCount > 0) " • $filteredCount taste-blocked" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("unused")
@Composable
private fun LegacyBlendSummary(
    blendInfo: RecommendationBlendInfo,
    adventurous: Float,
    negativeGuard: Float,
    filteredCount: Int,
    queueExplanations: List<RecommendationExplanation>,
) {
    val weights = blendInfo.weights
    val total = (weights.wCurrent + weights.wSession + weights.wProfile)
        .coerceAtLeast(0.0001f)
    val current = (weights.wCurrent / total * 100f).toInt()
    val session = (weights.wSession / total * 100f).toInt()
    val profile = (100 - current - session).coerceAtLeast(0)
    val currentCount = queueExplanations.count {
        it.dominantInfluence == RecommendationInfluence.CURRENT
    }
    val sessionCount = queueExplanations.count {
        it.dominantInfluence == RecommendationInfluence.SESSION
    }
    val profileCount = queueExplanations.count {
        it.dominantInfluence == RecommendationInfluence.PROFILE
    }
    val diverseCount = queueExplanations.count {
        it.dominantInfluence == RecommendationInfluence.DIVERSITY
    }
    val neutralCount = queueExplanations.count {
        it.dominantInfluence == RecommendationInfluence.NONE
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Queue blend used to score AI songs",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
        ) {
            if (current > 0) Box(
                Modifier.weight(current.toFloat()).height(5.dp).background(CurrentInfluenceColor)
            )
            if (session > 0) Box(
                Modifier.weight(session.toFloat()).height(5.dp).background(SessionInfluenceColor)
            )
            if (profile > 0) Box(
                Modifier.weight(profile.toFloat()).height(5.dp).background(ProfileInfluenceColor)
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = "Current $current%  •  Session $session%  •  Long-term $profile%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Diversity ${(adventurous * 100).toInt()}%  •  Negative guard ${(negativeGuard * 100).toInt()}%" +
                if (filteredCount > 0) "  •  $filteredCount taste-blocked" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Bar: amber = explicit anchor • green = session mood • blue = long-term taste",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Queue influence dots",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        InfluenceLegend(CurrentInfluenceColor, currentCount, "Explicit-anchor match")
        InfluenceLegend(SessionInfluenceColor, sessionCount, "Session mood")
        InfluenceLegend(ProfileInfluenceColor, profileCount, "Long-term taste")
        InfluenceLegend(DiversityInfluenceColor, diverseCount, "Diverse pick")
        if (neutralCount > 0) {
            InfluenceLegend(MaterialTheme.colorScheme.outline, neutralCount, "No single dominant source")
        }
    }
}

@Composable
private fun InfluenceLegend(
    color: Color,
    count: Int,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$count $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    index: Int,
    song: Song,
    role: QueueRole,
    currentMediaId: String?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: (() -> Unit)?,
    isDragging: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null,
    explanation: RecommendationExplanation? = null,
    fallbackLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val isCurrent = role == QueueRole.Current
    val rowBg = when {
        isDragging -> MaterialTheme.colorScheme.surfaceVariant
        isCurrent -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.background
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .background(rowBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
        ArtThumbnail(filePath = song.filePath, size = 44.dp, cornerRadius = 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = song.title.ifBlank { song.filename },
                style = MaterialTheme.typography.titleSmall,
                color = when (role) {
                    QueueRole.Current -> MaterialTheme.colorScheme.primary
                    QueueRole.Previous -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onBackground
                },
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
            val originLabel = fallbackLabel ?: explanation?.origin?.label
            if (!originLabel.isNullOrBlank()) {
                Text(
                    text = originLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove from queue",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        // 6-dot drag handle — only present for Coming Up rows (the caller
        // passes a non-null dragHandle composable that wires
        // `Modifier.draggableHandle()` from the reorderable scope).
        if (dragHandle != null) dragHandle()
    }
}

@Composable
private fun WhySongDialog(
    explanation: RecommendationExplanation,
    onDismiss: () -> Unit,
) {
    val (current, session, profile) = explanation.normalizedBlendContributions()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why this song?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current $current%  •  Session $session%  •  Long-term $profile%")
                Text(
                    "Own ${signed(explanation.directScore)}  •  Halo ${signed(explanation.similarityScore)}  •  Total ${signed(explanation.totalScore)}"
                )
                Text("Taste adjustment ${signed(explanation.tasteAdjustment)}")
                if (explanation.isDiversePick) {
                    Text(
                        "Diverse pick: moved from relevance #${explanation.relevanceRank} to queue #${explanation.finalRank}."
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun signed(value: Float): String =
    (if (value >= 0f) "+" else "") + "%.2f".format(value)

private fun alignQueueExplanations(
    queue: List<Song>,
    explanations: List<RecommendationExplanation>,
): List<RecommendationExplanation?> {
    if (queue.isEmpty()) return emptyList()
    if (explanations.isEmpty()) return List(queue.size) { null }
    val aligned = ArrayList<RecommendationExplanation?>(queue.size)
    var explanationIndex = 0
    for (song in queue) {
        val next = explanations.getOrNull(explanationIndex)
        if (next != null && next.filename == song.filename) {
            aligned += next
            explanationIndex++
        } else {
            aligned += null
        }
    }
    return aligned
}
