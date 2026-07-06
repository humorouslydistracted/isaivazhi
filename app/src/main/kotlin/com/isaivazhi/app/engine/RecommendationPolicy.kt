package com.isaivazhi.app.engine

/**
 * Pure recommendation-policy helpers (Up Next exclusions). Testable
 * without Android or DataStore.
 */
object RecommendationPolicy {

    const val HARD_EXCLUDE_SHARE = 0.18f
    const val HARD_EXCLUDE_FLOOR = 1.5f
    /**
     * Learned negatives are blocked when their guarded magnitude reaches the
     * unified threshold. A higher Negative Guard can only add blocks.
     */
    fun hardBlockedFilenames(
        directScoresByFilename: Map<String, Float>,
        negativeStrength: Float,
        share: Float = HARD_EXCLUDE_SHARE,
        floor: Float = HARD_EXCLUDE_FLOOR,
    ): Set<String> {
        if (negativeStrength <= 0f || directScoresByFilename.isEmpty()) return emptySet()
        return directScoresByFilename
            .filterValues {
                UnifiedTasteContract.isLearnedNegativeBlocked(it, negativeStrength)
            }
            .keys
    }

    /** Songs with directScore below -[0.5 × negativeStrength]. */
    fun softExcludedFilenames(
        directScoresByFilename: Map<String, Float>,
        negativeStrength: Float,
    ): Set<String> = emptySet()

    fun unionExcludes(
        hardBlocked: Set<String>,
        softExcluded: Set<String>,
        disliked: Set<String>,
    ): Set<String> = hardBlocked + softExcluded + disliked

    /** Drop songs excluded by current Up Next policy (cache refresh, soft refresh). */
    fun filterSongsForUpNext(songs: List<Song>, policyExcludes: Set<String>): List<Song> {
        if (policyExcludes.isEmpty()) return songs
        return songs.filter { it.filename !in policyExcludes }
    }

    /**
     * Drop songs that are still inside the recommendation cooldown window.
     * [allowedFilenames] lets explicit user choices such as Play Next survive
     * final queue replacement even when they are also on cooldown.
     */
    fun filterSongsForRecommendationCooldown(
        songs: List<Song>,
        cooldownFilenames: Set<String>,
        allowedFilenames: Set<String> = emptySet(),
    ): List<Song> {
        if (cooldownFilenames.isEmpty()) return songs
        return songs.filter { it.filename !in cooldownFilenames || it.filename in allowedFilenames }
    }
}
