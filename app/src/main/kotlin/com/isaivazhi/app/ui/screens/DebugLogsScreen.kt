package com.isaivazhi.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.DebugLogCapture

/**
 * Debug Logs overlay. Two tabs:
 *   - Crashes: persisted file written by the uncaught exception handler.
 *     Survives process death so the user can grab a stack trace after
 *     reopening the app.
 *   - Logcat: current device logcat buffer (last 1500 lines, threadtime
 *     format). Captured fresh every time the user taps Refresh.
 *
 * Each tab has a Copy button that puts the full text on the clipboard.
 */
@Composable
fun DebugLogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(DebugTab.Crashes) }
    var crashText by remember { mutableStateOf(DebugLogCapture.readCrashLog(ctx)) }
    var logcatText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val refreshLogcat: () -> Unit = {
        scope.launch {
            loading = true
            try { logcatText = DebugLogCapture.captureLogcat() } finally { loading = false }
        }
    }

    // Push #45: auto-capture every time the user switches to the Logcat
    // tab so they never see a stale placeholder ("Tap Refresh…" /
    // "Cleared"). Old behaviour gated on the placeholder string, so a
    // Clear tap left the user looking at an empty pane until they tapped
    // Refresh. Now switching back always shows a fresh capture.
    LaunchedEffect(tab) {
        if (tab == DebugTab.Logcat) {
            loading = true
            try { logcatText = DebugLogCapture.captureLogcat() } finally { loading = false }
        }
    }

    val visibleText = when (tab) { DebugTab.Crashes -> crashText; DebugTab.Logcat -> logcatText }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
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
                    text = "Debug Logs",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                // Push #45 (revised): Clear restored on both tabs. On
                // Crashes it deletes the persisted crash file. On Logcat
                // it actually clears the system buffer via `logcat -c`
                // (not just blanks the in-memory string — that was the
                // confusing behavior).
                IconButton(onClick = {
                    when (tab) {
                        DebugTab.Crashes -> {
                            DebugLogCapture.clearCrashLog(ctx)
                            crashText = DebugLogCapture.readCrashLog(ctx)
                        }
                        DebugTab.Logcat -> {
                            scope.launch {
                                loading = true
                                try {
                                    DebugLogCapture.clearLogcatBuffer()
                                    logcatText = DebugLogCapture.captureLogcat()
                                } finally { loading = false }
                            }
                        }
                    }
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Tab toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabChip(active = tab == DebugTab.Crashes, label = "Crashes",
                    onClick = { tab = DebugTab.Crashes })
                TabChip(active = tab == DebugTab.Logcat, label = "Logcat",
                    onClick = { tab = DebugTab.Logcat })
            }

            // Info line + actions
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (tab) {
                        DebugTab.Crashes -> "Persisted across app restarts. Captures every uncaught exception."
                        DebugTab.Logcat -> "Live buffer snapshot (latest at the top). Refresh after reproducing a crash."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (tab == DebugTab.Logcat) {
                    IconButton(onClick = refreshLogcat) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    // Push #45: removed Clear button — it just blanked
                    // the in-memory string, leaving an empty pane that
                    // the user thought meant the logcat itself was
                    // cleared. Auto-refresh on tab activation handles it.
                }
                TextButton(onClick = {
                    val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val label = "IsaiVazhi ${if (tab == DebugTab.Crashes) "crashes" else "logcat"}"
                    clip.setPrimaryClip(ClipData.newPlainText(label, visibleText))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Log body — monospace, scrollable
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
                        text = if (loading) "Capturing…" else visibleText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                    )
                }
            }
        }
    }
}

private enum class DebugTab { Crashes, Logcat }

@Composable
private fun TabChip(active: Boolean, label: String, onClick: () -> Unit) {
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
