package com.isaivazhi.app.engine

import kotlin.math.abs
import kotlin.math.pow

/**
 * User-facing playback result. The reason playback ended is deliberately
 * separate: transport controls describe navigation, not preference.
 */
enum class PlaybackOutcome(val label: String, val evidenceUnits: Float) {
    EARLY("Early", 0f),
    PARTIAL("Partial", 0.5f),
    LISTEN("Listen", 1f),
    STRONG("Strong", 1f),
}

enum class PlaybackEndReason(val label: String) {
    NEXT("Next"),
    PREVIOUS("Previous"),
    SONG_TAP("Song selected"),
    QUEUE_TAP("Queue selected"),
    AUTO_ADVANCE("Finished"),
    RECOVERY("Recovered"),
    QUEUE_END("Queue ended"),
    DISMISS("Player closed"),
    UNKNOWN("Playback ended");

    companion object {
        fun fromSource(source: String): PlaybackEndReason {
            val normalized = source.lowercase()
            return when {
                normalized.contains("next") || normalized == "neutral_skip" -> NEXT
                normalized.contains("prev") || normalized == "history_prev" -> PREVIOUS
                normalized.contains("song_tap") || normalized.contains("manual_tap") -> SONG_TAP
                normalized.contains("queue_tap") || normalized.contains("user_jump") -> QUEUE_TAP
                normalized.contains("auto_advance") || normalized.contains("queue_update") -> AUTO_ADVANCE
                normalized.contains("background") || normalized.contains("recovery") ||
                    normalized.contains("ledger") || normalized.contains("cold_start") -> RECOVERY
                normalized.contains("queue_end") -> QUEUE_END
                normalized.contains("dismiss") -> DISMISS
                else -> UNKNOWN
            }
        }
    }
}

enum class TasteEventKind {
    PLAYBACK,
    FAVORITE,
    UNFAVORITE,
    DISLIKE,
    UNDISLIKE,
    QUEUE_REMOVE,
}

object UnifiedTasteContract {
    const val EARLY_REJECTION_THRESHOLD = 0.20f
    const val PARTIAL_THRESHOLD = 0.30f
    const val LISTEN_THRESHOLD = 0.50f
    const val STRONG_THRESHOLD = 0.70f
    const val PARTIAL_STRENGTH = 0.50f
    const val STRONG_AVOIDANCE_REPAIR = 0.50f
    const val QUEUE_REMOVAL_AVOIDANCE = 0.50f

    const val TASTE_RANKING_SHARE = TasteEngine.DEFAULT_TASTE_INFLUENCE
    const val AUDIO_RANKING_SHARE = 1f - TASTE_RANKING_SHARE
    const val TASTE_SCORE_NORMALIZER = 4f
    const val NEGATIVE_BLOCK_THRESHOLD = 0.25f

    fun outcomeFor(fraction: Float): PlaybackOutcome {
        val value = fraction.coerceIn(0f, 1f)
        return when {
            value < PARTIAL_THRESHOLD -> PlaybackOutcome.EARLY
            value < LISTEN_THRESHOLD -> PlaybackOutcome.PARTIAL
            value < STRONG_THRESHOLD -> PlaybackOutcome.LISTEN
            else -> PlaybackOutcome.STRONG
        }
    }

    fun positiveContribution(fraction: Float): Float {
        val value = fraction.coerceIn(0f, 1f)
        return when (outcomeFor(value)) {
            PlaybackOutcome.EARLY -> 0f
            PlaybackOutcome.PARTIAL -> value * PARTIAL_STRENGTH
            PlaybackOutcome.LISTEN,
            PlaybackOutcome.STRONG -> value
        }
    }

    fun isUserInitiated(reason: PlaybackEndReason): Boolean =
        reason == PlaybackEndReason.NEXT ||
            reason == PlaybackEndReason.PREVIOUS ||
            reason == PlaybackEndReason.SONG_TAP ||
            reason == PlaybackEndReason.QUEUE_TAP

    /**
     * Cumulative early-rejection pressure:
     * 1st=0, 2nd=0.2, 3rd=0.5, then +0.5 for every additional rejection.
     */
    fun earlyRejectionPenalty(count: Int): Float = when {
        count < 2 -> 0f
        count == 2 -> 0.2f
        else -> 0.5f + (count - 3).coerceAtLeast(0) * 0.5f
    }

    fun boundedTasteAffinity(totalScore: Float): Float =
        (totalScore / TASTE_SCORE_NORMALIZER).coerceIn(-1f, 1f)

    fun tasteAdjustment(
        totalScore: Float,
        negativeGuard: Float,
        tasteInfluence: Float = TasteEngine.DEFAULT_TASTE_INFLUENCE,
    ): Float {
        val affinity = boundedTasteAffinity(totalScore)
        val guarded = if (affinity < 0f) affinity * negativeGuard.coerceIn(0f, 1f) else affinity
        return tasteInfluence.coerceIn(0f, TasteEngine.MAX_TASTE_INFLUENCE) * guarded
    }

    fun finalRelevance(
        audioRelevance: Float,
        totalScore: Float,
        negativeGuard: Float,
        tasteInfluence: Float = TasteEngine.DEFAULT_TASTE_INFLUENCE,
    ): Float {
        val tasteWeight = tasteInfluence.coerceIn(0f, TasteEngine.MAX_TASTE_INFLUENCE)
        return (1f - tasteWeight) * audioRelevance +
            tasteAdjustment(totalScore, negativeGuard, tasteWeight)
    }

    fun isLearnedNegativeBlocked(totalScore: Float, negativeGuard: Float): Boolean {
        if (totalScore >= 0f) return false
        return abs(totalScore) * negativeGuard.coerceIn(0f, 1f) >= NEGATIVE_BLOCK_THRESHOLD
    }
}

internal fun decayedLearnedValue(
    value: Float,
    updatedAt: Long,
    fallbackAt: Long,
    now: Long,
): Float {
    if (value == 0f) return 0f
    val reference = when {
        updatedAt > 0L -> updatedAt
        fallbackAt > 0L -> fallbackAt
        else -> now - 90L * TasteEngine.PROFILE_DAY_MS
    }
    val days = (now - reference).coerceAtLeast(0L).toFloat() / TasteEngine.PROFILE_DAY_MS
    val multiplier = 0.5f.pow(days / TasteEngine.PROFILE_HALF_LIFE_DAYS)
        .coerceIn(0f, 1f)
    return value * multiplier
}
