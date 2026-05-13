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
 * Capture for the in-app Debug Logs screen. Two sources combined:
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
 * Both are shown via [DebugLogsScreen] and copyable to clipboard.
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
    fun installCrashHandler(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                pw.println("=== CRASH @ $ts (thread: ${thread.name}) ===")
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

    /** Read the persisted crash log file. */
    fun readCrashLog(ctx: Context): String {
        val f = File(ctx.filesDir, CRASH_FILE)
        return if (f.exists() && f.length() > 0) f.readText()
        else "No crash traces recorded.\n\nIf the app crashed previously and this is empty, the crash handler wasn't installed yet — close and reopen the app, then reproduce."
    }

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
                "logcat", "-d", "-v", "threadtime", "-t", "1500"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (output.isBlank()) "logcat returned no output."
            else output.lines().asReversed().joinToString("\n")
        } catch (t: Throwable) {
            "logcat capture failed: ${t.message}"
        }
    }
}
