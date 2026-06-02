package com.isaivazhi.app.ui

/** Formats track length for list rows (e.g. `3:42`). */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val s = ms / 1000
    val m = s / 60
    val r = s % 60
    return "$m:${r.toString().padStart(2, '0')}"
}
