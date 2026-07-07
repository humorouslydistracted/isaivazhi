package com.isaivazhi.app.engine

/**
 * Pure recommendation-policy helpers (Up Next exclusions). Testable
 * without Android or DataStore.
 */
object RecommendationPolicy {

    /**
     * Learned negatives are blocked when their guarded magnitude reaches the
     * unified threshold. A higher Negative Guard can only add blocks.
     */
    fun hardBlockedFilenames(
        overallScoresByFilename: Map<String, Float>,
        negativeStrength: Float,
    ): Set<String> {
        if (negativeStrength <= 0f || overallScoresByFilename.isEmpty()) return emptySet()
        return overallScoresByFilename
            .filterValues {
                UnifiedTasteContract.isLearnedNegativeBlocked(it, negativeStrength)
            }
            .keys
    }

    /** Union of all Up Next exclusion sets passed into [Recommender]. */
    fun unionExcludes(
        hardBlockedFilenames: Set<String>,
        softExcludedFilenames: Set<String> = emptySet(),
        dislikedFilenames: Set<String> = emptySet(),
    ): Set<String> = hardBlockedFilenames + softExcludedFilenames + dislikedFilenames

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
