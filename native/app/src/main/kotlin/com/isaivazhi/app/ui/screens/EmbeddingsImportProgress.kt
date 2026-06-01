package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Inline status under Import row (Settings) or on onboarding. */
@Composable
fun EmbeddingsImportStatusRow(
    inProgress: Boolean,
    statusMessage: String?,
    modifier: Modifier = Modifier,
) {
    if (!inProgress && statusMessage.isNullOrBlank()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inProgress) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = statusMessage ?: "Working…",
            style = MaterialTheme.typography.bodyMedium,
            color = if (inProgress) {
                MaterialTheme.colorScheme.primary
            } else if (statusMessage.orEmpty().contains("failed", ignoreCase = true)) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * Modal shown during JSON import from anywhere except onboarding (which has its own layout).
 * Blocks interaction until finished; user dismisses the result.
 */
@Composable
fun EmbeddingsImportDialog(
    visible: Boolean,
    inProgress: Boolean,
    statusMessage: String?,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val message = statusMessage ?: if (inProgress) "Working…" else ""
    val isError = !inProgress && message.contains("failed", ignoreCase = true)

    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = {
            Text(
                text = when {
                    inProgress -> "Importing embeddings"
                    isError -> "Import failed"
                    else -> "Import complete"
                },
            )
        },
        text = {
            Column {
                if (inProgress) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (!isError && message.isNotBlank()) {
                        Spacer(Modifier.padding(top = 8.dp))
                        Text(
                            text = "Recommendations should work now. Check AI & Library for counts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!inProgress) {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        },
    )
}
