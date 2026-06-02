package com.isaivazhi.app.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Pure recommendation-policy helpers (Up Next exclusions). Testable
 * without Android or DataStore.
 */
object RecommendationPolicy {

    const val HARD_EXCLUDE_SHARE = 0.18f
    const val HARD_EXCLUDE_FLOOR = 1.5f
    private const val SOFT_NEGATIVE_SCALE = 0.5f

    /**
     * Top [effectiveShare] of negative direct scores with |score| >= [effectiveFloor].
     * At strength 0 returns empty; at 1 uses full share/floor constants.
     */
    fun hardBlockedFilenames(
        directScoresByFilename: Map<String, Float>,
        negativeStrength: Float,
        share: Float = HARD_EXCLUDE_SHARE,
        floor: Float = HARD_EXCLUDE_FLOOR,
    ): Set<String> {
        val s = negativeStrength.coerceIn(0f, 1f)
        if (s <= 0f || directScoresByFilename.isEmpty()) return emptySet()
        val effectiveShare = share * s
        val effectiveFloor = floor * s
        val negativeStrongRows = directScoresByFilename.entries
            .filter { it.value < 0f }
            .sortedByDescending { abs(it.value) }
        if (negativeStrongRows.isEmpty()) return emptySet()
        val cap = max(1, ceil(negativeStrongRows.size * effectiveShare).toInt())
        val out = HashSet<String>()
        for (i in 0 until min(cap, negativeStrongRows.size)) {
            if (abs(negativeStrongRows[i].value) >= effectiveFloor) {
                out += negativeStrongRows[i].key
            }
        }
        return out
    }

    /** Songs with directScore below -[0.5 × negativeStrength]. */
    fun softExcludedFilenames(
        directScoresByFilename: Map<String, Float>,
        negativeStrength: Float,
    ): Set<String> {
        val threshold = SOFT_NEGATIVE_SCALE * negativeStrength.coerceIn(0f, 1f)
        if (threshold <= 0f) return emptySet()
        return directScoresByFilename
            .filter { (_, score) -> score < -threshold }
            .keys
    }

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
}
