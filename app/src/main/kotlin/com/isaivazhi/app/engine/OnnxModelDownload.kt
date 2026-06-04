package com.isaivazhi.app.engine

import android.content.Context
import com.isaivazhi.app.EmbeddingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads on-device CLAP ONNX assets from the app's GitHub Release
 * (same source as [scripts/fetch_onnx_assets.sh]) into internal storage
 * where [com.isaivazhi.app.EmbeddingService] loads them.
 */
object OnnxModelDownload {

    const val RELEASE_TAG = "onnx-model-v1"
    private const val REPO = "humorouslydistracted/isaivazhi"

    val FILE_NAMES: List<String> = listOf(
        "clap_audio_encoder.onnx",
        "clap_audio_encoder.onnx.data",
    )

    private val baseUrl: String
        get() = "https://github.com/$REPO/releases/download/$RELEASE_TAG"

    fun modelDir(context: Context): File =
        File(context.filesDir, EmbeddingService.ONNX_MODEL_DIR_NAME)

    fun areModelsReady(context: Context): Boolean =
        EmbeddingService.areModelFilesReady(context)

    data class Progress(
        val fileName: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val bytesDownloaded: Long,
        val bytesTotal: Long?,
    )

    data class DownloadState(
        val inProgress: Boolean = false,
        val progress: Progress? = null,
        val error: String? = null,
        val completed: Boolean = false,
    )

    suspend fun download(
        context: Context,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = modelDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext Result.failure(
                IllegalStateException("Could not create model directory"),
            )
        }
        FILE_NAMES.forEachIndexed { index, name ->
            val dest = File(dir, name)
            val url = URL("$baseUrl/$name")
            runCatching {
                downloadFile(url, dest) { downloaded, total ->
                    onProgress(
                        Progress(
                            fileName = name,
                            fileIndex = index,
                            totalFiles = FILE_NAMES.size,
                            bytesDownloaded = downloaded,
                            bytesTotal = total,
                        ),
                    )
                }
            }.onFailure { t ->
                dest.delete()
                File(dest.absolutePath + ".partial").delete()
                return@withContext Result.failure(t)
            }
        }
        if (!areModelsReady(context)) {
            return@withContext Result.failure(
                IllegalStateException("Download finished but model files are incomplete"),
            )
        }
        Result.success(Unit)
    }

    private fun downloadFile(
        url: URL,
        dest: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ) {
        val partial = File(dest.absolutePath + ".partial")
        if (dest.exists() && dest.length() > 0L) {
            onProgress(dest.length(), dest.length())
            return
        }
        partial.delete()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code for ${url.path}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0L }
            conn.inputStream.use { input ->
                FileOutputStream(partial).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    fun formatProgressLine(progress: Progress?): String {
        if (progress == null) return "Preparing download…"
        val pct = progress.bytesTotal?.takeIf { it > 0L }?.let { total ->
            ((progress.bytesDownloaded * 100) / total).toInt().coerceIn(0, 100)
        }
        val size = progress.bytesTotal?.let { formatBytes(it) } ?: "?"
        val done = formatBytes(progress.bytesDownloaded)
        return if (pct != null) {
            "Downloading ${progress.fileName} (${progress.fileIndex + 1}/${progress.totalFiles}) — $pct% ($done / $size)"
        } else {
            "Downloading ${progress.fileName} (${progress.fileIndex + 1}/${progress.totalFiles}) — $done"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.1f MB".format(kb / 1024.0)
    }
}
