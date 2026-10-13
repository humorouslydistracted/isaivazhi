package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.EmbeddingHealthAnalyzer
import com.isaivazhi.app.engine.EmbeddingHealthEngine
import com.isaivazhi.app.engine.Song

private enum class HealthSection(val label: String) {
    Never("Never picked"),
    Rare("Rarely picked"),
    Everywhere("Everywhere"),
    Suspects("Suspects"),
}

private data class HealthRow(val song: Song, val detail: String, val canReembed: Boolean)

private const val MOST_SURFACED_LIMIT = 100

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmbeddingHealthPanel(
    state: EmbeddingHealthEngine.State,
    songsByHash: Map<String, Song>,
    sharperSimilarityOn: Boolean,
    currentMediaId: String?,
    nowPlayingTitle: String,
    nowPlayingArtist: String,
    isPlaying: Boolean,
    onAnalyze: () -> Unit,
    onPlay: (songs: List<Song>, index: Int) -> Unit,
    onLongPress: (Song) -> Unit,
    onReembed: (Song) -> Unit,
    onTogglePause: () -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(HealthSection.Never) }
    var viewSharper by rememberSaveable(sharperSimilarityOn) { mutableStateOf(sharperSimilarityOn) }
    val ready = state as? EmbeddingHealthEngine.State.Ready
    val rows: List<HealthRow> = remember(ready, section, viewSharper, songsByHash) {
        if (ready == null) emptyList() else buildRows(ready.report, section, viewSharper, songsByHash)
    }
    val playable = remember(rows) { rows.map { it.song } }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item(key = "status") {
                StatusCard(state = state, onAnalyze = onAnalyze)
            }
            if (ready != null) {
                item(key = "summary") {
                    SummaryCard(ready.report)
                }
                item(key = "chips") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HealthChip(!viewSharper, "Original matching") { viewSharper = false }
                            HealthChip(viewSharper, "Sharper similarity") { viewSharper = true }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (s in HealthSection.entries) {
                                HealthChip(section == s, s.label) { section = s }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = sectionHint(section, ready.report.k),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "Nothing here — good news.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                itemsIndexed(rows, key = { i, r -> "h_${section.name}_${i}_${r.song.id}" }) { i, row ->
                    val isCurrent = row.song.filename == currentMediaId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onPlay(playable, i) },
                                onLongClick = { onLongPress(row.song) },
                            )
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.background,
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtThumbnail(filePath = row.song.filePath, size = 44.dp, cornerRadius = 4.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.song.title.ifBlank { row.song.filename },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOf(row.song.artist, row.detail).filter { it.isNotBlank() }
                                    .joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (row.canReembed) {
                            IconButton(
                                onClick = { onReembed(row.song) },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "Re-embed",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (currentMediaId != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nowPlayingTitle.ifBlank { currentMediaId },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (nowPlayingArtist.isNotBlank()) {
                        Text(
                            text = nowPlayingArtist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onSkipPrev) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous",
                        tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = onTogglePause) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onSkipNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: EmbeddingHealthEngine.State, onAnalyze: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        when (state) {
            EmbeddingHealthEngine.State.Idle -> {
                Text(
                    "Checks every embedded song against the whole library to find songs that " +
                        "similarity never reaches, songs it reaches far too often, and embeddings " +
                        "that look broken. Runs on the phone in a few seconds; nothing is changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAnalyze) { Text("Analyze library") }
            }
            is EmbeddingHealthEngine.State.Running -> {
                Text(
                    "Analyzing… ${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is EmbeddingHealthEngine.State.Failed -> {
                Text(
                    "Analysis failed: ${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAnalyze) { Text("Try again") }
            }
            is EmbeddingHealthEngine.State.Ready -> {
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(state.computedAtMs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.report.songCount} songs analyzed at $time " +
                            "(${"%.1f".format(state.elapsedMs / 1000f)} s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onAnalyze) { Text("Re-analyze") }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(report: EmbeddingHealthAnalyzer.Report) {
    val k = report.k
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        SummaryLine("", "Original", "Sharper", header = true)
        SummaryLine(
            "Never picked",
            report.raw.orphans().size.toString(),
            report.centered.orphans().size.toString(),
        )
        SummaryLine(
            "Rarely picked (≤${EmbeddingHealthAnalyzer.lowExposureMax(k)})",
            report.raw.lowExposure(k).size.toString(),
            report.centered.lowExposure(k).size.toString(),
        )
        SummaryLine(
            "Everywhere (≥${EmbeddingHealthAnalyzer.hubMin(k)})",
            report.raw.hubs(k).size.toString(),
            report.centered.hubs(k).size.toString(),
        )
        SummaryLine(
            "Most lists one song is in",
            report.raw.maxOccurrence.toString(),
            report.centered.maxOccurrence.toString(),
        )
        SummaryLine("Suspect embeddings", report.suspects.size.toString(), "")
        Spacer(Modifier.height(6.dp))
        Text(
            "Each song's $k closest matches by sound alone (no taste, no variety). " +
                "On average a song is in $k lists. Lower \"Never\" and \"Everywhere\" " +
                "counts mean similarity spreads more evenly across your library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryLine(label: String, original: String, sharper: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = style, color = color, modifier = Modifier.weight(1f))
        Text(original, style = style, color = color, modifier = Modifier.width(72.dp))
        Text(sharper, style = style, color = color, modifier = Modifier.width(72.dp))
    }
}

@Composable
private fun HealthChip(active: Boolean, label: String, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

private fun sectionHint(section: HealthSection, k: Int): String = when (section) {
    HealthSection.Never ->
        "In nobody's top $k. Up Next built from similarity basically never reaches these. Tap to play them."
    HealthSection.Rare ->
        "In at most ${EmbeddingHealthAnalyzer.lowExposureMax(k)} lists. These are what the discovery slot draws from."
    HealthSection.Everywhere ->
        "Songs in the most top-$k lists. Very high counts crowd out everything else."
    HealthSection.Suspects ->
        "Embeddings that look wrong. Listen first; tap ↻ to re-embed one if it sounds fine."
}

private fun buildRows(
    report: EmbeddingHealthAnalyzer.Report,
    section: HealthSection,
    sharper: Boolean,
    songsByHash: Map<String, Song>,
): List<HealthRow> {
    val space = if (sharper) report.centered else report.raw
    val other = if (sharper) report.raw else report.centered
    val otherLabel = if (sharper) "Original" else "Sharper"
    val k = report.k
    fun counts(hash: String): String {
        val n = space.occurrence[hash] ?: 0
        val o = other.occurrence[hash] ?: 0
        return "in $n lists ($otherLabel: $o)"
    }
    return when (section) {
        HealthSection.Never -> space.orphans()
            .mapNotNull { h -> songsByHash[h]?.let { HealthRow(it, counts(h), canReembed = false) } }
            .sortedBy { it.song.title.lowercase() }
        HealthSection.Rare -> space.occurrence.entries
            .filter { it.value in 1..EmbeddingHealthAnalyzer.lowExposureMax(k) }
            .sortedBy { it.value }
            .mapNotNull { (h, _) -> songsByHash[h]?.let { HealthRow(it, counts(h), canReembed = false) } }
        HealthSection.Everywhere -> space.mostSurfaced(MOST_SURFACED_LIMIT)
            .mapNotNull { h -> songsByHash[h]?.let { HealthRow(it, counts(h), canReembed = false) } }
        HealthSection.Suspects -> report.suspects.mapNotNull { s ->
            val song = songsByHash[s.key] ?: return@mapNotNull null
            val detail = when (s.reason) {
                EmbeddingHealthAnalyzer.SuspectReason.INVALID_VECTOR -> "Embedding is empty or invalid"
                EmbeddingHealthAnalyzer.SuspectReason.OUTLIER ->
                    "Unlike anything else: closest match %.2f (typical %.2f)".format(
                        s.nearestSimilarity, report.medianNearestSimilarity,
                    )
                EmbeddingHealthAnalyzer.SuspectReason.NEAR_IDENTICAL_CLUSTER ->
                    "Nearly identical to ${s.nearIdenticalCount} other songs"
            }
            HealthRow(song, detail, canReembed = true)
        }
    }
}
