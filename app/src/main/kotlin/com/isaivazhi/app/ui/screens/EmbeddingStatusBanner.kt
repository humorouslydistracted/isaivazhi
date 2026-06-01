package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.EmbeddingEngine

/**
 * Status chip / banner shown on top of the Discover screen while the
 * embedding service is running. Hidden when no batch is in progress.
 *
 * Renders: "Embedding 23/2471 • NPU (FP16) • ~6m left" + linear progress.
 */
@Composable
fun EmbeddingStatusBanner(
    status: EmbeddingEngine.EmbeddingStatus,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!status.inProgress && status.error == null) return

    val progress = if (status.total > 0) status.processed.toFloat() / status.total.toFloat() else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                // Push #43: during init (processed==0 + initStepText present),
                // the banner shows the actual init step ("Extracting audio
                // model…" / "Starting NPU/GPU…") instead of a generic
                // "warming up". After init completes, falls back to the
                // normal "Embedding X/Y" + backend display.
                val title = when {
                    status.error != null -> "Embedding error"
                    status.processed == 0 && status.total > 0 && status.initStepText.isNotBlank() ->
                        status.initStepText
                    else -> "Embedding ${status.processed}/${status.total}"
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildSubtitle(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status.inProgress) {
                TextButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
        if (status.inProgress && status.total > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

private fun buildSubtitle(s: EmbeddingEngine.EmbeddingStatus): String {
    if (s.error != null) return s.error
    val backendLabel = when {
        s.activeBackend.isBlank() -> "warming up"
        s.activeBackend.contains("fp16", ignoreCase = true) -> "NPU (FP16)"
        s.activeBackend.startsWith("nnapi", ignoreCase = true) -> "NPU"
        s.activeBackend.equals("cpu", ignoreCase = true) -> "CPU"
        else -> s.activeBackend
    }
    val etaPart = s.etaSeconds?.takeIf { it > 0 }?.let { secs ->
        when {
            secs < 60 -> "${secs}s left"
            secs < 3600 -> "~${secs / 60}m left"
            else -> "~${secs / 3600}h ${(secs % 3600) / 60}m left"
        }
    }
    val parts = listOfNotNull(
        s.current.takeIf { it.isNotBlank() },
        backendLabel,
        etaPart,
    )
    return parts.joinToString(" • ")
}
