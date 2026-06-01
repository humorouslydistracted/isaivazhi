package com.isaivazhi.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.ActivityLogEngine
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Push #74: in-app human-readable activity log. Mirrors the Capacitor debug
 * screen ("engine.activity"). Shows the last 200 entries from
 * [ActivityLogEngine] with category-coloured chips and an inline JSON detail
 * panel when a row is tapped.
 *
 * Reachable from Settings → Activity Log and from a long-press on the Taste
 * page header.
 */
@Composable
fun ActivityLogScreen(
    activityLog: ActivityLogEngine,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val entries by activityLog.entries.collectAsState()
    var selectedCategory by remember { mutableStateOf("all") }
    val expanded = remember { mutableStateOf<Set<Long>>(emptySet()) }

    val categories = listOf("all", "playback", "engine", "taste", "queue", "notification", "ui")
    val filtered = remember(entries, selectedCategory) {
        if (selectedCategory == "all") entries else entries.filter { it.category == selectedCategory }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Activity Log",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                val text = filtered.joinToString("\n") { formatLine(it) }
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("activity_log", text))
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy all")
            }
            IconButton(onClick = { activityLog.clear() }) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear")
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (cat in categories) {
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = {
                        val count = if (cat == "all") entries.size else entries.count { it.category == cat }
                        Text("$cat ($count)", style = MaterialTheme.typography.labelSmall)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = categoryColor(cat).copy(alpha = 0.25f),
                    ),
                )
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (filtered.isEmpty()) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No entries yet — start playing music or change a setting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.timestamp.toString() + ":" + it.type + ":" + it.message.hashCode() }) { entry ->
                    EntryRow(
                        entry = entry,
                        isExpanded = entry.timestamp in expanded.value,
                        onClick = {
                            expanded.value = if (entry.timestamp in expanded.value) {
                                expanded.value - entry.timestamp
                            } else {
                                expanded.value + entry.timestamp
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: ActivityLogEngine.Entry,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val ts = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    }
    val typeColor = categoryColor(entry.category)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "[$ts]",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(typeColor.copy(alpha = 0.25f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = entry.type,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = typeColor,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        if (isExpanded && entry.data.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            val pretty = remember(entry.data) {
                runCatching { JSONObject(entry.data).toString(2) }.getOrDefault(entry.data)
            }
            Text(
                text = pretty,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(8.dp),
            )
        }
    }
    HorizontalDivider(thickness = 0.25.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

private fun formatLine(entry: ActivityLogEngine.Entry): String {
    val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    val type = entry.type.padEnd(12).take(12)
    val base = "[$ts]  $type  ${entry.message}"
    if (entry.data.isBlank()) return base
    val data = runCatching { JSONObject(entry.data).toString() }.getOrDefault(entry.data)
    return "$base | data=$data"
}

private fun categoryColor(category: String): Color = when (category) {
    "playback" -> Color(0xFF4FC3F7)
    "engine" -> Color(0xFFFFB74D)
    "taste" -> Color(0xFFAED581)
    "queue" -> Color(0xFFBA68C8)
    "notification" -> Color(0xFFE57373)
    "ui" -> Color(0xFF90A4AE)
    else -> Color(0xFFB0BEC5)
}
