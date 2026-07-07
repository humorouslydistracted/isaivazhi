package com.isaivazhi.app.engine

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecommendationInfluence {
    CURRENT,
    SESSION,
    PROFILE,
    DIVERSITY,
    NONE,
}

enum class QueueItemOrigin(val label: String) {
    AI("AI"),
    PLAY_NEXT("Play Next"),
    USER_CHOICE("You chose"),
    ALBUM("Album order"),
    SHUFFLE("Shuffle"),
    RESTORED("Restored queue"),
    UNKNOWN("Queue"),
}

data class RecommendationExplanation(
    val queueGenerationId: String = UUID.randomUUID().toString(),
    val itemId: String = UUID.randomUUID().toString(),
    val filename: String,
    val origin: QueueItemOrigin = QueueItemOrigin.AI,
    val currentContribution: Float = 0f,
    val sessionContribution: Float = 0f,
    val profileContribution: Float = 0f,
    val directScore: Float = 0f,
    val similarityScore: Float = 0f,
    val totalScore: Float = 0f,
    val tasteAdjustment: Float = 0f,
    val relevanceRank: Int = 0,
    val finalRank: Int = 0,
    val negativeFilteredCount: Int = 0,
) {
    val dominantInfluence: RecommendationInfluence
        get() {
            if (relevanceRank > 0 && finalRank > 0 && relevanceRank - finalRank >= 3) {
                return RecommendationInfluence.DIVERSITY
            }
            val current = kotlin.math.abs(currentContribution)
            val session = kotlin.math.abs(sessionContribution)
            val profile = kotlin.math.abs(profileContribution)
            val best = maxOf(current, session, profile)
            if (best <= 0f) return RecommendationInfluence.NONE
            return when (best) {
                current -> RecommendationInfluence.CURRENT
                session -> RecommendationInfluence.SESSION
                else -> RecommendationInfluence.PROFILE
            }
        }

    val isDiversePick: Boolean
        get() = relevanceRank > 0 && finalRank > 0 && relevanceRank - finalRank >= 3

    fun normalizedBlendContributions(): Triple<Int, Int, Int> {
        val current = kotlin.math.abs(currentContribution)
        val session = kotlin.math.abs(sessionContribution)
        val profile = kotlin.math.abs(profileContribution)
        val total = current + session + profile
        if (total <= 0f) return Triple(0, 0, 0)
        val c = (current / total * 100f).toInt()
        val s = (session / total * 100f).toInt()
        return Triple(c, s, (100 - c - s).coerceAtLeast(0))
    }
}

data class ExplainedRecommendationResult(
    val songs: List<Song>,
    val explanations: List<RecommendationExplanation>,
    val blendInfo: RecommendationBlendInfo?,
)

data class RecommendationCandidateTrace(
    val filename: String,
    val audioSimilarity: Float,
    val overallScore: Float,
    val tier: String,
    val tasteAffinity: Float,
    val guardDecision: String,
    val preDiversityRank: Int,
    val diversityScore: Float?,
    val finalRank: Int?,
    val rejectionReason: String?,
)

data class RecommendationGenerationTrace(
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String,
    val tasteInfluence: Float,
    val negativeGuard: Float,
    val candidateCount: Int,
    val selectedCount: Int,
    val candidates: List<RecommendationCandidateTrace>,
)

data class SessionBlendInterval(
    val startedAt: Long,
    val endedAt: Long? = null,
    val currentPercent: Int,
    val sessionPercent: Int,
    val profilePercent: Int,
    val playbackResults: Int = 0,
    val reason: String,
)

/**
 * Process-lifetime blend history. Queue explanation persistence is handled by
 * AppPreferences; these timing intervals intentionally reset with the process.
 */
class RecommendationExplainabilityEngine {
    private val _blendIntervals = MutableStateFlow<List<SessionBlendInterval>>(emptyList())
    val blendIntervals: StateFlow<List<SessionBlendInterval>> = _blendIntervals.asStateFlow()

    private val _queueExplanations =
        MutableStateFlow<List<RecommendationExplanation>>(emptyList())
    val queueExplanations: StateFlow<List<RecommendationExplanation>> =
        _queueExplanations.asStateFlow()

    private val _generationTraces =
        MutableStateFlow<List<RecommendationGenerationTrace>>(emptyList())
    val generationTraces: StateFlow<List<RecommendationGenerationTrace>> =
        _generationTraces.asStateFlow()

    @Synchronized
    fun recordGeneration(trace: RecommendationGenerationTrace) {
        _generationTraces.value = (listOf(trace) + _generationTraces.value).take(10)
    }

    @Synchronized
    fun recordBlend(weights: Recommender.BlendWeights, reason: String, now: Long = System.currentTimeMillis()) {
        val total = (weights.wCurrent + weights.wSession + weights.wProfile).coerceAtLeast(0.0001f)
        val current = (weights.wCurrent / total * 100f).toInt()
        val session = (weights.wSession / total * 100f).toInt()
        val profile = (100 - current - session).coerceAtLeast(0)
        val latest = _blendIntervals.value.firstOrNull()
        if (latest != null &&
            latest.currentPercent == current &&
            latest.sessionPercent == session &&
            latest.profilePercent == profile
        ) return

        val closed = latest?.copy(endedAt = now)
        val tail = if (closed == null) _blendIntervals.value else
            listOf(closed) + _blendIntervals.value.drop(1)
        val next = SessionBlendInterval(
            startedAt = now,
            currentPercent = current,
            sessionPercent = session,
            profilePercent = profile,
            reason = reason,
        )
        _blendIntervals.value = (listOf(next) + tail).take(20)
    }

    @Synchronized
    fun recordPlaybackResult() {
        val current = _blendIntervals.value.firstOrNull() ?: return
        _blendIntervals.value = listOf(current.copy(playbackResults = current.playbackResults + 1)) +
            _blendIntervals.value.drop(1)
    }

    fun setQueueExplanations(value: List<RecommendationExplanation>) {
        _queueExplanations.value = value
    }

    fun explanationFor(filename: String, occurrence: Int = 0): RecommendationExplanation? =
        _queueExplanations.value.filter { it.filename == filename }.getOrNull(occurrence)

    fun resetSession() {
        _blendIntervals.value = emptyList()
        _generationTraces.value = emptyList()
    }
}
