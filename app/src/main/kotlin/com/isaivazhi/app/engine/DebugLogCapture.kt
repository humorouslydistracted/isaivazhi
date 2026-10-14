package com.isaivazhi.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture for the in-app Diagnostics screen (Crashes + Startup tabs). Two sources:
 *
 *   - **Crashes** — persisted file in app data dir, written by an uncaught
 *     exception handler installed at app start. Survives the process death
 *     that crashes typically cause, so the next app launch can show the
 *     trace even though logcat's ring buffer may have rolled over.
 *
 *   - **Logcat (current buffer)** — `logcat -d -v threadtime -t 1500` of the
 *     current device buffer, captured at the moment the user opens the
 *     screen. App's own process logs are always readable; other processes
 *     return empty on most user-installed builds (no READ_LOGS permission).
 *
 * Startup tab uses filtered logcat; Crashes uses the persisted file. Raw logcat is not shown in UI.
 */
object DebugLogCapture {

    private const val CRASH_FILE = "debug_crash_log.txt"
    private const val MAX_FILE_BYTES = 256 * 1024  // 256 KB cap; half-truncate when exceeded

    /**
     * Install a chained uncaught exception handler that appends every
     * crash trace (with timestamp + thread name) to the persisted crash
     * log file before delegating to the previous handler (which lets
     * Android show the system "App stopped" dialog as usual).
     */
    @Volatile private var handlerInstalled = false

    /** Installed once per process (main and :ai), from Application.onCreate. */
    @Synchronized
    fun installCrashHandler(ctx: Context) {
        if (handlerInstalled) return
        handlerInstalled = true
        val process = currentProcessName(ctx)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                pw.println("=== CRASH @ $ts (process: $process, thread: ${thread.name}) ===")
                throwable.printStackTrace(pw)
                pw.println()
                appendToFile(ctx, sw.toString())
            } catch (t: Throwable) {
                // best-effort — never let crash logging itself crash again
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun appendToFile(ctx: Context, text: String) {
        try {
            val f = File(ctx.filesDir, CRASH_FILE)
            // Half-truncate when file grows past the cap so we keep recent
            // crashes without unbounded growth.
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val existing = f.readText()
                val keep = existing.substring(existing.length / 2)
                f.writeText(keep)
            }
            f.appendText(text)
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** Persisted crash traces followed by Android's record of recent app exits. */
    fun readCrashLog(ctx: Context): String {
        val f = File(ctx.filesDir, CRASH_FILE)
        val traces = if (f.exists() && f.length() > 0) f.readText()
        else "No crash traces recorded.\n\nIf the app crashed previously and this is empty, the crash handler wasn't installed yet — close and reopen the app, then reproduce."
        val exits = readRecentExits(ctx)
        return if (exits.isBlank()) traces else "$traces\n\n$exits"
    }

    /**
     * Android keeps why each of the app's processes died (crash, native
     * crash, ANR, killed for memory, background limits, user swipe…),
     * including crashes in the :ai embedding process that our own handler
     * may have missed. Available on Android 11+.
     */
    private fun readRecentExits(ctx: Context): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return ""
        return try {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return ""
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, MAX_EXITS)
            if (exits.isEmpty()) return ""
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            buildString {
                appendLine("=== Recent app exits (from Android, newest first) ===")
                for (e in exits) {
                    append(fmt.format(Date(e.timestamp)))
                    append("  ").append(e.processName)
                    append("  ").append(exitReasonName(e.reason))
                    if (e.status != 0) append(" (status ${e.status})")
                    append("  pss=").append(e.pss / 1024).append("MB rss=").append(e.rss / 1024).append("MB")
                    e.description?.takeIf { it.isNotBlank() }?.let { append("\n    ").append(it) }
                    appendLine()
                    if (e.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                        e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                    ) {
                        val trace = runCatching {
                            e.traceInputStream?.bufferedReader()?.use { r ->
                                r.lineSequence().take(TRACE_LINES).joinToString("\n")
                            }
                        }.getOrNull()
                        if (!trace.isNullOrBlank()) appendLine(trace.prependIndent("    "))
                    }
                }
            }
        } catch (t: Throwable) {
            "Recent app exits unavailable: ${t.message}"
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init failure"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
        android.app.ApplicationExitInfo.REASON_OTHER -> "other"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user/system stop"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "freezer"
        android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        else -> "reason $reason"
    }

    private fun currentProcessName(ctx: Context): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            ctx.packageName
        }

    private const val MAX_EXITS = 15
    private const val TRACE_LINES = 40

    /** Clear the persisted crash log file. */
    fun clearCrashLog(ctx: Context) {
        try { File(ctx.filesDir, CRASH_FILE).delete() } catch (_: Throwable) {}
    }

    /**
     * Capture the recent logcat buffer. Returns logs for this process if
     * READ_LOGS isn't available, which is the common case on user-installed
     * builds. Threadtime format includes PID/TID for cross-process traces.
     *
     * Push #45: returns lines in newest-first order so the user reads the
     * most recent activity at the top. `logcat -d -t N` returns chronological
     * (oldest-first); we reverse before returning.
     */
    /**
     * Push #45 (revised): clear the device logcat buffer via `logcat -c`.
     * After this, captureLogcat() returns only new events. Useful before
     * reproducing a hang/jank so the buffer has just the reproduction.
     * No-op on devices that don't allow `logcat -c` for the current uid;
     * the next capture will still work.
     */
    suspend fun clearLogcatBuffer(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("logcat", "-c").redirectErrorStream(true).start()
            process.waitFor()
            true
        } catch (t: Throwable) {
            false
        }
    }

    suspend fun captureLogcat(): String = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime", "-t", "3000"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (output.isBlank()) "logcat returned no output."
            else output.lines().asReversed().joinToString("\n")
        } catch (t: Throwable) {
            "logcat capture failed: ${t.message}"
        }
    }

    suspend fun captureStartupDiagnostics(): String = withContext(Dispatchers.IO) {
        val raw = captureLogcat()
        if (raw.startsWith("logcat ")) return@withContext raw
        val needles = listOf(
            "StartupTrace",
            "STARTUP_",
            "ActivityLog",
            "LIBRARY_LOAD_",
            "PERF_DB_",
            "DISCOVER_CACHE_LOAD",
            "DISCOVER_HYDRATE_",
            "DISCOVER_FORYOU_",
            "DISCOVER_UNEXP_",
            "ACTIVITY_PROCESS_COLD",
            "ACTIVITY_RECREATED",
            "LibraryCache",
            "PlaybackEngine",
            "EmbeddingDbFacade",
            "DiscoverLE",
        )
        val filtered = raw.lines()
            .filter { line -> needles.any { needle -> line.contains(needle) } }
            .joinToString("\n")
        if (filtered.isBlank()) {
            "No startup diagnostics found in current logcat buffer.\n\n" +
                "Steps: open Diagnostics, Startup tab, tap Clear buffer, reproduce slow start, reopen Startup, tap Copy."
        } else {
            filtered
        }
    }
}
