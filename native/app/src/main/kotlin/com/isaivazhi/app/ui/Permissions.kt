package com.isaivazhi.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Required permission for reading the music library.
 *   - Android 13+ (API 33): READ_MEDIA_AUDIO
 *   - Older: READ_EXTERNAL_STORAGE
 */
private val audioReadPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        @Suppress("DEPRECATION")
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun hasAudioReadPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, audioReadPermission) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Composable gate that emits the current permission grant state and offers a
 * launcher to request it. Pattern:
 *
 *     val granted = rememberAudioPermissionGate()
 *     if (granted.value) { LibraryUi() } else { RequestPermissionUi(granted) }
 *
 * The returned holder updates reactively when the system grant changes.
 */
class AudioPermissionGate(
    initialGranted: Boolean,
    val request: () -> Unit,
) {
    var granted: Boolean = initialGranted
        internal set
}

@Composable
fun rememberAudioPermissionGate(ctx: Context): AudioPermissionGateState {
    var granted by remember { mutableStateOf(hasAudioReadPermission(ctx)) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> granted = isGranted }

    // Re-check on first composition in case it was granted from system Settings.
    LaunchedEffect(Unit) {
        granted = hasAudioReadPermission(ctx)
    }

    return AudioPermissionGateState(
        granted = granted,
        request = { launcher.launch(audioReadPermission) },
        permissionName = audioReadPermission,
    )
}

data class AudioPermissionGateState(
    val granted: Boolean,
    val request: () -> Unit,
    val permissionName: String,
)

/**
 * Push #54: POST_NOTIFICATIONS runtime permission gate (Android 13+).
 *
 * The manifest declares POST_NOTIFICATIONS, but on targetSdk 33+ the
 * permission is dangerous and must be granted at runtime. Without this
 * grant, every `nm.notify(...)` call silently fails — the embedding
 * foreground service notification never appears in the status bar or on
 * the lockscreen no matter what channel importance / VISIBILITY_PUBLIC
 * the notification uses. This was the actual reason the lockscreen
 * notification stayed invisible across pushes #49 → #53 even after the
 * channel was bumped to IMPORTANCE_DEFAULT.
 *
 * On Android 12 and below, POST_NOTIFICATIONS is not a runtime permission
 * — the system grants it implicitly at install — so this gate reports
 * `granted = true` unconditionally on those devices.
 */
fun hasNotificationsPermission(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun rememberNotificationsPermissionGate(ctx: Context): NotificationsPermissionGateState {
    var granted by remember { mutableStateOf(hasNotificationsPermission(ctx)) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> granted = isGranted }

    LaunchedEffect(Unit) {
        granted = hasNotificationsPermission(ctx)
    }

    return NotificationsPermissionGateState(
        granted = granted,
        request = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}

data class NotificationsPermissionGateState(
    val granted: Boolean,
    val request: () -> Unit,
)
