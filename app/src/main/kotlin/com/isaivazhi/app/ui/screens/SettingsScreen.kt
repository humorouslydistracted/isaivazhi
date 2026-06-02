package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * App & device maintenance: restore embeddings after reinstall, clear caches.
 * Embedding status and batch actions live on AI & Library; logs live on Logs.
 */
@Composable
fun SettingsScreen(
    artCacheBytes: Long,
    importInProgress: Boolean = false,
    importStatus: String? = null,
    onBack: () -> Unit,
    onReimportEmbeddings: () -> Unit,
    onClearArtCache: () -> Unit,
    onOpenAiLibrary: (() -> Unit)? = null,
    onOpenLogs: (() -> Unit)? = null,
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

            Text(
                text = "Maintenance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ActionRow(
                title = "Import isaivazhi_embeddings.bin",
                subtitle = "Restore after reinstall. Export a backup from AI & Library first.",
                onClick = onReimportEmbeddings,
                enabled = !importInProgress,
            )
            EmbeddingsImportStatusRow(
                inProgress = importInProgress,
                statusMessage = importStatus,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ActionRow(
                title = "Clear album-art cache",
                subtitle = "Album-art cache: ${formatBytes(artCacheBytes)}. Re-extracts art on next view.",
                onClick = onClearArtCache,
            )

            if (onOpenAiLibrary != null || onOpenLogs != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "Go to",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (onOpenAiLibrary != null) {
                    ActionRow(
                        title = "AI & Library",
                        subtitle = "Embedding status, pending songs, and library health.",
                        onClick = onOpenAiLibrary,
                    )
                }
                if (onOpenLogs != null) {
                    ActionRow(
                        title = "Diagnostics",
                        subtitle = "Activity, embedding batches, crashes, and startup.",
                        onClick = onOpenLogs,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
