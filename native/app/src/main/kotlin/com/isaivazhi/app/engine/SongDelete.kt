package com.isaivazhi.app.engine

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Push #58: Android 11+ scoped-storage-aware delete.
 *
 * Pre-push #58 this just called `File.delete()` + `resolver.delete()` and
 * caught exceptions, but on Android 11+ both fail silently for files the
 * app doesn't own. The previous "Deleted" toast was lying — the file was
 * never actually deleted, and after Rescan library the song came right
 * back from MediaStore.
 *
 * Two-phase API:
 *   1. `attempt(filePath)` — tries the delete on the IO thread.
 *      • Returns `Done(success=true)` if the row + file were both removed
 *        without needing user consent (rare on Android 11+; possible on
 *        older devices or for files the app owns).
 *      • Returns `NeedsConsent(intentSender)` when MediaStore requires the
 *        user to confirm via the system delete-confirmation dialog. The
 *        caller launches this via an ActivityResultLauncher.
 *      • Returns `Done(success=false, error=…)` when neither path is
 *        possible (file missing, MediaStore row missing, IO failure).
 *
 *   2. After the user confirms the system dialog, the caller calls
 *      `onConsentResult(filePath, granted)` so we can run the post-delete
 *      cleanup (best-effort file delete on top of the MediaStore-side
 *      delete that the OS just performed).
 */
sealed class DeleteAttempt {
    /** No further interaction required. Reflects what actually happened on disk. */
    data class Done(val success: Boolean, val error: String? = null) : DeleteAttempt()

    /** System dialog must be shown via the caller's ActivityResultLauncher. */
    data class NeedsConsent(val intentSender: IntentSender) : DeleteAttempt()
}

object SongDelete {

    /**
     * Look up the MediaStore content URI for a file at [filePath]. Returns
     * null if MediaStore doesn't have a row for that path (e.g. file was
     * external-deleted and MediaStore hasn't re-scanned).
     */
    private fun lookupUri(ctx: Context, filePath: String): android.net.Uri? {
        val resolver = ctx.contentResolver
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.DATA} = ?"
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, arrayOf(filePath), null
        ).use { c ->
            if (c == null || !c.moveToFirst()) return null
            val id = c.getLong(0)
            return ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
            )
        }
    }

    suspend fun attempt(ctx: Context, filePath: String): DeleteAttempt = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val uri = lookupUri(ctx, filePath)

        // No MediaStore row — file may already be gone, or never scanned.
        // Try a bare file delete as best effort.
        if (uri == null) {
            return@withContext attemptBareFileDelete(filePath)
        }

        // Android 11+ (API 30+): preemptively ask the OS to bundle a
        // delete confirmation. The system handles deleting both the row
        // and the underlying file once the user confirms.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@withContext try {
                val pi = MediaStore.createDeleteRequest(resolver, listOf(uri))
                DeleteAttempt.NeedsConsent(pi.intentSender)
            } catch (t: Throwable) {
                DeleteAttempt.Done(false, "createDeleteRequest: ${t.message}")
            }
        }

        // API 29 (Android 10): MediaStore.delete throws
        // RecoverableSecurityException for files the app doesn't own. Catch
        // it and surface the IntentSender from the recoverable info.
        try {
            val rows = resolver.delete(uri, null, null)
            if (rows > 0) {
                // Best-effort file delete on top, since direct delete worked.
                try { File(filePath).delete() } catch (_: Throwable) {}
                return@withContext DeleteAttempt.Done(true)
            }
            return@withContext DeleteAttempt.Done(false, "delete returned 0 rows")
        } catch (rse: RecoverableSecurityException) {
            return@withContext DeleteAttempt.NeedsConsent(
                rse.userAction.actionIntent.intentSender
            )
        } catch (t: Throwable) {
            return@withContext DeleteAttempt.Done(false, "mediastore: ${t.message}")
        }
    }

    /**
     * Push #62: batch variant. Builds a single `createDeleteRequest`
     * over multiple URIs so the user sees ONE system dialog covering
     * the whole album/group. Returns `NeedsConsent` for the bundled
     * intent on Android 11+; older devices fall back to per-file
     * deletes via the single-file path (caller handles the loop).
     *
     * Filepaths whose MediaStore URI can't be resolved are skipped
     * silently — they'll be handled by the LibraryScanner stale-file
     * filter at the next rescan.
     */
    suspend fun attemptBatch(ctx: Context, filePaths: List<String>): DeleteAttempt = withContext(Dispatchers.IO) {
        if (filePaths.isEmpty()) return@withContext DeleteAttempt.Done(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // No native batch on API < 30. Return Done(false) so the
            // caller knows to fall back to a per-file loop.
            return@withContext DeleteAttempt.Done(false, "batch_unsupported_pre_api_30")
        }
        val resolver = ctx.contentResolver
        val uris = filePaths.mapNotNull { lookupUri(ctx, it) }
        if (uris.isEmpty()) return@withContext DeleteAttempt.Done(false, "no_uris_resolved")
        return@withContext try {
            val pi = MediaStore.createDeleteRequest(resolver, uris)
            DeleteAttempt.NeedsConsent(pi.intentSender)
        } catch (t: Throwable) {
            DeleteAttempt.Done(false, "createDeleteRequest: ${t.message}")
        }
    }

    /**
     * Called by the caller after the user confirms (or declines) the
     * system delete dialog. When granted, MediaStore has already removed
     * the row + the file. Returns true if the file is verifiably gone.
     */
    suspend fun onConsentResult(filePath: String, granted: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!granted) return@withContext false
        // The OS already deleted both the row and file via createDeleteRequest.
        // Belt-and-suspenders: if the file somehow still exists, try to delete it.
        val f = File(filePath)
        if (f.exists()) {
            runCatching { f.delete() }
        }
        !f.exists()
    }

    /** Fallback for the no-MediaStore-row case (probably already deleted externally). */
    private fun attemptBareFileDelete(filePath: String): DeleteAttempt {
        val f = File(filePath)
        if (!f.exists()) return DeleteAttempt.Done(true)  // already gone
        return try {
            DeleteAttempt.Done(f.delete())
        } catch (t: Throwable) {
            DeleteAttempt.Done(false, "fs: ${t.message}")
        }
    }
}
