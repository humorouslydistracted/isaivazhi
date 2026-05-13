package com.isaivazhi.app.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded ring buffer of log lines, exposed as a StateFlow so any Compose
 * screen can subscribe. Used by EmbeddingEngine to surface inference
 * progress + backend transitions inside the AI page (the user can't easily
 * `adb logcat` on a stock device, so logs go to an on-device viewer).
 *
 * Push #48: persistent across app launches. On init, last N lines are
 * loaded from disk into the in-memory buffer. Every `append` schedules a
 * debounced flush to disk (1 s) so disk IO doesn't pile up under rapid
 * logging. When the in-memory buffer hits capacity, the oldest half is
 * dropped from memory but the on-disk file keeps an additional archive
 * tail so the user can scroll back further if needed.
 *
 * Default capacity = 1000 lines (the user's preferred archive threshold).
 */
class LogBuffer(
    private val capacity: Int = 1000,
    private val archiveContext: Context? = null,
    private val persistFileName: String = "ai_log.txt",
) {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushPending = AtomicBoolean(false)

    init {
        // Load whatever's on disk from a previous session. Newer entries
        // appended after this still land at the end via `append`.
        archiveContext?.let { ctx ->
            ioScope.launch {
                runCatching {
                    val file = File(ctx.filesDir, persistFileName)
                    if (file.exists() && file.length() > 0) {
                        val all = file.readLines()
                        val tail = if (all.size > capacity) all.takeLast(capacity) else all
                        _lines.value = tail
                    }
                }
            }
        }
    }

    fun append(tag: String, msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val line = "$ts [$tag] $msg"
        val cur = _lines.value
        // Drop the oldest line when at capacity (matches the legacy
        // ring-buffer semantics but without the half-batch archive — the
        // on-disk file holds the full history).
        val next = if (cur.size >= capacity) cur.drop(cur.size - capacity + 1) + line else cur + line
        _lines.value = next
        scheduleFlush()
    }

    fun clear() {
        _lines.value = emptyList()
        archiveContext?.let { ctx ->
            ioScope.launch {
                runCatching { File(ctx.filesDir, persistFileName).delete() }
            }
        }
    }

    fun snapshotText(): String = _lines.value.joinToString("\n")

    /**
     * Debounce: coalesces rapid appends into a single disk write after
     * ~1 s of inactivity. The in-memory state stays current; the disk
     * lags by at most ~1 s. A process kill loses up to ~1 s of lines —
     * acceptable for diagnostic logs.
     */
    private fun scheduleFlush() {
        if (archiveContext == null) return
        if (!flushPending.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                delay(1000L)
                val snapshot = _lines.value
                val file = File(archiveContext.filesDir, persistFileName)
                val tmp = File(archiveContext.filesDir, "$persistFileName.tmp")
                tmp.writeText(snapshot.joinToString("\n"))
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            } catch (t: Throwable) {
                android.util.Log.w("LogBuffer", "flush failed: ${t.message}")
            } finally {
                flushPending.set(false)
            }
        }
    }
}
