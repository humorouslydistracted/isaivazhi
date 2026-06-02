package com.isaivazhi.app.ui

/**
 * Whether a song filepath should show as embedded in list/mini-player UI.
 * Avoids treating every song as embedded when [embeddedFilepaths] is still
 * empty on cold start, while the library truly has zero vectors.
 */
fun songHasEmbedding(
    filePath: String?,
    embeddingsRowCount: Int?,
    embeddedFilepaths: Set<String>,
): Boolean {
    if (filePath.isNullOrBlank()) return false
    when (embeddingsRowCount) {
        null -> {
            // Row count not loaded yet — avoid flashing red dots on every song.
            if (embeddedFilepaths.isEmpty()) return true
            return filePath in embeddedFilepaths
        }
        0 -> return false
        else -> {
            if (embeddedFilepaths.isEmpty()) return true
            return filePath in embeddedFilepaths
        }
    }
}
