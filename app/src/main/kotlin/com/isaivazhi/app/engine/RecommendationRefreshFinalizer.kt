package com.isaivazhi.app.engine

object RecommendationRefreshFinalizer {

    data class HardRefreshResult(
        val policyFilteredTail: List<Song>,
        val cooldownFilteredTail: List<Song>,
        val finalTail: List<Song>,
        val finalUpcoming: List<Song>,
    )

    data class SoftRefreshResult(
        val frozenZone: List<Song>,
        val stableZone: List<Song>,
        val fluidZone: List<Song>,
    ) {
        val finalTail: List<Song>
            get() = frozenZone + stableZone + fluidZone
    }

    fun finalizeHardRefresh(
        candidateTail: List<Song>,
        playNextSongs: List<Song>,
        playNextFilenames: Set<String>,
        currentFilename: String,
        policyExcludes: Set<String>,
        cooldownFilenames: Set<String>,
    ): HardRefreshResult {
        val policyFiltered = RecommendationPolicy.filterSongsForUpNext(candidateTail, policyExcludes)
        val cooldownFiltered = RecommendationPolicy.filterSongsForRecommendationCooldown(
            songs = policyFiltered,
            cooldownFilenames = cooldownFilenames,
        )
        val blockedTailFilenames = playNextFilenames + currentFilename
        val finalTail = cooldownFiltered
            .filter { it.filename !in blockedTailFilenames }
            .distinctBy { it.filename }
        val finalUpcoming = (playNextSongs + finalTail).distinctBy { it.filename }
        return HardRefreshResult(
            policyFilteredTail = policyFiltered,
            cooldownFilteredTail = cooldownFiltered,
            finalTail = finalTail,
            finalUpcoming = finalUpcoming,
        )
    }

    fun finalizeSoftRefresh(
        frozenZone: List<Song>,
        stableCandidates: List<Song>,
        fluidCandidates: List<Song>,
        currentFilename: String,
        policyExcludes: Set<String>,
        cooldownFilenames: Set<String>,
    ): SoftRefreshResult {
        // Bugfix: the frozen zone was previously kept verbatim, so a song
        // that turned strongly-negative / disliked while sitting in the
        // top-5 of Up Next would survive every soft refresh. Apply the
        // negative policy here too — the "don't yank the user's immediate
        // picks" intent never meant preserving songs the user rejected.
        val frozenFiltered = RecommendationPolicy.filterSongsForUpNext(frozenZone, policyExcludes)
        val stableZone = RecommendationPolicy.filterSongsForUpNext(stableCandidates, policyExcludes)
            .distinctBy { it.filename }
        val fluidPolicyFiltered = RecommendationPolicy.filterSongsForUpNext(fluidCandidates, policyExcludes)
        val fluidCooldownFiltered = RecommendationPolicy.filterSongsForRecommendationCooldown(
            songs = fluidPolicyFiltered,
            cooldownFilenames = cooldownFilenames,
        )
        val blockedFluidFilenames = (frozenFiltered + stableZone).map { it.filename }.toSet() + currentFilename
        val fluidZone = fluidCooldownFiltered
            .filter { it.filename !in blockedFluidFilenames }
            .distinctBy { it.filename }
        return SoftRefreshResult(
            frozenZone = frozenFiltered,
            stableZone = stableZone,
            fluidZone = fluidZone,
        )
    }
}
