package com.isaivazhi.app.engine

data class CachedRecommendationTail(
    val seedFilename: String,
    val revision: Long,
    val tail: List<Song>,
    val blendInfo: RecommendationBlendInfo? = null,
) {
    fun isUsableFor(currentSeedFilename: String?, currentRevision: Long): Boolean =
        tail.isNotEmpty() &&
            currentSeedFilename == seedFilename &&
            currentRevision == revision
}
