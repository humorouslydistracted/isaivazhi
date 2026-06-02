package com.isaivazhi.app.engine

import com.isaivazhi.app.EmbeddingWindowConfig

/** Kotlin helpers for embedding split count (3, 5, 7). */
object EmbeddingSplitCount {
    val allowed: List<Int> = EmbeddingWindowConfig.ALLOWED_SPLIT_COUNTS.toList()

    fun normalize(value: Int): Int = EmbeddingWindowConfig.normalizeSplitCount(value)

    fun positionsLabel(splitCount: Int): String = EmbeddingWindowConfig.positionsLabel(splitCount)

    fun timeMultiplier(splitCount: Int): Float = normalize(splitCount) / 3f
}
