package com.isaivazhi.app.engine

internal object PlaybackNotificationActionRouter {
    fun handle(action: String, onRefreshQueue: () -> Unit): Boolean {
        if (action != "refresh_queue") return false
        onRefreshQueue()
        return true
    }
}
