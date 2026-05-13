package com.isaivazhi.app.engine

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Push #41: app-wide feedback channel.
 *
 * The Capacitor build had ~40 `showStatusToast(text, duration)` call
 * sites across favorites/playlists/embedding/playback/errors; the Kotlin
 * port had none. This tiny helper lets ANY engine or UI code emit a
 * snackbar message that MainActivity's `Scaffold(snackbarHost = …)`
 * consumes via a single LaunchedEffect.
 *
 * Buffer capacity 8 + DROP_OLDEST so a flurry of toggles (e.g. user
 * rapidly tapping fav/dislike) doesn't drop messages but also doesn't
 * grow unbounded. tryEmit is non-suspending so call-sites stay simple
 * (no coroutineScope required).
 */
class Toaster {
    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun show(text: String) {
        if (text.isBlank()) return
        _messages.tryEmit(text)
    }
}
