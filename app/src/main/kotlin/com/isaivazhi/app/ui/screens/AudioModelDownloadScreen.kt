package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.OnnxModelDownload

/**
 * Shown on first launch when CLAP ONNX weights are not on device yet.
 * Models are fetched from the GitHub Release (not bundled in the APK).
 */
@Composable
fun AudioModelDownloadScreen(
    downloadInProgress: Boolean,
    progressLine: String?,
    errorMessage: String?,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    showSkipConfirm: Boolean,
    onConfirmSkip: () -> Unit,
    onDismissSkipConfirm: () -> Unit,
) {
    if (showSkipConfirm) {
        AlertDialog(
            onDismissRequest = onDismissSkipConfirm,
            title = { Text("Skip audio model download?") },
            text = {
                Text(
                    "On-device embedding will not work until you download the audio model " +
                        "(~273 MB total from GitHub).\n\n" +
                        "You can still play music and import a precomputed embeddings backup. " +
                        "Download anytime from Settings.",
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmSkip) {
                    Text("Skip for now")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissSkipConfirm) {
                    Text("Go back")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Download audio model",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "IsaiVazhi needs the CLAP encoder to embed songs on this phone. " +
                "The model is downloaded once from our GitHub release (~273 MB) and stored privately on device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        if (downloadInProgress) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(
                text = progressLine ?: "Downloading…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
            }
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Download now")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Skip for now")
            }
        }
    }
}

/** Settings row status line. */
fun audioModelSettingsSubtitle(ready: Boolean, downloading: Boolean, progressLine: String?): String =
    when {
        downloading -> progressLine ?: "Downloading audio model…"
        ready -> "Installed — on-device embedding is available."
        else -> "Not installed — embed & background AI encoding need the model (~273 MB)."
    }
