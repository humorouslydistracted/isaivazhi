package com.isaivazhi.app.engine

import java.io.File

/**
 * Push #62: small utility for filesystem-side song metadata that
 * `Song` doesn't carry (it's MediaStore-derived). Used by every
 * "view details" surface so the user can tell duplicate copies apart
 * by size / extension / mtime even when the audio is byte-identical.
 *
 * All accessors are nullable + best-effort — calls land on the IO
 * thread via `withContext(Dispatchers.IO)` at the call site or are
 * already on a background scope, so a missing/unreadable file just
 * surfaces as "—" in the UI without crashing.
 */
object FileMeta {

    data class Info(
        val sizeBytes: Long,
        val extension: String,
        val lastModified: Long,
        val exists: Boolean,
    )

    /** Reads size + extension + mtime in one stat call. Safe on background thread. */
    fun read(filepath: String?): Info {
        if (filepath.isNullOrBlank()) return Info(0L, "", 0L, false)
        return try {
            val f = File(filepath)
            if (!f.exists()) Info(0L, "", 0L, false)
            else Info(
                sizeBytes = f.length(),
                extension = f.extension.lowercase(),
                lastModified = f.lastModified(),
                exists = true,
            )
        } catch (t: Throwable) {
            Info(0L, "", 0L, false)
        }
    }

    /** Human-readable size: "12.3 MB", "812 KB", "—" for unknown/zero. */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "%.2f GB".format(bytes / gb)
            bytes >= mb -> "%.1f MB".format(bytes / mb)
            bytes >= kb -> "%.0f KB".format(bytes / kb)
            else -> "$bytes B"
        }
    }
}
