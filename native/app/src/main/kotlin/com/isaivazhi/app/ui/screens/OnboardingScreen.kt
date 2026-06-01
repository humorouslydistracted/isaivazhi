package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Onboarding step shown once permission is granted and the embeddings DB
 * is empty. Three actions per the user's spec:
 *   1. Import isaivazhi_embeddings.bin (or legacy local_embeddings.json)
 *   2. Embed in background (run ONNX over the library)
 *   3. Continue without (shuffle mode)
 *
 * ETA copy gives a rough estimate of how long embedding the full library
 * will take based on the assumed per-song latency by backend.
 */
@Composable
fun OnboardingScreen(
    songCount: Int,
    estimatedEmbedTimeSec: Long?,
    importInProgress: Boolean = false,
    importStatus: String? = null,
    onImportEmbeddings: () -> Unit,
    onEmbedInBackground: () -> Unit,
    onContinueWithoutEmbeddings: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Almost ready",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Found $songCount songs on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Recommendations need embeddings — one small vector per song.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            EmbeddingsImportStatusRow(
                inProgress = importInProgress,
                statusMessage = importStatus,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            if (importInProgress || !importStatus.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
            }
            Button(
                onClick = onImportEmbeddings,
                modifier = Modifier.fillMaxWidth(),
                enabled = !importInProgress,
            ) {
                Text("Import embeddings backup (.bin)")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Fastest — if you already have one from the older app or Colab generator.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = onEmbedInBackground,
                modifier = Modifier.fillMaxWidth(),
                enabled = !importInProgress,
            ) {
                Text("Embed in background")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("Runs ONNX in a background service while you play in shuffle mode. ")
                    if (estimatedEmbedTimeSec != null && estimatedEmbedTimeSec > 0) {
                        append("Estimated time: ${formatEta(estimatedEmbedTimeSec)} on this device.")
                    } else {
                        append("Initial run estimate appears once we probe the device backend.")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = onContinueWithoutEmbeddings,
                modifier = Modifier.fillMaxWidth(),
                enabled = !importInProgress,
            ) {
                Text("Skip for now — shuffle only")
            }
        }
    }
}

private fun formatEta(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    val rem = minutes % 60
    return "${hours}h ${rem}m"
}
