package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * App & device maintenance: export/import embeddings backup, clear caches.
 * Embedding batch actions live on AI & Library; logs live on Diagnostics.
 */
@Composable
fun SettingsScreen(
    artCacheBytes: Long,
    importInProgress: Boolean = false,
    importStatus: String? = null,
    audioModelReady: Boolean = false,
    audioModelDownloading: Boolean = false,
    audioModelProgressLine: String? = null,
    onDownloadAudioModel: (() -> Unit)? = null,
    onBack: () -> Unit,
    onExportEmbeddings: () -> Unit = {},
    exportInProgress: Boolean = false,
    exportStatus: String? = null,
    onReimportEmbeddings: () -> Unit,
    onClearArtCache: () -> Unit,
    onOpenAiLibrary: (() -> Unit)? = null,
    onOpenLogs: (() -> Unit)? = null,
    networkBlocked: Boolean = false,
    onToggleNetworkBlocked: ((Boolean) -> Unit)? = null,
    onOpenNetworkSystemSettings: (() -> Unit)? = null,
    onOpenFolders: (() -> Unit)? = null,
    folderSummary: String = "",
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
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

            val scrollState = rememberScrollState()
            var showExportInfo by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
            ) {
            Text(
                text = "Audio model",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (onDownloadAudioModel != null) {
                ActionRow(
                    title = if (audioModelReady) "Audio model installed" else "Download audio model",
                    subtitle = audioModelSettingsSubtitle(
                        ready = audioModelReady,
                        downloading = audioModelDownloading,
                        progressLine = audioModelProgressLine,
                    ),
                    onClick = onDownloadAudioModel,
                    enabled = !audioModelDownloading && !audioModelReady,
                )
            }
            if (onToggleNetworkBlocked != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "Network",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Block network downloads",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = if (networkBlocked)
                                "Downloads disabled — re-enable to install model updates."
                            else
                                "Allow the app to download audio model updates over the network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = networkBlocked,
                        onCheckedChange = onToggleNetworkBlocked,
                    )
                }
                if (onOpenNetworkSystemSettings != null) {
                    ActionRow(
                        title = "System-level network restriction",
                        subtitle = "For complete isolation, revoke network permission in Android settings.",
                        onClick = onOpenNetworkSystemSettings,
                    )
                }
            }
            if (onOpenFolders != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "Folders",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                ActionRow(
                    title = "Manage folders",
                    subtitle = folderSummary.ifBlank { "Control which directories appear in your library." },
                    onClick = onOpenFolders,
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Maintenance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionRow(
                    title = "Export isaivazhi_embeddings.bin",
                    subtitle = "Save every AI vector to a portable backup file on your device.",
                    onClick = onExportEmbeddings,
                    enabled = !exportInProgress && !importInProgress,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { showExportInfo = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "About export",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            EmbeddingsImportStatusRow(
                inProgress = exportInProgress,
                statusMessage = exportStatus,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (showExportInfo) {
                AlertDialog(
                    onDismissRequest = { showExportInfo = false },
                    title = { Text("Export embeddings") },
                    text = {
                        Text(
                            "Saves every AI vector to isaivazhi_embeddings.bin on your device. " +
                                "After reinstall, use Import below to restore without re-embedding.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showExportInfo = false }) { Text("OK") }
                    },
                )
            }
            ActionRow(
                title = "Import isaivazhi_embeddings.bin",
                subtitle = "Restore after reinstall. Export a backup above first.",
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
            Spacer(Modifier.height(24.dp))
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
