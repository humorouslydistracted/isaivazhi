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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    HardToReach("Hard to reach"),
    Everywhere("Everywhere"),
    Suspects("Suspects"),
    Short("Short tracks"),
}

private data class HealthRow(val song: Song, val detail: String)

private const val MOST_SURFACED_LIMIT = 100

/** Matches EmbeddingWindowConfig.SHORT_TRACK_SECONDS: below this, clips use the audible part only. */
private const val SHORT_TRACK_MS = 30_000L

private fun shortTracks(report: EmbeddingHealthAnalyzer.Report, songsByHash: Map<String, Song>): List<Song> {
    val keys = LinkedHashSet<String>(report.raw.occurrence.keys)
    report.suspects.forEach { keys.add(it.key) }
    return keys.mapNotNull { songsByHash[it] }
        .filter { it.durationMs in 1 until SHORT_TRACK_MS }
        .sortedBy { it.durationMs }
}

private fun formatSeconds(ms: Long): String {
    val s = (ms + 500) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * [sharperSimilarityOn] is the Settings toggle; the panel shows the space the
 * recommender actually uses and summarises the other one in a single line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmbeddingHealthPanel(
    state: EmbeddingHealthEngine.State,
    songsByHash: Map<String, Song>,
    sharperSimilarityOn: Boolean,
    currentMediaId: String?,
    onAnalyze: () -> Unit,
    onPlay: (songs: List<Song>, index: Int) -> Unit,
    onLongPress: (Song) -> Unit,
    onReembed: (List<Song>) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(HealthSection.HardToReach) }
    var confirmSection by remember { mutableStateOf<List<Song>?>(null) }
    val ready = state as? EmbeddingHealthEngine.State.Ready
    val rows: List<HealthRow> = remember(ready, section, sharperSimilarityOn, songsByHash) {
        if (ready == null) emptyList() else buildRows(ready.report, section, sharperSimilarityOn, songsByHash)
    }
    val playable = remember(rows) { rows.map { it.song } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item(key = "status") {
            StatusCard(state = state, onAnalyze = onAnalyze)
        }
        if (ready != null) {
            item(key = "summary") {
                val shortCount = remember(ready, songsByHash) {
                    shortTracks(ready.report, songsByHash).size
                }
                SummaryCard(ready.report, sharperSimilarityOn, shortCount)
            }
            item(key = "chips") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
                    if (playable.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { confirmSection = playable }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Re-embed all (${playable.size})")
                        }
                    }
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
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                    IconButton(onClick = { onReembed(listOf(row.song)) }) {
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

    val pending = confirmSection
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmSection = null },
            title = { Text("Re-embed ${pending.size} songs?") },
            text = {
                Text(
                    "Their embeddings are recomputed in the background and replace the current ones. " +
                        "If embedding is already running, these join the end of that run. " +
                        "Re-analyze when it finishes.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSection = null; onReembed(pending) }) { Text("Re-embed") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSection = null }) { Text("Cancel") }
            },
        )
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
private fun SummaryCard(report: EmbeddingHealthAnalyzer.Report, sharper: Boolean, shortCount: Int) {
    val k = report.k
    val space = if (sharper) report.centered else report.raw
    val other = if (sharper) report.raw else report.centered
    val unusual = report.suspects.count { it.reason == EmbeddingHealthAnalyzer.SuspectReason.OUTLIER }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(
            if (sharper) "Using Sharper similarity (Settings)" else "Using original matching",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SummaryLine("Hard to reach (in ≤${EmbeddingHealthAnalyzer.lowExposureMax(k)} lists)", space.hardToReach(k).size)
        SummaryLine("  of which never picked", space.orphans().size)
        SummaryLine("Everywhere (in ≥${EmbeddingHealthAnalyzer.hubMin(k)} lists)", space.hubs(k).size)
        SummaryLine("Suspect embeddings", report.suspects.size - unusual)
        SummaryLine("Unusual, probably fine", unusual)
        SummaryLine("Short tracks (under 30 s)", shortCount)
        Spacer(Modifier.height(6.dp))
        Text(
            "Turning Sharper similarity ${if (sharper) "off" else "on"} would give " +
                "${other.hardToReach(k).size} hard to reach and ${other.hubs(k).size} everywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Each song's $k closest matches by sound alone (no taste, no variety). " +
                "On average a song is in $k lists. Fewer hard-to-reach and everywhere " +
                "songs mean similarity spreads more evenly across your library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryLine(label: String, value: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
    HealthSection.HardToReach ->
        "In at most ${EmbeddingHealthAnalyzer.lowExposureMax(k)} of the top-$k lists, never-picked first. " +
            "Up Next built from similarity rarely reaches these; the discovery slot draws from them."
    HealthSection.Everywhere ->
        "Songs in the most top-$k lists. Very high counts crowd out everything else."
    HealthSection.Suspects ->
        "Embeddings that may be wrong. Listen first; re-embed if it sounds fine. " +
            "\"Unusual\" songs are listed last and are usually fine."
    HealthSection.Short ->
        "Under 30 s, so they get fewer clips (silence trimmed, very short audio looped). " +
            "Their matches can be less reliable; listen to check."
}

private fun buildRows(
    report: EmbeddingHealthAnalyzer.Report,
    section: HealthSection,
    sharper: Boolean,
    songsByHash: Map<String, Song>,
): List<HealthRow> {
    val space = if (sharper) report.centered else report.raw
    val k = report.k
    fun lists(hash: String): String = when (val n = space.occurrence[hash] ?: 0) {
        0 -> "never picked"
        1 -> "in 1 list"
        else -> "in $n lists"
    }
    return when (section) {
        HealthSection.HardToReach -> space.hardToReach(k)
            .mapNotNull { h -> songsByHash[h]?.let { HealthRow(it, lists(h)) } }
        HealthSection.Everywhere -> space.mostSurfaced(MOST_SURFACED_LIMIT)
            .mapNotNull { h -> songsByHash[h]?.let { HealthRow(it, lists(h)) } }
        HealthSection.Suspects -> report.suspects.mapNotNull { s ->
            val song = songsByHash[s.key] ?: return@mapNotNull null
            val detail = when (s.reason) {
                EmbeddingHealthAnalyzer.SuspectReason.INVALID_VECTOR -> "Embedding is empty or invalid"
                EmbeddingHealthAnalyzer.SuspectReason.NEAR_IDENTICAL_CLUSTER ->
                    "Nearly identical to ${s.nearIdenticalCount} other songs"
                EmbeddingHealthAnalyzer.SuspectReason.OUTLIER ->
                    "Unusual (probably fine): closest match %.2f, typical %.2f".format(
                        s.nearestSimilarity, report.medianNearestSimilarity,
                    )
            }
            HealthRow(song, detail)
        }
        HealthSection.Short -> shortTracks(report, songsByHash).map { song ->
            val h = song.contentHash.orEmpty()
            HealthRow(song, "${formatSeconds(song.durationMs)} • ${lists(h)}")
        }
    }
}
