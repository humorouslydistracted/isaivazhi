package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.ActivityLogEngine
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity tab: full rolling activity log (playback, taste, queue, engine, UI) — no sub-filters.
 */
@Composable
fun ActivityLogPane(
    activityLog: ActivityLogEngine,
    modifier: Modifier = Modifier,
) {
    val entries by activityLog.entries.collectAsState()
    val expanded = remember { mutableStateOf<Set<Long>>(emptySet()) }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No events yet — play music, change taste, or use the library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.timestamp.toString() + ":" + it.type + ":" + it.message.hashCode() }) { entry ->
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

/** Embedding tab: ONNX batch trace from [com.isaivazhi.app.engine.LogBuffer]. */
@Composable
fun EmbeddingLogPane(
    logLines: List<String>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (logLines.isEmpty()) "No lines yet"
                else "${logLines.size} lines — batch progress from AI & Library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (logLines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Start an embedding batch from AI & Library to see progress here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val total = logLines.size
                items(total) { i ->
                    Text(
                        text = logLines[total - 1 - i],
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 1.dp),
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
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
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
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(8.dp),
            )
        }
    }
    HorizontalDivider(thickness = 0.25.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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
