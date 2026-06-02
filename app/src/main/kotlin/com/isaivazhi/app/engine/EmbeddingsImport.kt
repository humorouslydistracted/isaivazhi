package com.isaivazhi.app.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.isaivazhi.app.EmbeddingDbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Imports a user-provided embeddings backup into app external storage.
 *
 * Preferred: `isaivazhi_embeddings.bin` (IVZ1 — fast).
 * Legacy: `local_embeddings.json` (slow; still supported).
 */
object EmbeddingsImport {

    const val BIN_FILENAME = EmbeddingDbManager.EMBEDDINGS_BIN
    private const val LEGACY_JSON_FILENAME = "local_embeddings.json"
    private val IVZ_MAGIC = byteArrayOf('I'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), '1'.code.toByte())

    enum class PortableFormat { IVZ, LEGACY_JSON, UNKNOWN }

    data class ImportResult(
        val ok: Boolean,
        val bytesCopied: Long,
        val targetPath: String,
        val format: PortableFormat = PortableFormat.UNKNOWN,
        val error: String? = null,
    )

    suspend fun importFromUri(ctx: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // One-shot read access is still valid for this session.
            }

            val peek = ByteArray(4)
            var peekLen = 0
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                peekLen = stream.read(peek)
            } ?: return@withContext ImportResult(
                false, 0L, "", PortableFormat.UNKNOWN,
                "cannot open file — try copying into Downloads and pick it again",
            )

            val format = when {
                peekLen >= 4 && peek[0] == IVZ_MAGIC[0] && peek[1] == IVZ_MAGIC[1]
                    && peek[2] == IVZ_MAGIC[2] && peek[3] == IVZ_MAGIC[3] -> PortableFormat.IVZ
                peekLen >= 1 && peek[0] == '{'.code.toByte() -> PortableFormat.LEGACY_JSON
                else -> PortableFormat.UNKNOWN
            }
            if (format == PortableFormat.UNKNOWN) {
                return@withContext ImportResult(
                    false, 0L, "", format,
                    "unrecognized format — use isaivazhi_embeddings.bin or local_embeddings.json",
                )
            }

            val targetName = when (format) {
                PortableFormat.IVZ -> BIN_FILENAME
                PortableFormat.LEGACY_JSON -> LEGACY_JSON_FILENAME
                PortableFormat.UNKNOWN -> BIN_FILENAME
            }
            val target = targetFile(ctx, targetName)
            val tmp = File(target.parentFile, "$targetName.tmp")
            var bytes = 0L

            ctx.contentResolver.openInputStream(uri)?.use { stream ->
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
            } ?: return@withContext ImportResult(false, 0L, target.absolutePath, format, "cannot read file")

            if (bytes <= 0L) {
                return@withContext ImportResult(false, 0L, target.absolutePath, format, "file is empty")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                return@withContext ImportResult(false, bytes, target.absolutePath, format, "rename failed")
            }
            android.util.Log.i("EmbeddingsImport", "Imported $bytes bytes ($format) to $target")
            ImportResult(true, bytes, target.absolutePath, format, null)
        } catch (t: Throwable) {
            ImportResult(false, 0L, "", PortableFormat.UNKNOWN, t.message ?: t.javaClass.simpleName)
        }
    }

    fun hasPreviousImport(ctx: Context): Boolean {
        val bin = targetFile(ctx, BIN_FILENAME)
        val json = targetFile(ctx, LEGACY_JSON_FILENAME)
        return (bin.exists() && bin.length() > 0) || (json.exists() && json.length() > 0)
    }

    /** Primary on-device backup (IVZ). */
    fun backupFile(ctx: Context): File = targetFile(ctx, BIN_FILENAME)

    /** Legacy JSON mirror if present. */
    fun legacyJsonBackupFile(ctx: Context): File = targetFile(ctx, LEGACY_JSON_FILENAME)

    data class CopyResult(val ok: Boolean, val bytesCopied: Long, val error: String? = null)

    suspend fun copyBackupToUri(ctx: Context, uri: Uri, preferBin: Boolean = true): CopyResult =
        withContext(Dispatchers.IO) {
            val source = when {
                preferBin && backupFile(ctx).exists() && backupFile(ctx).length() > 0 -> backupFile(ctx)
                legacyJsonBackupFile(ctx).exists() && legacyJsonBackupFile(ctx).length() > 0 ->
                    legacyJsonBackupFile(ctx)
                backupFile(ctx).exists() && backupFile(ctx).length() > 0 -> backupFile(ctx)
                else -> return@withContext CopyResult(false, 0L, "no backup file — export first")
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

    private fun targetFile(ctx: Context, name: String): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }
}
