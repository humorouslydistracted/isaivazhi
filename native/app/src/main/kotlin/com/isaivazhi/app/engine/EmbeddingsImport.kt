package com.isaivazhi.app.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Handles importing a user-provided `local_embeddings.json` file into the
 * app's external-files directory so EmbeddingDbManager.migrateFromLegacyIfNeeded
 * picks it up. Same on-disk filename + shape as the Capacitor app's portable
 * mirror; the Colab and laptop embedding generators write this exact format.
 *
 * Flow:
 *   1. UI invokes ActivityResultContracts.OpenDocument with mime application/json
 *   2. Returned Uri is passed to `importFromUri(ctx, uri)`
 *   3. We copy the URI content to <dataDir>/local_embeddings.json
 *   4. Caller invokes embeddingDb.migrateFromLegacy() to ingest it into SQLite
 */
object EmbeddingsImport {

    private const val TARGET_FILENAME = "local_embeddings.json"

    data class ImportResult(
        val ok: Boolean,
        val bytesCopied: Long,
        val targetPath: String,
        val error: String? = null,
    )

    suspend fun importFromUri(ctx: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val target = targetFile(ctx)
        try {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // One-shot read access is still valid for this session.
            }
            val tmp = File(target.parentFile, "$TARGET_FILENAME.tmp")
            var bytes = 0L
            val input = ctx.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult(false, 0L, target.absolutePath, "cannot open file — try copying the JSON into Downloads and pick it again")
            input.use { stream ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        bytes += n
                    }
                    output.fd.sync()
                }
            }
            if (bytes <= 0L) {
                return@withContext ImportResult(false, 0L, target.absolutePath, "file is empty")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                return@withContext ImportResult(false, bytes, target.absolutePath, "rename failed")
            }
            android.util.Log.i("EmbeddingsImport", "Imported $bytes bytes to $target")
            ImportResult(true, bytes, target.absolutePath, null)
        } catch (t: Throwable) {
            ImportResult(false, 0L, target.absolutePath, t.message ?: t.javaClass.simpleName)
        }
    }

    fun hasPreviousImport(ctx: Context): Boolean = backupFile(ctx).exists() && backupFile(ctx).length() > 0

    /** App-private path where exportLegacyMirror writes the portable backup. */
    fun backupFile(ctx: Context): File = targetFile(ctx)

    data class CopyResult(val ok: Boolean, val bytesCopied: Long, val error: String? = null)

    /** Copy the on-device backup to a user-picked location (Save as). */
    suspend fun copyBackupToUri(ctx: Context, uri: Uri): CopyResult = withContext(Dispatchers.IO) {
        val source = backupFile(ctx)
        if (!source.exists() || source.length() <= 0L) {
            return@withContext CopyResult(false, 0L, "no backup file — export first")
        }
        try {
            var bytes = 0L
            ctx.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        bytes += n
                    }
                }
            } ?: return@withContext CopyResult(false, 0L, "cannot write to chosen location")
            CopyResult(true, bytes, null)
        } catch (t: Throwable) {
            CopyResult(false, 0L, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun targetFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, TARGET_FILENAME)
    }
}
