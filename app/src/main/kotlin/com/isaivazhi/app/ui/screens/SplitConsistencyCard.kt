package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.EmbeddingSplitStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MISMATCH_PREVIEW = 30

private sealed class SplitAction {
    abstract val rows: List<EmbeddingSplitStats.Row>
    data class Label(override val rows: List<EmbeddingSplitStats.Row>, val what: String) : SplitAction()
    data class Reembed(override val rows: List<EmbeddingSplitStats.Row>, val what: String) : SplitAction()
}

/** Splits per song in the library, with label / re-embed actions to make them consistent. */
@Composable
fun SplitConsistencyCard(
    summary: EmbeddingSplitStats.Summary,
    targetSplit: Int,
    inProgress: Boolean,
    onLabel: (hashes: List<String>, split: Int) -> Unit,
    onReembed: (rows: List<EmbeddingSplitStats.Row>) -> Unit,
) {
    if (summary.total == 0) return
    var action by remember { mutableStateOf<SplitAction?>(null) }
    var showMismatched by remember { mutableStateOf(false) }
    val dayFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
    ) {
        Text(
            "Splits in your library",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val parts = summary.bySplit.map { (split, n) -> "$split splits: $n" } +
            if (summary.unknownCount > 0) listOf("not recorded: ${summary.unknownCount}") else emptyList()
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (summary.labelledCount > 0) {
            Text(
                "${summary.labelledCount} of these were labelled by you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Short songs use fewer clips by design and count under the split that was requested.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (summary.mismatched.isNotEmpty()) {
            val n = summary.mismatched.size
            Text(
                "$n song${if (n == 1) "" else "s"} use a different split than $targetSplit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !inProgress,
                    onClick = { action = SplitAction.Reembed(summary.mismatched, "$n mismatched songs") },
                ) { Text("Re-embed at $targetSplit") }
                TextButton(onClick = { showMismatched = !showMismatched }) {
                    Text(if (showMismatched) "Hide list" else "Show list")
                }
            }
            if (showMismatched) {
                for (row in summary.mismatched.take(MISMATCH_PREVIEW)) {
                    Text(
                        "• ${row.filename.ifBlank { row.filepath }} — ${row.splitCount} splits",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (n > MISMATCH_PREVIEW) {
                    Text(
                        "+${n - MISMATCH_PREVIEW} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        if (summary.unknownByDay.isNotEmpty()) {
            Text(
                "Not recorded (${summary.unknownCount}): embedded before splits were tracked. " +
                    "If you know which split a day's run used, mark that day; otherwise re-embed it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (summary.unknownByDay.size > 1) {
                val all = summary.unknownByDay.flatMap { it.rows }
                TextButton(onClick = {
                    action = SplitAction.Label(all, "all ${all.size} unrecorded songs")
                }) { Text("Mark all ${all.size} as $targetSplit splits") }
            }
            for (group in summary.unknownByDay) {
                val day = if (group.dayStartMs > 0) dayFormat.format(Date(group.dayStartMs)) else "Unknown date"
                val count = group.rows.size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$day — $count song${if (count == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        action = SplitAction.Label(group.rows, "$count songs embedded on $day")
                    }) { Text("Mark $targetSplit") }
                    TextButton(
                        enabled = !inProgress,
                        onClick = { action = SplitAction.Reembed(group.rows, "$count songs embedded on $day") },
                    ) { Text("Re-embed") }
                }
            }
        }
    }

    when (val a = action) {
        is SplitAction.Label -> AlertDialog(
            onDismissRequest = { action = null },
            title = { Text("Mark as $targetSplit splits?") },
            text = {
                Text(
                    "Records that ${a.what} were embedded with $targetSplit splits. " +
                        "Only do this if you're sure; it doesn't change the embeddings themselves. " +
                        "Songs whose split was recorded by the app are never changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    action = null
                    onLabel(a.rows.map { it.contentHash }, targetSplit)
                }) { Text("Mark") }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
        is SplitAction.Reembed -> AlertDialog(
            onDismissRequest = { action = null },
            title = { Text("Re-embed at $targetSplit splits?") },
            text = {
                Text(
                    "Recomputes ${a.what} in the background with $targetSplit splits, " +
                        "replacing their current embeddings.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    action = null
                    onReembed(a.rows)
                }) { Text("Re-embed") }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}
