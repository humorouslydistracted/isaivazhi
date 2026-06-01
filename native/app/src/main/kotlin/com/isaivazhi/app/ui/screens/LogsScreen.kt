package com.isaivazhi.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.ActivityLogEngine
import com.isaivazhi.app.engine.DebugLogCapture
import kotlinx.coroutines.launch

enum class LogsTab { Activity, Embedding, Crashes, Startup }

/**
 * Diagnostics — four focused logs:
 * - Activity: curated app events (playback, taste, queue, engine)
 * - Embedding: ONNX batch trace
 * - Crashes: persisted uncaught exceptions
 * - Startup: filtered logcat for cold start / library load
 */
@Composable
fun LogsScreen(
    activityLog: ActivityLogEngine,
    embeddingLogLines: List<String>,
    onClearEmbeddingLog: () -> Unit,
    initialTab: LogsTab = LogsTab.Activity,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    var crashText by remember { mutableStateOf(DebugLogCapture.readCrashLog(ctx)) }
    var startupText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val activityEntries by activityLog.entries.collectAsState()

    LaunchedEffect(tab) {
        if (tab == LogsTab.Startup) {
            loading = true
            try { startupText = DebugLogCapture.captureStartupDiagnostics() } finally { loading = false }
        }
    }

    val tabSubtitle = when (tab) {
        LogsTab.Activity -> "What the app did: plays, skips, taste, queue, favorites (last 200)."
        LogsTab.Embedding -> "ONNX embedding batches from AI & Library only."
        LogsTab.Crashes -> "Stack traces after the app was killed — survives restart."
        LogsTab.Startup -> "Cold start and library load (filtered device log)."
    }

    val copyLabel = when (tab) {
        LogsTab.Activity -> "IsaiVazhi activity log"
        LogsTab.Embedding -> "IsaiVazhi embedding log"
        LogsTab.Crashes -> "IsaiVazhi crashes"
        LogsTab.Startup -> "IsaiVazhi startup diagnostics"
    }

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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = "Activity = everyday issues. Crashes = force-close. Startup = slow open. Embedding = batch jobs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogsTabChip(active = tab == LogsTab.Activity, label = "Activity", onClick = { tab = LogsTab.Activity })
                LogsTabChip(active = tab == LogsTab.Embedding, label = "Embedding", onClick = { tab = LogsTab.Embedding })
                LogsTabChip(active = tab == LogsTab.Crashes, label = "Crashes", onClick = { tab = LogsTab.Crashes })
                LogsTabChip(active = tab == LogsTab.Startup, label = "Startup", onClick = { tab = LogsTab.Startup })
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tabSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when (tab) {
                    LogsTab.Activity -> {
                        IconButton(onClick = { activityLog.clear() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    LogsTab.Embedding -> {
                        IconButton(onClick = onClearEmbeddingLog) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    LogsTab.Crashes -> {
                        IconButton(onClick = {
                            DebugLogCapture.clearCrashLog(ctx)
                            crashText = DebugLogCapture.readCrashLog(ctx)
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    LogsTab.Startup -> {
                        IconButton(onClick = {
                            scope.launch {
                                loading = true
                                try {
                                    DebugLogCapture.clearLogcatBuffer()
                                    startupText = DebugLogCapture.captureStartupDiagnostics()
                                } finally { loading = false }
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear buffer",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                loading = true
                                try { startupText = DebugLogCapture.captureStartupDiagnostics() }
                                finally { loading = false }
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    else -> {}
                }
                TextButton(onClick = {
                    val clip = ctx.getSystemService(ClipboardManager::class.java)
                    val text = when (tab) {
                        LogsTab.Activity -> {
                            activityEntries.joinToString("\n") { formatActivityLineForCopy(it) }
                        }
                        LogsTab.Embedding -> embeddingLogLines.asReversed().joinToString("\n")
                        LogsTab.Crashes -> crashText
                        LogsTab.Startup -> startupText
                        else -> ""
                    }
                    clip?.setPrimaryClip(ClipData.newPlainText(copyLabel, text))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            when (tab) {
                LogsTab.Activity -> {
                    ActivityLogPane(activityLog = activityLog, modifier = Modifier.fillMaxSize())
                }
                LogsTab.Embedding -> {
                    EmbeddingLogPane(logLines = embeddingLogLines, modifier = Modifier.fillMaxSize())
                }
                LogsTab.Crashes -> {
                    MonospaceLogBody(text = if (loading) "Capturing…" else crashText)
                }
                LogsTab.Startup -> {
                    MonospaceLogBody(text = if (loading) "Capturing…" else startupText)
                }
            }
        }
    }
}

private fun formatActivityLineForCopy(entry: ActivityLogEngine.Entry): String {
    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(entry.timestamp))
    val type = entry.type.padEnd(12).take(12)
    val base = "[$ts]  $type  ${entry.message}"
    if (entry.data.isBlank()) return base
    val data = runCatching { org.json.JSONObject(entry.data).toString() }.getOrDefault(entry.data)
    return "$base | data=$data"
}

@Composable
private fun MonospaceLogBody(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
        ) {
            val scrollState = rememberScrollState()
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            )
        }
    }
}

@Composable
private fun LogsTabChip(active: Boolean, label: String, onClick: () -> Unit) {
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
