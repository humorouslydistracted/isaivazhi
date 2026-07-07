package com.isaivazhi.app.engine

/**
 * Tuning + blend values captured when an Up Next tail was last built.
 * Display uses this snapshot so slider moves do not misrepresent the active queue.
 */
data class QueueBuildSnapshot(
    val blendInfo: RecommendationBlendInfo,
    val adventurous: Float,
    val tasteInfluence: Float,
    val negativeStrength: Float,
    val negativeFilteredCount: Int = 0,
)
