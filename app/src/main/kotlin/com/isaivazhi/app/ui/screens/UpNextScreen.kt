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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.Song
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
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh upcoming queue with current mode",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
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
        }
        IconButton(onClick = onLongPress) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
