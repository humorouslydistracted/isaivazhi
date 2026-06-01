package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Lightweight settings screen with the controls users need after onboarding:
 *   - re-import a local_embeddings.json
 *   - kick off background embedding for the full library
 *   - rescan MediaStore (invalidates the 6h library cache)
 *   - clear the album-art disk cache
 *   - stats: song count + embedding row count + DB size + sqlite-vec status
 */
@Composable
fun SettingsScreen(
    songCount: Int,
    embeddingRows: Int,
    embeddingDimText: String,
    vecExtensionLoaded: Boolean,
    artCacheBytes: Long,
    onBack: () -> Unit,
    onReimportEmbeddings: () -> Unit,
    onEmbedAllNow: () -> Unit,
    onRescanLibrary: () -> Unit,
    onClearArtCache: () -> Unit,
    onOpenActivityLog: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                StatRow("Songs on device", songCount.toString())
                StatRow("Embeddings in DB", "$embeddingRows ($embeddingDimText)")
                StatRow("sqlite-vec extension", if (vecExtensionLoaded) "Loaded" else "Not loaded — fallback to NEON")
                StatRow("Album-art cache", formatBytes(artCacheBytes))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(4.dp))
            ActionRow(
                title = "Import local_embeddings.json",
                subtitle = "Pick a file from device to replace current embeddings.",
                onClick = onReimportEmbeddings,
            )
            ActionRow(
                title = "Embed full library now",
                subtitle = "Runs ONNX in background for every song.",
                onClick = onEmbedAllNow,
            )
            ActionRow(
                title = "Rescan music library",
                subtitle = "Discards the cache and re-walks MediaStore.",
                onClick = onRescanLibrary,
            )
            ActionRow(
                title = "Clear album-art cache",
                subtitle = "Re-extracts art on next view.",
                onClick = onClearArtCache,
            )
            if (onOpenActivityLog != null) {
                ActionRow(
                    title = "Activity Log",
                    subtitle = "Last 200 playback/taste/notification events with timestamps.",
                    onClick = onOpenActivityLog,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${"%.1f".format(kb)} KB"
    val mb = kb / 1024.0
    return "${"%.1f".format(mb)} MB"
}
