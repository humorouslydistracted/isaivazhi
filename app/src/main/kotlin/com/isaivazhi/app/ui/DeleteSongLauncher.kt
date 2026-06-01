package com.isaivazhi.app.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.isaivazhi.app.engine.DeleteAttempt
import com.isaivazhi.app.engine.SongDelete
import kotlinx.coroutines.launch

/**
 * Push #58: glue between `SongDelete.attempt()` (which produces an
 * IntentSender that must be launched via an `ActivityResultLauncher`)
 * and Compose state.
 *
 * Usage:
 *   ```
 *   val deleteHelper = rememberDeleteSongHelper(ctx) { filepath, success ->
 *       if (success) { /* refresh library, drop stale embedding etc. */ }
 *       else { /* surface "Delete denied" */ }
 *   }
 *   // …on user tap:
 *   deleteHelper.delete(song.filePath)
 *   ```
 *
 * The helper handles:
 *   - Calling `SongDelete.attempt` on a background scope.
 *   - Launching the system delete-confirmation dialog when needed.
 *   - Routing the dialog result back to the `onResult` callback.
 *   - Carrying the filepath across the suspend boundary so the callback
 *     gets the right one even if multiple deletes overlap.
 */
class DeleteSongHelper internal constructor(
    private val perform: (filepath: String) -> Unit,
    private val performBatch: (filepaths: List<String>) -> Unit,
) {
    fun delete(filepath: String) = perform(filepath)
    /** Push #62: batch delete for "Delete album" + similar bulk flows. */
    fun deleteBatch(filepaths: List<String>) = performBatch(filepaths)
}

@Composable
fun rememberDeleteSongHelper(
    ctx: Context,
    // Push #62: optional callback for the batch path. Fires once after
    // the user confirms the bundled system dialog (or denies). The
    // helper doesn't know which individual files succeeded vs failed;
    // it just reports the overall consent + count.
    // Declared BEFORE onResult so callers can supply the per-song
    // result via a trailing lambda while still naming onBatchResult.
    onBatchResult: (filepaths: List<String>, success: Boolean, error: String?) -> Unit = { _, _, _ -> },
    onResult: (filepath: String, success: Boolean, error: String?) -> Unit,
): DeleteSongHelper {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var pendingBatch by remember { mutableStateOf<List<String>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        val batch = pendingBatch
        val single = pending
        pending = null
        pendingBatch = null
        if (batch != null) {
            // Batch path: report overall outcome. Per-file verification
            // is the caller's responsibility (e.g. via library rescan).
            onBatchResult(batch, granted, if (!granted) "User declined" else null)
        } else if (single != null) {
            scope.launch {
                val truly = SongDelete.onConsentResult(single, granted)
                onResult(single, truly, if (!granted) "User declined" else null)
            }
        }
    }
    // Discard any pending state on composition leave.
    LaunchedEffect(Unit) { pending = null; pendingBatch = null }

    return remember(launcher) {
        DeleteSongHelper(
            perform = { filepath ->
                scope.launch {
                    when (val attempt = SongDelete.attempt(ctx, filepath)) {
                        is DeleteAttempt.Done -> onResult(filepath, attempt.success, attempt.error)
                        is DeleteAttempt.NeedsConsent -> {
                            pending = filepath
                            runCatching {
                                launcher.launch(
                                    IntentSenderRequest.Builder(attempt.intentSender).build()
                                )
                            }.onFailure {
                                pending = null
                                onResult(filepath, false, "launcher: ${it.message}")
                            }
                        }
                    }
                }
            },
            performBatch = { filepaths ->
                if (filepaths.isEmpty()) return@DeleteSongHelper
                scope.launch {
                    when (val attempt = SongDelete.attemptBatch(ctx, filepaths)) {
                        is DeleteAttempt.Done -> onBatchResult(filepaths, attempt.success, attempt.error)
                        is DeleteAttempt.NeedsConsent -> {
                            pendingBatch = filepaths
                            runCatching {
                                launcher.launch(
                                    IntentSenderRequest.Builder(attempt.intentSender).build()
                                )
                            }.onFailure {
                                pendingBatch = null
                                onBatchResult(filepaths, false, "launcher: ${it.message}")
                            }
                        }
                    }
                }
            },
        )
    }
}
