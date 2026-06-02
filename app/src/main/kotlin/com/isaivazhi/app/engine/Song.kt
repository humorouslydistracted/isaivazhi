package com.isaivazhi.app.engine

/**
 * Plain Kotlin data class for a song row in the library. Maps directly to
 * what MediaScanHelper produced in the JS app, but with typed fields and
 * native Kotlin nullability.
 */
data class Song(
    val id: Int,
    val filename: String,
    val title: String,
    val artist: String,
    val album: String,
    val filePath: String?,        // null if file was deleted but metadata kept
    val artPath: String? = null,
    val dateModified: Long = 0L,
    val durationMs: Long = 0L,
    val contentHash: String? = null,
    val hasEmbedding: Boolean = false,
    val embeddingIndex: Int? = null,
    val disliked: Boolean = false,
)
