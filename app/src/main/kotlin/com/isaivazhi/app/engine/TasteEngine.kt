package com.isaivazhi.app.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.isaivazhi.app.engine.dataStoreLocal
import com.isaivazhi.app.engine.decayedLearnedValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/**
 * Recommender tuning knobs + per-song taste signals.
 *
 * Push #63 — full Capacitor parity for scoring formulas. See plan
 * "Signal Engine Remediation" for the 8 locked design decisions; the
 * relevant ones land in this file:
 *   1. xScore on skip = +0.1 per skip (was 0.7x+1)
 *   2. Plays counter increments only on non-skip (fraction >= 0.50)
 *   3. similarityBoost propagation triggered externally — TasteEngine
 *      exposes `propagateSimilarityBoost` which uses an injected
 *      `neighborLookup` lambda to find the top-10 embedding-nearest
 *      songs and spreads the score delta across them with weights
 *      [0.10, 0.09, …, 0.01], with final similarity clamped to ±0.5.
 *   4. Recency multiplier: `0.5^(daysSince/30)` on listen weights.
 *   5. Hard-block list exposed via `decoratedSignals` for the
 *      Recommender to filter on.
 *   6. Per-song reset clears everything except isFavorite/isDisliked
 *      flags (those live in FavoritesEngine/DislikedEngine anyway).
 */
class TasteEngine(private val appContext: Context) {

    data class Tuning(
        val adventurous: Float = DEFAULT_ADVENTUROUS,
        val tasteInfluence: Float = DEFAULT_TASTE_INFLUENCE,
        val negativeStrength: Float = DEFAULT_NEGATIVE_STRENGTH,
        val manualBlend: Boolean = false,
        val manualCurrentWeight: Float = DEFAULT_MANUAL_CURRENT_WEIGHT,
        val manualSessionWeight: Float = DEFAULT_MANUAL_SESSION_WEIGHT,
    )

    data class TasteSignal(
        val plays: Int = 0,
        val skips: Int = 0,
        val avgFraction: Float = 0f,
        val xScore: Float = 0f,
        val directScore: Float = 0f,
        val similarityBoost: Float = 0f,
        val favoritePrior: Float = 0f,
        val dislikePrior: Float = 0f,
        val isFavorite: Boolean = false,
        val isDisliked: Boolean = false,
        val lastPlayedAt: Long = 0L,  // Push #63: feeds recencyMult.
        val lastUpdatedAt: Long = 0L,
        // Push #73: full-listens (frac >= 0.70) — used to decay
        // dislikePrior asymmetrically. plays still increments at
        // frac >= 0.50 for everything else.
        val fullListens: Int = 0,
        // Push #73: per-field freshness timestamps so xScore and
        // similarityBoost can each decay independently with the same
        // 30-day halflife as listens. Stamped only when the
        // corresponding field actually changes — not on every signal
        // touch, so unrelated activity doesn't reset their freshness.
        val xScoreUpdatedAt: Long = 0L,
        val similarityBoostUpdatedAt: Long = 0L,
        // Unified contract counters. `plays` remains the >=50% count for
        // compatibility and manual-prior decay; encounters is every result.
        val encounters: Int = 0,
        val earlyCount: Int = 0,
        val partialCount: Int = 0,
        val listenCount: Int = 0,
        val strongCount: Int = 0,
        val queueRemovals: Int = 0,
        val avgAllFraction: Float = 0f,
        // Decaying positive evidence accumulated with the same contribution
        // used by session/profile recommendation vectors.
        val positiveEvidence: Float = 0f,
        val positiveEvidenceUpdatedAt: Long = 0L,
        val similarityContributors: List<SimilarityContributor> = emptyList(),
        val earlySkipCount: Int = 0,
        val earlySkipPenalty: Float = 0f,
    )

    data class SimilarityContributor(
        val sourceFilename: String,
        val amount: Float,
        val reason: String,
        val updatedAt: Long,
    )

    data class OutboundSimilarityImpact(
        val targetFilename: String,
        val amount: Float,
        val reason: String,
        val updatedAt: Long,
    )

    data class PropagationSummary(
        val changedSongs: Int = 0,
        val totalDelta: Float = 0f,
    )

    /**
     * Breakdown returned by [computeDirectScore]. Lets the UI render
     * separate progress bars for positive/negative weight and lets the
     * decoration pass classify chips (Mixed, Short-listened, etc.).
     */
    data class DirectScoreBreakdown(
        val positiveWeight: Float,
        val negativeWeight: Float,
        val effectivePositive: Float,
        val effectiveNegative: Float,
        val directScore: Float,
        val score: Float,  // directScore + similarityBoost
        val recencyMult: Float,
    )

    /**
     * One signal row after decoration. The Taste page renders these.
     * `signal` is the raw stored state; `breakdown` is the live
     * recomputation with recencyMult applied.
     */
    data class DecoratedRow(
        val filename: String,
        val signal: TasteSignal,
        val breakdown: DirectScoreBreakdown,
        val isActive: Boolean,
        val inTopPositive30: Boolean,
        val inTopNegative30: Boolean,
        val inTop30: Boolean,
        val isShortListened: Boolean,
        val isMixed: Boolean,
        val isHardRecommendationBlock: Boolean,
        // Push #69 — Capacitor parity (`_buildSuspiciousRecommendationData`):
        // true when the song shows conflicting signals that warrant a
        // user review. Patterns:
        //  • Favorite + repeated skips: user said "love" but keeps skipping
        //  • High avgFraction (≥ 0.7) + very few plays + high xScore: noisy data
        //  • Disliked + repeated complete listens: user said "no" but keeps playing
        val isSuspicious: Boolean,
        val suspiciousReason: String,
    )

    data class DecoratedSignals(
        val rows: Map<String, DecoratedRow>,
        val hardBlockedFilenames: Set<String>,
        val positiveActiveCount: Int,
        val negativeActiveCount: Int,
        val computedAt: Long,
    )

    companion object {
        const val DEFAULT_ADVENTUROUS = 0.4f
        const val DEFAULT_TASTE_INFLUENCE = 0.30f
        const val DEFAULT_NEGATIVE_STRENGTH = 0.5f
        const val DEFAULT_MANUAL_CURRENT_WEIGHT = 0.30f
        const val DEFAULT_MANUAL_SESSION_WEIGHT = 0.40f
        const val MAX_TASTE_INFLUENCE = 0.50f

        // Capacitor parity (engine-state.js): songs played < 50% are SKIPS;
        // strong listens at ≥ 70% decay xScore more aggressively.
        const val SKIP_THRESHOLD = 0.50f
        const val FULL_LISTEN_THRESHOLD = 0.70f

        internal fun isExplicitUserSkip(source: String, fraction: Float): Boolean =
            (source == "next_button" || source == "prev_button") &&
                fraction.coerceIn(0f, 1f) < SKIP_THRESHOLD

        internal fun isEarlyUserRejection(source: String, fraction: Float): Boolean =
            isExplicitUserSkip(source, fraction) &&
                fraction.coerceIn(0f, 1f) < UnifiedTasteContract.EARLY_REJECTION_THRESHOLD

        // Push #63 (Capacitor parity): xScore deltas.
        // engine-state.js: USER_SKIP_NEGATIVE_STEP=0.1, NEG_LISTEN_DECAY=0.5,
        // NEG_X_DELTA=0.5 (queue-remove), NEG_SCORE_MAX=10.
        private const val NEG_SCORE_MAX = 10f

        // Prior coefficients from engine-favorites.js / engine-disliked.js.
        // Push #73: user-tuned, intentionally asymmetric. Originally
        // +2.0 / -3.0 (Capacitor parity). Now +1.5 / -2.5: favorites
        // should fade quickly as the user's listens prove they like it,
        // but dislikes need to stay sticky enough to survive 1-2
        // accidental full plays AND comfortably above the
        // HARD_EXCLUDE_FLOOR (1.5) so a fresh dislike is reliably
        // blocked from recommendations. Halflife decay (every 2 plays)
        // still applies on top.
        const val MANUAL_FAVORITE_PRIOR_BASE = 1.5f
        const val MANUAL_DISLIKE_PRIOR_BASE = 2.5f
        private const val FAVORITE_PRIOR_BASE = MANUAL_FAVORITE_PRIOR_BASE
        private const val DISLIKE_PRIOR_BASE = MANUAL_DISLIKE_PRIOR_BASE
        private const val MANUAL_PRIOR_HALF_LIFE_PLAYS = 2f
        private const val MANUAL_PRIOR_MIN = 0.05f

        // Recency decay (engine-taste.js:378).
        const val PROFILE_HALF_LIFE_DAYS = 30f
        const val PROFILE_DAY_MS = 86_400_000L

        // Negative-listen weight gate (engine-taste.js:391-487).
        private const val NEGATIVE_PLAY_THRESHOLD = 2
        private const val NEGATIVE_FRAC_THRESHOLD = 0.5f

        // Similarity-boost propagation (engine-state.js:17-18, 16).
        const val SIMILARITY_NEIGHBOR_COUNT = 10
        val SIMILARITY_NEIGHBOR_WEIGHTS = floatArrayOf(
            0.10f, 0.09f, 0.08f, 0.07f, 0.06f, 0.05f, 0.04f, 0.03f, 0.02f, 0.01f
        )
        const val SIMILARITY_BOOST_MAX = 0.5f

        /** Pure xScore update for playback events — used by [recordPlaybackEvent] and unit tests. */
        fun nextXScoreAfterPlayback(beforeXScore: Float, fraction: Float, source: String): Float {
            val frac = fraction.coerceIn(0f, 1f)
            return when {
                frac >= UnifiedTasteContract.STRONG_THRESHOLD ->
                    (beforeXScore - UnifiedTasteContract.STRONG_AVOIDANCE_REPAIR)
                        .coerceAtLeast(0f)
                else -> beforeXScore
            }
        }

        // Decoration ranking window (engine-taste.js:644-660).
        private const val TOP_RANK_LIMIT = 30
        private const val MAX_PROCESSED_EVENT_IDS = 1000
    }

    private val KEY_ADV = floatPreferencesKey("taste_adventurous")
    private val KEY_TASTE_INFLUENCE = floatPreferencesKey("taste_influence")
    private val KEY_NEG = floatPreferencesKey("taste_negative_strength")
    private val KEY_BLEND_MANUAL = intPreferencesKey("taste_blend_manual")
    private val KEY_BLEND_CURRENT = floatPreferencesKey("taste_blend_current")
    private val KEY_BLEND_SESSION = floatPreferencesKey("taste_blend_session")
    private val KEY_FAM_LEGACY = floatPreferencesKey("taste_familiarity")
    private val KEY_SIGNALS = stringPreferencesKey("taste_signals_v1_json")
    private val KEY_PROCESSED_EVENT_IDS = stringPreferencesKey("taste_processed_playback_event_ids_v1_json")
    private val KEY_CONTRACT_VERSION = intPreferencesKey("taste_contract_version")

    private val _tuning = MutableStateFlow(Tuning())
    val tuning: StateFlow<Tuning> = _tuning.asStateFlow()

    private val _signals = MutableStateFlow<Map<String, TasteSignal>>(emptyMap())
    val signals: StateFlow<Map<String, TasteSignal>> = _signals.asStateFlow()

    /**
     * Push #66: last recorded playback instance ID. Used by
     * [recordPlaybackEvent] to skip double-counting the same native
     * transition when an event fires twice (e.g. delayed retry from a
     * controller-side reconnect). Capacitor parity:
     * `useCurrentPlaybackDedupe` in engine.js:406.
     */
    @Volatile private var lastCapturedPlaybackInstanceId: Long = 0L

    private val _decorated = MutableStateFlow(
        DecoratedSignals(
            rows = emptyMap(),
            hardBlockedFilenames = emptySet(),
            positiveActiveCount = 0,
            negativeActiveCount = 0,
            computedAt = 0L,
        )
    )
    /**
     * Decorated signals — recomputed whenever `_signals` mutates (and once
     * after initial load). The UI's chip layer and the Recommender's
     * hard-block filter both consume this. Live-recency drift is acceptable
     * for the chip pass (a song's daysSince barely changes minute-to-minute),
     * but on a long-idle app the next mutation will refresh it.
     */
    val decoratedSignals: StateFlow<DecoratedSignals> = _decorated.asStateFlow()

    /**
     * Push #63: injected by AppContainer at construction time. Given a
     * source filename, returns up to [SIMILARITY_NEIGHBOR_COUNT] embedding-
     * nearest filenames (excluding the source). TasteEngine doesn't own
     * the embedding DB — keeping the dependency injected lets the engine
     * stay unit-testable without a full SQLite stack.
     */
    @Volatile var neighborLookup: (suspend (filename: String, k: Int) -> List<String>)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val propagationMutex = Mutex()
    private val readyDeferred = CompletableDeferred<Unit>()
    private var processedPlaybackEventIds: Set<String> = emptySet()

    init {
        scope.launch {
            try {
                val data = appContext.dataStoreLocal.data.first()
                _tuning.value = Tuning(
                    adventurous = data[KEY_ADV] ?: DEFAULT_ADVENTUROUS,
                    tasteInfluence = (data[KEY_TASTE_INFLUENCE] ?: DEFAULT_TASTE_INFLUENCE)
                        .coerceIn(0f, MAX_TASTE_INFLUENCE),
                    negativeStrength = data[KEY_NEG] ?: DEFAULT_NEGATIVE_STRENGTH,
                    manualBlend = data[KEY_BLEND_MANUAL] == 1,
                    manualCurrentWeight = (data[KEY_BLEND_CURRENT]
                        ?: DEFAULT_MANUAL_CURRENT_WEIGHT).coerceIn(0f, 1f),
                    manualSessionWeight = (data[KEY_BLEND_SESSION]
                        ?: DEFAULT_MANUAL_SESSION_WEIGHT).coerceIn(0f, 1f),
                )
                data[KEY_SIGNALS]?.let {
                    _signals.value = parseSignals(it).mapValues { (_, signal) ->
                        normalizeLoadedSignal(signal)
                    }
                }
                data[KEY_PROCESSED_EVENT_IDS]?.let { processedPlaybackEventIds = parseEventIds(it) }
                refreshDecorated()
            } catch (t: Throwable) {
                android.util.Log.w("TasteEngine", "load failed: ${t.message}")
            } finally {
                if (!readyDeferred.isCompleted) readyDeferred.complete(Unit)
            }
        }
    }

    suspend fun awaitReady() {
        readyDeferred.await()
    }

    fun setAdventurous(value: Float) {
        _tuning.value = _tuning.value.copy(adventurous = value.coerceIn(0f, 1f))
        scope.launch { persistTuning() }
    }

    fun setTasteInfluence(value: Float) {
        _tuning.value = _tuning.value.copy(
            tasteInfluence = value.coerceIn(0f, MAX_TASTE_INFLUENCE),
        )
        scope.launch { persistTuning() }
    }

    fun setNegativeStrength(value: Float) {
        _tuning.value = _tuning.value.copy(negativeStrength = value.coerceIn(0f, 1f))
        scope.launch { persistTuning() }
    }

    fun setManualBlend(currentWeight: Float, sessionWeight: Float) {
        val current = currentWeight.coerceIn(0f, 1f)
        val session = sessionWeight.coerceIn(0f, 1f - current)
        _tuning.value = _tuning.value.copy(
            manualBlend = true,
            manualCurrentWeight = current,
            manualSessionWeight = session,
        )
        scope.launch { persistTuning() }
    }

    fun resetBlendToAuto() {
        _tuning.value = _tuning.value.copy(manualBlend = false)
        scope.launch { persistTuning() }
    }

    fun resetAdventurous() = setAdventurous(DEFAULT_ADVENTUROUS)
    fun resetTasteInfluence() = setTasteInfluence(DEFAULT_TASTE_INFLUENCE)
    fun resetNegativeStrength() = setNegativeStrength(DEFAULT_NEGATIVE_STRENGTH)

    /** Canonical overall scores used by recommendation ranking and policy. */
    fun overallScoresByFilename(): Map<String, Float> {
        val dec = _decorated.value
        if (dec.rows.isNotEmpty()) {
            return dec.rows.mapValues { it.value.breakdown.score }
        }
        val now = System.currentTimeMillis()
        return _signals.value.mapValues { (_, sig) ->
            computeUnifiedScore(
                positiveEvidence = sig.positiveEvidence.takeIf { it > 0f }
                    ?: (sig.plays * sig.avgFraction),
                positiveEvidenceUpdatedAt = sig.positiveEvidenceUpdatedAt
                    .takeIf { it > 0L } ?: sig.lastPlayedAt,
                xScore = sig.xScore,
                favoritePrior = sig.favoritePrior,
                dislikePrior = sig.dislikePrior,
                similarityBoost = effectiveSimilarityBoost(sig, now),
                xScoreUpdatedAt = sig.xScoreUpdatedAt,
                similarityBoostUpdatedAt = now,
                lastUpdatedAt = sig.lastUpdatedAt,
                now = now,
            ).score
        }
    }

    fun hardBlockedFilenamesForPolicy(negativeStrength: Float): Set<String> =
        RecommendationPolicy.hardBlockedFilenames(overallScoresByFilename(), negativeStrength)

    fun signalFor(filename: String): TasteSignal = _signals.value[filename] ?: TasteSignal()

    private fun normalizeLoadedSignal(signal: TasteSignal): TasteSignal {
        val favorite = if (signal.isFavorite) MANUAL_FAVORITE_PRIOR_BASE else 0f
        val dislike = if (signal.isDisliked) computeDislikePrior(signal.fullListens) else 0f
        return signal.copy(
            favoritePrior = favorite,
            dislikePrior = dislike,
            similarityBoost = signal.similarityBoost.coerceIn(
                -SIMILARITY_BOOST_MAX,
                SIMILARITY_BOOST_MAX,
            ),
        )
    }

    private fun isPersistentManualContributor(reason: String): Boolean {
        val normalized = reason.lowercase()
        return normalized.contains("favorite") ||
            normalized.contains("dislike") ||
            normalized.contains("manual_prior") ||
            normalized == "reset_engine_reseed"
    }

    private fun effectiveSimilarityBoost(
        signal: TasteSignal,
        now: Long = System.currentTimeMillis(),
    ): Float {
        if (signal.similarityContributors.isEmpty()) {
            return decayedLearnedValue(
                value = signal.similarityBoost,
                updatedAt = signal.similarityBoostUpdatedAt,
                fallbackAt = signal.lastUpdatedAt,
                now = now,
            ).coerceIn(-SIMILARITY_BOOST_MAX, SIMILARITY_BOOST_MAX)
        }
        return signal.similarityContributors.sumOf { contributor ->
            val amount = if (isPersistentManualContributor(contributor.reason)) {
                contributor.amount
            } else {
                decayedLearnedValue(
                    value = contributor.amount,
                    updatedAt = contributor.updatedAt,
                    fallbackAt = signal.lastUpdatedAt,
                    now = now,
                )
            }
            amount.toDouble()
        }.toFloat().coerceIn(-SIMILARITY_BOOST_MAX, SIMILARITY_BOOST_MAX)
    }

    /**
     * One-time v2 rebuild from the durable history ledger. Manual flags and
     * avoidance survive; playback-derived positive evidence and counters are
     * recalculated under the unified thresholds.
     */
    suspend fun migrateUnifiedContractIfNeeded(historyEvents: List<HistoryEngine.Event>) {
        awaitReady()
        val version = appContext.dataStoreLocal.data.first()[KEY_CONTRACT_VERSION] ?: 0
        if (version >= 2) return
        val now = System.currentTimeMillis()
        val grouped = historyEvents.groupBy { it.filename }
        val filenames = _signals.value.keys + grouped.keys
        val rebuilt = HashMap<String, TasteSignal>(filenames.size)

        for (filename in filenames) {
            val existing = signalFor(filename)
            val events = grouped[filename].orEmpty().sortedBy { it.startedAt }
            var positive = 0f
            var positiveAt = 0L
            var encounters = 0
            var early = 0
            var partial = 0
            var listens = 0
            var strong = 0
            var qualifiedSum = 0f
            var allSum = 0f
            for (event in events) {
                val timestamp = event.startedAt.takeIf { it > 0L } ?: now
                if (positive > 0f && positiveAt > 0L) {
                    val days = (timestamp - positiveAt).coerceAtLeast(0L).toFloat() / PROFILE_DAY_MS
                    positive *= 0.5f.pow(days / PROFILE_HALF_LIFE_DAYS)
                }
                val fraction = event.fractionPlayed.coerceIn(0f, 1f)
                val outcome = UnifiedTasteContract.outcomeFor(fraction)
                positive += UnifiedTasteContract.positiveContribution(fraction)
                if (outcome != PlaybackOutcome.EARLY) positiveAt = timestamp
                encounters++
                allSum += fraction
                when (outcome) {
                    PlaybackOutcome.EARLY -> early++
                    PlaybackOutcome.PARTIAL -> partial++
                    PlaybackOutcome.LISTEN -> {
                        listens++
                        qualifiedSum += fraction
                    }
                    PlaybackOutcome.STRONG -> {
                        strong++
                        qualifiedSum += fraction
                    }
                }
            }
            val qualified = listens + strong
            val favoritePrior = if (existing.isFavorite) computeFavoritePrior(qualified) else 0f
            val dislikePrior = if (existing.isDisliked) computeDislikePrior(strong) else 0f
            val base = existing.copy(
                plays = qualified,
                avgFraction = if (qualified > 0) qualifiedSum / qualified else 0f,
                similarityBoost = 0f,
                similarityBoostUpdatedAt = 0L,
                similarityContributors = emptyList(),
                favoritePrior = favoritePrior,
                dislikePrior = dislikePrior,
                encounters = encounters,
                earlyCount = early,
                partialCount = partial,
                listenCount = listens,
                strongCount = strong,
                avgAllFraction = if (encounters > 0) allSum / encounters else 0f,
                positiveEvidence = positive,
                positiveEvidenceUpdatedAt = positiveAt,
                fullListens = strong,
                lastPlayedAt = positiveAt,
                lastUpdatedAt = now,
            )
            val breakdown = computeUnifiedScore(
                positiveEvidence = base.positiveEvidence,
                positiveEvidenceUpdatedAt = base.positiveEvidenceUpdatedAt,
                xScore = base.xScore,
                favoritePrior = base.favoritePrior,
                dislikePrior = base.dislikePrior,
                similarityBoost = 0f,
                xScoreUpdatedAt = base.xScoreUpdatedAt,
                similarityBoostUpdatedAt = 0L,
                lastUpdatedAt = now,
                now = now,
            )
            rebuilt[filename] = base.copy(directScore = breakdown.directScore)
        }
        _signals.value = rebuilt
        refreshDecorated()
        persistSignals()
        appContext.dataStoreLocal.edit { it[KEY_CONTRACT_VERSION] = 2 }

        // Rebuild halos serially under the propagation mutex. Manual priors
        // use persistent contributors; learned evidence uses one-shot spread.
        for ((filename, signal) in rebuilt) {
            if (signal.favoritePrior > 0f || signal.dislikePrior > 0f) {
                syncManualSimilarityHalo(filename, signal, "v2_migration")
            } else if (abs(signal.directScore) >= 0.001f) {
                propagateSimilarityBoost(filename, signal.directScore, "v2_migration")
            }
        }
    }

    /**
     * Apply a playback event. Returns the (before, after) snapshot pair so
     * the caller can store it in the SignalTimeline.
     *
     * Push #63 — Capacitor parity:
     *   - plays increments only when `fraction >= SKIP_THRESHOLD` (0.50).
     *   - avgFraction is the running average of non-skip listens only.
     *   - xScore on skip: +USER_SKIP_NEGATIVE_STEP (0.1), capped at 10.
     *   - xScore on strong listen (>= 0.70): -NEG_LISTEN_DECAY (0.5), >=0.
     *   - xScore on partial listen: unchanged (no decay).
     *   - Manual taps (manual_x, song_tap, queue_tap, neutral_skip):
     *     count as encounter but no skip count, no xScore bump.
     */
    fun recordPlaybackEvent(
        filename: String,
        fraction: Float,
        source: String,
        playbackInstanceId: Long = 0L,
        eventId: String = "",
    ): Pair<TasteSignal, TasteSignal> {
        val before = signalFor(filename)
        if (eventId.isNotBlank() && eventId in processedPlaybackEventIds) {
            android.util.Log.i(
                "TasteEngine",
                "recordPlaybackEvent skipped fn=$filename src=$source: duplicate eventId=$eventId"
            )
            return before to before
        }
        // Push #66 dedupe: if this is the same playbackInstanceId we just
        // recorded, the caller is re-firing for a transition we already
        // captured. No-op.
        if (playbackInstanceId > 0L && playbackInstanceId == lastCapturedPlaybackInstanceId) {
            android.util.Log.i(
                "TasteEngine",
                "recordPlaybackEvent skipped fn=$filename src=$source: duplicate instanceId=$playbackInstanceId"
            )
            return before to before
        }
        val frac = fraction.coerceIn(0f, 1f)
        // Push #66 — Capacitor parity (engine.js `_shouldAccumulateUserSkipNegative`):
        // ONLY `next_button` / `prev_button` count as explicit user skips
        // that bump xScore. Everything else (manual_tap, song_tap,
        // queue_tap, neutral_skip, auto_advance, app_background,
        // background_recovery, cold_start_*) is an encounter only — the
        // listen evidence still updates plays/avgFraction but the song
        // doesn't get a skip-penalty.
        val outcome = UnifiedTasteContract.outcomeFor(frac)
        val contribution = UnifiedTasteContract.positiveContribution(frac)
        val now = System.currentTimeMillis()

        // Plays counter: only increments on non-skip (fraction >= 0.50).
        val isQualifiedListen = outcome == PlaybackOutcome.LISTEN ||
            outcome == PlaybackOutcome.STRONG
        val isStrong = outcome == PlaybackOutcome.STRONG
        val newPlays = if (isQualifiedListen) before.plays + 1 else before.plays
        val isExplicitUserSkip = isExplicitUserSkip(source, frac)
        val newSkips = if (isExplicitUserSkip) before.skips + 1 else before.skips
        // Push #73: fullListens only increments on >=0.70 non-skip.
        // Used to decay the dislike prior asymmetrically.
        val newFullListens = if (isStrong) before.fullListens + 1 else before.fullListens
        // avgFraction tracks only non-skip listens. When the event is a
        // skip, the existing avgFraction is preserved.
        val newAvgFrac = when {
            !isQualifiedListen -> before.avgFraction
            before.plays == 0 -> frac
            else -> ((before.avgFraction * before.plays) + frac) / newPlays
        }
        val newEncounters = before.encounters + 1
        val newAvgAll = ((before.avgAllFraction * before.encounters) + frac) / newEncounters
        val oldEvidence = before.positiveEvidence.takeIf { it > 0f }
            ?: (before.plays * before.avgFraction)
        val oldEvidenceAt = before.positiveEvidenceUpdatedAt.takeIf { it > 0L }
            ?: before.lastPlayedAt
        val decayedEvidence = if (oldEvidence > 0f && oldEvidenceAt > 0L) {
            val days = (now - oldEvidenceAt).coerceAtLeast(0L).toFloat() / PROFILE_DAY_MS
            oldEvidence * 0.5f.pow(days / PROFILE_HALF_LIFE_DAYS)
        } else oldEvidence
        val newEvidence = decayedEvidence + contribution
        val newEvidenceAt = if (contribution > 0f || oldEvidence > 0f) now
            else before.positiveEvidenceUpdatedAt
        val isEarlyUserRejection = isEarlyUserRejection(source, frac)
        val newEarlySkipCount = before.earlySkipCount + if (isEarlyUserRejection) 1 else 0
        val newEarlySkipPenalty = if (isEarlyUserRejection) {
            UnifiedTasteContract.earlyRejectionPenalty(newEarlySkipCount)
        } else before.earlySkipPenalty
        val earlyPenaltyDelta = (newEarlySkipPenalty - before.earlySkipPenalty).coerceAtLeast(0f)
        val changesAvoidance = isStrong || earlyPenaltyDelta > 0f
        val avoidanceBase = if (changesAvoidance) {
            decayedLearnedValue(
                value = before.xScore,
                updatedAt = before.xScoreUpdatedAt,
                fallbackAt = before.lastUpdatedAt,
                now = now,
            )
        } else before.xScore
        val newX = if (changesAvoidance) {
            (nextXScoreAfterPlayback(avoidanceBase, frac, source) + earlyPenaltyDelta)
                .coerceAtMost(NEG_SCORE_MAX)
        } else before.xScore
        val xScoreChanged = newX != before.xScore
        // Push #73: stamp xScoreUpdatedAt only when xScore actually
        // changed. Stale timestamps would defeat the recency decay.
        val newXScoreUpdatedAt = if (xScoreChanged) now else before.xScoreUpdatedAt
        // Recompute manual priors against the new counters. Favorite
        // decays on plays (all engagement); dislike decays on
        // fullListens (only genuine listen-throughs).
        val newFavPrior = if (before.isFavorite) computeFavoritePrior(newPlays) else 0f
        val newDisPrior = if (before.isDisliked) computeDislikePrior(newFullListens) else 0f
        // lastPlayedAt — only stamp on real listens, not skips. A skip
        // doesn't mean "I played this," it means "I rejected this."
        val newLastPlayedAt = if (contribution > 0f) now else before.lastPlayedAt
        val newBreakdown = computeUnifiedScore(
            positiveEvidence = newEvidence,
            positiveEvidenceUpdatedAt = newEvidenceAt,
            xScore = newX,
            favoritePrior = newFavPrior,
            dislikePrior = newDisPrior,
            similarityBoost = effectiveSimilarityBoost(before, now),
            xScoreUpdatedAt = newXScoreUpdatedAt,
            similarityBoostUpdatedAt = now,
            lastUpdatedAt = now,
            now = now,
        )
        val after = before.copy(
            plays = newPlays,
            skips = newSkips,
            avgFraction = newAvgFrac,
            xScore = newX,
            directScore = newBreakdown.directScore,
            favoritePrior = newFavPrior,
            dislikePrior = newDisPrior,
            lastPlayedAt = newLastPlayedAt,
            lastUpdatedAt = now,
            fullListens = newFullListens,
            xScoreUpdatedAt = newXScoreUpdatedAt,
            encounters = newEncounters,
            earlyCount = before.earlyCount + if (outcome == PlaybackOutcome.EARLY) 1 else 0,
            partialCount = before.partialCount + if (outcome == PlaybackOutcome.PARTIAL) 1 else 0,
            listenCount = before.listenCount + if (outcome == PlaybackOutcome.LISTEN) 1 else 0,
            strongCount = before.strongCount + if (outcome == PlaybackOutcome.STRONG) 1 else 0,
            avgAllFraction = newAvgAll,
            positiveEvidence = newEvidence,
            positiveEvidenceUpdatedAt = newEvidenceAt,
            earlySkipCount = newEarlySkipCount,
            earlySkipPenalty = newEarlySkipPenalty,
        )
        android.util.Log.i(
            "TasteEngine",
            "recordPlaybackEvent fn=$filename frac=$frac src=$source outcome=$outcome contribution=$contribution earlyReject=$isEarlyUserRejection " +
                "plays=${before.plays}→$newPlays skips=${before.skips}→$newSkips avg=${"%.2f".format(before.avgFraction)}→${"%.2f".format(newAvgFrac)} " +
                "x=${"%.2f".format(before.xScore)}→${"%.2f".format(newX)} direct=${"%.2f".format(before.directScore)}→${"%.2f".format(newBreakdown.directScore)} recencyMult=${"%.2f".format(newBreakdown.recencyMult)} instId=$playbackInstanceId"
        )

        _signals.value = _signals.value + (filename to after)
        if (playbackInstanceId > 0L) lastCapturedPlaybackInstanceId = playbackInstanceId
        if (eventId.isNotBlank()) {
            processedPlaybackEventIds = (processedPlaybackEventIds + eventId).takeLastIds(MAX_PROCESSED_EVENT_IDS)
        }
        scope.launch { persistSignals() }
        refreshDecorated()
        return before to after
    }

    /**
     * Called when FavoritesEngine.toggle / DislikedEngine.toggle fires so the
     * per-song directScore reflects the new manual prior immediately.
     */
    fun applyManualPriorChange(
        filename: String,
        isFavorite: Boolean,
        isDisliked: Boolean,
    ): Pair<TasteSignal, TasteSignal> {
        if (filename.isBlank()) return TasteSignal() to TasteSignal()
        val before = signalFor(filename)
        val newFavPrior = if (isFavorite) computeFavoritePrior(before.plays) else 0f
        val newDisPrior = if (isDisliked) computeDislikePrior(before.fullListens) else 0f
        val now = System.currentTimeMillis()
        val newBreakdown = computeUnifiedScore(
            positiveEvidence = before.positiveEvidence.takeIf { it > 0f }
                ?: (before.plays * before.avgFraction),
            positiveEvidenceUpdatedAt = before.positiveEvidenceUpdatedAt
                .takeIf { it > 0L } ?: before.lastPlayedAt,
            xScore = before.xScore,
            favoritePrior = newFavPrior,
            dislikePrior = newDisPrior,
            similarityBoost = effectiveSimilarityBoost(before, now),
            xScoreUpdatedAt = before.xScoreUpdatedAt,
            similarityBoostUpdatedAt = now,
            lastUpdatedAt = now,
            now = now,
        )
        val after = before.copy(
            favoritePrior = newFavPrior,
            dislikePrior = newDisPrior,
            isFavorite = isFavorite,
            isDisliked = isDisliked,
            directScore = newBreakdown.directScore,
            lastUpdatedAt = now,
        )
        _signals.value = _signals.value + (filename to after)
        android.util.Log.i(
            "TasteEngine",
            "applyManualPriorChange fn=$filename fav=$isFavorite dis=$isDisliked favPrior=$newFavPrior disPrior=$newDisPrior direct=${before.directScore}→${newBreakdown.directScore}"
        )
        scope.launch { persistSignals() }
        refreshDecorated()
        return before to after
    }

    /**
     * Push #63: bump xScore for a song that was removed from Up Next.
     * Capacitor parity (engine.js:1590) — treats queue removal as a mild
     * dislike signal. Returns the new directScore so callers can compute
     * a propagation delta if they want.
     */
    fun bumpXScoreForQueueRemove(filename: String): Pair<TasteSignal, TasteSignal> {
        if (filename.isBlank()) return TasteSignal() to TasteSignal()
        val before = signalFor(filename)
        val now = System.currentTimeMillis()
        val avoidanceBase = decayedLearnedValue(
            value = before.xScore,
            updatedAt = before.xScoreUpdatedAt,
            fallbackAt = before.lastUpdatedAt,
            now = now,
        )
        val newX = (avoidanceBase + UnifiedTasteContract.QUEUE_REMOVAL_AVOIDANCE)
            .coerceAtMost(NEG_SCORE_MAX)
        val newBreakdown = computeUnifiedScore(
            positiveEvidence = before.positiveEvidence.takeIf { it > 0f }
                ?: (before.plays * before.avgFraction),
            positiveEvidenceUpdatedAt = before.positiveEvidenceUpdatedAt
                .takeIf { it > 0L } ?: before.lastPlayedAt,
            xScore = newX,
            favoritePrior = before.favoritePrior,
            dislikePrior = before.dislikePrior,
            similarityBoost = effectiveSimilarityBoost(before, now),
            xScoreUpdatedAt = now,
            similarityBoostUpdatedAt = now,
            lastUpdatedAt = now,
            now = now,
        )
        val after = before.copy(
            xScore = newX,
            directScore = newBreakdown.directScore,
            lastUpdatedAt = now,
            xScoreUpdatedAt = now,
            queueRemovals = before.queueRemovals + 1,
        )
        _signals.value = _signals.value + (filename to after)
        android.util.Log.i(
            "TasteEngine",
            "bumpXScoreForQueueRemove fn=$filename x=${before.xScore}→$newX direct=${before.directScore}→${newBreakdown.directScore}"
        )
        scope.launch { persistSignals() }
        refreshDecorated()
        return before to after
    }

    /**
     * Push #63: propagate a score delta to the source song's top-N
     * embedding neighbors. Capacitor parity (engine-taste.js:865-903).
     * Spread weights: [0.10, 0.09, …, 0.01] for neighbors 0..9 by rank.
     * Contributor history is retained in full; the effective total used by
     * scoring is capped to ±0.5.
     * No-op when [neighborLookup] is not wired or [scoreDelta] is below
     * the noise floor.
     *
     * Callers:
     *   - PlaybackEngine on transition (delta = after.directScore - before.directScore)
     *   - AppContainer favorites/disliked toggle hook (same delta)
     *   - PlaybackEngine queue-remove (delta = -NEG_X_DELTA = -0.5)
     */
    suspend fun propagateSimilarityBoost(
        sourceFilename: String,
        scoreDelta: Float,
        reason: String,
    ): PropagationSummary = propagationMutex.withLock {
        propagateSimilarityBoostUnlocked(sourceFilename, scoreDelta, reason)
    }

    private suspend fun propagateSimilarityBoostUnlocked(
        sourceFilename: String,
        scoreDelta: Float,
        reason: String,
    ): PropagationSummary {
        val persistent = isPersistentManualContributor(reason)
        if (sourceFilename.isBlank() || (!persistent && abs(scoreDelta) < 0.001f)) {
            return PropagationSummary()
        }
        val lookup = neighborLookup ?: run {
            android.util.Log.w("TasteEngine", "propagate src=$sourceFilename Δ=$scoreDelta reason=$reason: no neighborLookup wired")
            return PropagationSummary()
        }
        val neighbors = try { lookup(sourceFilename, SIMILARITY_NEIGHBOR_COUNT) }
            catch (t: Throwable) {
                android.util.Log.w("TasteEngine", "propagate: lookup failed: ${t.message}")
                return PropagationSummary()
            }
        if (neighbors.isEmpty()) return PropagationSummary()
        val now = System.currentTimeMillis()
        var changedCount = 0
        var totalDelta = 0f
        var next = _signals.value
        val limit = minOf(neighbors.size, SIMILARITY_NEIGHBOR_WEIGHTS.size)
        for (i in 0 until limit) {
            val nfn = neighbors[i]
            if (nfn == sourceFilename) continue
            val w = SIMILARITY_NEIGHBOR_WEIGHTS[i]
            val delta = scoreDelta * w
            if (!persistent && abs(delta) < 0.001f) continue
            val cur = next[nfn] ?: TasteSignal()
            val effectiveBoost = effectiveSimilarityBoost(cur, now)
            val normalizedContributors = cur.similarityContributors.map { existing ->
                if (isPersistentManualContributor(existing.reason)) {
                    existing
                } else {
                    existing.copy(
                        amount = decayedLearnedValue(
                            value = existing.amount,
                            updatedAt = existing.updatedAt,
                            fallbackAt = cur.lastUpdatedAt,
                            now = now,
                        ),
                        updatedAt = now,
                    )
                }
            }
            val priorContributor = normalizedContributors.firstOrNull {
                it.sourceFilename == sourceFilename &&
                    isPersistentManualContributor(it.reason) == persistent
            }
            val contributor = SimilarityContributor(
                sourceFilename = sourceFilename,
                amount = if (persistent) delta else (priorContributor?.amount ?: 0f) + delta,
                reason = reason,
                updatedAt = now,
            )
            val contributors = (normalizedContributors.filterNot {
                it.sourceFilename == sourceFilename &&
                    isPersistentManualContributor(it.reason) == persistent
            } + contributor)
                .filter { abs(it.amount) >= 0.001f }
                .sortedByDescending { abs(it.amount) }
            val rawBoost = contributors.sumOf { it.amount.toDouble() }.toFloat()
            val newBoost = rawBoost.coerceIn(-SIMILARITY_BOOST_MAX, SIMILARITY_BOOST_MAX)
            val appliedDelta = newBoost - effectiveBoost
            val nb = computeUnifiedScore(
                positiveEvidence = cur.positiveEvidence.takeIf { it > 0f }
                    ?: (cur.plays * cur.avgFraction),
                positiveEvidenceUpdatedAt = cur.positiveEvidenceUpdatedAt
                    .takeIf { it > 0L } ?: cur.lastPlayedAt,
                xScore = cur.xScore,
                favoritePrior = cur.favoritePrior,
                dislikePrior = cur.dislikePrior,
                similarityBoost = newBoost,
                xScoreUpdatedAt = cur.xScoreUpdatedAt,
                similarityBoostUpdatedAt = now,
                lastUpdatedAt = now,
                now = now,
            )
            next = next + (nfn to cur.copy(
                similarityBoost = rawBoost,
                directScore = nb.directScore,
                lastUpdatedAt = now,
                similarityBoostUpdatedAt = now,
                similarityContributors = contributors,
            ))
            changedCount++
            totalDelta += appliedDelta
        }
        if (changedCount > 0) {
            _signals.value = next
            scope.launch { persistSignals() }
            refreshDecorated()
        }
        android.util.Log.i(
            "TasteEngine",
            "propagate src=$sourceFilename Δ=${"%.3f".format(scoreDelta)} reason=$reason changed=$changedCount"
        )
        return PropagationSummary(changedSongs = changedCount, totalDelta = totalDelta)
    }

    suspend fun syncManualSimilarityHalo(
        sourceFilename: String,
        signal: TasteSignal,
        reason: String = "manual_prior_sync",
    ): PropagationSummary = propagateSimilarityBoost(
        sourceFilename = sourceFilename,
        scoreDelta = signal.favoritePrior - signal.dislikePrior,
        reason = reason,
    )

    suspend fun propagatePlaybackTasteChange(
        sourceFilename: String,
        before: TasteSignal,
        after: TasteSignal,
        reason: String,
    ): PropagationSummary {
        val manualBefore = before.favoritePrior - before.dislikePrior
        val manualAfter = after.favoritePrior - after.dislikePrior
        val learnedDelta = (after.directScore - before.directScore) -
            (manualAfter - manualBefore)
        val learned = propagateSimilarityBoost(sourceFilename, learnedDelta, reason)
        val manual = if (abs(manualAfter - manualBefore) >= 0.001f) {
            syncManualSimilarityHalo(sourceFilename, after)
        } else {
            PropagationSummary()
        }
        return PropagationSummary(
            changedSongs = maxOf(learned.changedSongs, manual.changedSongs),
            totalDelta = learned.totalDelta + manual.totalDelta,
        )
    }

    /**
     * Push #68: stable hash of the current recommendation policy.
     * Capacitor parity (`_buildRecommendationFingerprint`). Incorporates
     * top-10 positive + top-10 negative + hard-block set so a
     * Discover/UpNext cache built with one policy can be detected as
     * stale when the policy meaningfully shifts.
     */
    fun recommendationFingerprint(): String {
        val dec = _decorated.value
        val topPos = dec.rows.values
            .filter { it.breakdown.score > 0f }
            .sortedByDescending { it.breakdown.score }
            .take(10)
            .joinToString("|") { "${it.filename}:${"%.2f".format(it.breakdown.score)}" }
        val topNeg = dec.rows.values
            .filter { it.breakdown.score < 0f }
            .sortedBy { it.breakdown.score }
            .take(10)
            .joinToString("|") { "${it.filename}:${"%.2f".format(it.breakdown.score)}" }
        val blocked = dec.hardBlockedFilenames.toSortedSet().joinToString(",")
        return "$topPos::$topNeg::$blocked".hashCode().toString()
    }

    /**
     * Per-song reset on the Taste page. Push #63: full clear.
     * isFavorite/isDisliked are held by FavoritesEngine/DislikedEngine
     * (separate stores) and therefore survive automatically.
     */
    fun resetSignalForSong(
        filename: String,
        isFavorite: Boolean = false,
        isDisliked: Boolean = false,
    ) {
        if (filename.isBlank()) return
        val now = System.currentTimeMillis()
        val cleaned = _signals.value.mapValues { (_, signal) ->
            val contributors = signal.similarityContributors.filterNot {
                it.sourceFilename == filename && !isPersistentManualContributor(it.reason)
            }
            signal.copy(
                similarityContributors = contributors,
                similarityBoost = contributors.sumOf { it.amount.toDouble() }.toFloat(),
                similarityBoostUpdatedAt = now,
            )
        }.toMutableMap()
        val incomingManual = cleaned[filename]?.similarityContributors
            ?.filter { isPersistentManualContributor(it.reason) }
            .orEmpty()
        val favoritePrior = if (isFavorite) MANUAL_FAVORITE_PRIOR_BASE else 0f
        val dislikePrior = if (isDisliked) MANUAL_DISLIKE_PRIOR_BASE else 0f
        val incomingBoost = incomingManual.sumOf { it.amount.toDouble() }.toFloat()
        val breakdown = computeUnifiedScore(
            positiveEvidence = 0f,
            positiveEvidenceUpdatedAt = 0L,
            xScore = 0f,
            favoritePrior = favoritePrior,
            dislikePrior = dislikePrior,
            similarityBoost = incomingBoost,
            xScoreUpdatedAt = 0L,
            similarityBoostUpdatedAt = now,
            lastUpdatedAt = now,
            now = now,
        )
        if (isFavorite || isDisliked || incomingManual.isNotEmpty()) {
            cleaned[filename] = TasteSignal(
                directScore = breakdown.directScore,
                similarityBoost = incomingBoost,
                favoritePrior = favoritePrior,
                dislikePrior = dislikePrior,
                isFavorite = isFavorite,
                isDisliked = isDisliked,
                lastUpdatedAt = now,
                similarityBoostUpdatedAt = now,
                similarityContributors = incomingManual,
            )
        } else {
            cleaned.remove(filename)
        }
        _signals.value = cleaned
        scope.launch { persistSignals() }
        refreshDecorated()
        android.util.Log.i("TasteEngine", "resetSignalForSong fn=$filename")
    }

    /** Clears every per-song signal — paired with Reset Engine. */
    fun resetAllSignals() {
        _signals.value = emptyMap()
        processedPlaybackEventIds = emptySet()
        scope.launch { persistSignals() }
        refreshDecorated()
    }

    /**
     * Atomic Reset Engine path: wipe playback-derived signals, then seed
     * fresh entries for every manual favorite/dislike in one persist.
     */
    fun resetAllSignalsPreservingManual(favSet: Set<String>, disSet: Set<String>) {
        processedPlaybackEventIds = emptySet()
        val now = System.currentTimeMillis()
        val toReapply = favSet + disSet
        val newMap = HashMap<String, TasteSignal>(toReapply.size)
        for (fn in toReapply) {
            val isFavorite = fn in favSet
            val isDisliked = fn in disSet
            val newFavPrior = if (isFavorite) computeFavoritePrior(0) else 0f
            val newDisPrior = if (isDisliked) computeDislikePrior(0) else 0f
            val bd = computeUnifiedScore(
                positiveEvidence = 0f,
                positiveEvidenceUpdatedAt = 0L,
                xScore = 0f,
                favoritePrior = newFavPrior,
                dislikePrior = newDisPrior,
                similarityBoost = 0f,
                xScoreUpdatedAt = 0L,
                similarityBoostUpdatedAt = 0L,
                lastUpdatedAt = now,
                now = now,
            )
            newMap[fn] = TasteSignal(
                favoritePrior = newFavPrior,
                dislikePrior = newDisPrior,
                isFavorite = isFavorite,
                isDisliked = isDisliked,
                directScore = bd.directScore,
                lastUpdatedAt = now,
            )
        }
        _signals.value = newMap
        refreshDecorated()
        scope.launch { persistSignals() }
        android.util.Log.i(
            "TasteEngine",
            "resetAllSignalsPreservingManual fav=${favSet.size} dis=${disSet.size} reapply=${toReapply.size}"
        )
    }

    /**
     * Push #69 — Capacitor parity (`_buildSuspiciousRecommendationData`):
     * detect songs with conflicting signals that warrant a user review.
     * Returns (isSuspicious, reason). Reason is empty when not suspicious.
     */
    private fun detectSuspicious(s: TasteSignal): Pair<Boolean, String> {
        // Pattern 1: Favorite + repeated skips
        if (s.isFavorite && s.skips >= 3 && s.skips > s.plays) {
            return true to "Favorited but frequently skipped (${s.skips} skips / ${s.plays} plays)"
        }
        // Pattern 2: Disliked + high avgFraction
        if (s.isDisliked && s.plays >= 2 && s.avgFraction >= 0.70f) {
            return true to "Disliked but listened ${(s.avgFraction * 100).toInt()}% on avg (${s.plays} plays)"
        }
        // Pattern 3: High xScore + high avgFraction — conflicting evidence.
        if (s.xScore >= 2f && s.plays > 0 && s.avgFraction >= 0.70f) {
            return true to "High X (${"%.1f".format(s.xScore)}) but avg listen ${(s.avgFraction * 100).toInt()}%"
        }
        // Pattern 4: Very few plays + extreme avgFraction (noisy data).
        if (s.plays == 1 && (s.avgFraction >= 0.95f || s.avgFraction <= 0.05f)) {
            return true to "Single ${(s.avgFraction * 100).toInt()}% listen — need more evidence"
        }
        return false to ""
    }

    private fun computeFavoritePrior(@Suppress("UNUSED_PARAMETER") plays: Int): Float =
        FAVORITE_PRIOR_BASE

    /**
     * Push #73: dislike prior decays on fullListens (frac >= 0.70), not
     * total plays. A user who keeps half-skipping a disliked song
     * shouldn't be erasing the dislike — only genuine listen-throughs
     * count as evidence that the dislike was wrong.
     */
    private fun computeDislikePrior(fullListens: Int): Float {
        val w = DISLIKE_PRIOR_BASE * 0.5f.pow(fullListens / MANUAL_PRIOR_HALF_LIFE_PLAYS)
        return if (w >= MANUAL_PRIOR_MIN) w else 0f
    }

    /**
     * Recompute and publish [_decorated]. O(N log N) over signals.
     */
    private fun refreshDecorated() {
        val now = System.currentTimeMillis()
        val src = _signals.value
        if (src.isEmpty()) {
            _decorated.value = DecoratedSignals(emptyMap(), emptySet(), 0, 0, now)
            return
        }
        // Step 1: compute breakdown per signal.
        data class Row(
            val fn: String,
            val sig: TasteSignal,
            val bd: DirectScoreBreakdown,
        )
        val rows = src.entries.map { (fn, sig) ->
            Row(
                fn, sig,
                computeUnifiedScore(
                    positiveEvidence = sig.positiveEvidence.takeIf { it > 0f }
                        ?: (sig.plays * sig.avgFraction),
                    positiveEvidenceUpdatedAt = sig.positiveEvidenceUpdatedAt
                        .takeIf { it > 0L } ?: sig.lastPlayedAt,
                    xScore = sig.xScore,
                    favoritePrior = sig.favoritePrior,
                    dislikePrior = sig.dislikePrior,
                    similarityBoost = effectiveSimilarityBoost(sig, now),
                    xScoreUpdatedAt = sig.xScoreUpdatedAt,
                    similarityBoostUpdatedAt = now,
                    lastUpdatedAt = sig.lastUpdatedAt,
                    now = now,
                )
            )
        }
        // Step 2: top-positive / top-negative rankings by effective weight.
        val topPos = rows.filter { it.bd.effectivePositive > 0f }
            .sortedByDescending { it.bd.effectivePositive }
            .take(TOP_RANK_LIMIT)
            .map { it.fn }
            .toHashSet()
        val topNeg = rows.filter { it.bd.effectiveNegative > 0f }
            .sortedByDescending { it.bd.effectiveNegative }
            .take(TOP_RANK_LIMIT)
            .map { it.fn }
            .toHashSet()
        // Step 3: hard-block at the user's Negative guard (matches Up Next policy).
        val policyGuard = _tuning.value.negativeStrength.coerceIn(0f, 1f)
        val hardBlocked = RecommendationPolicy.hardBlockedFilenames(
            rows.associate { it.fn to it.bd.score },
            negativeStrength = policyGuard,
        )
        // Step 4: build DecoratedRow entries.
        val out = HashMap<String, DecoratedRow>(rows.size)
        var posCount = 0
        var negCount = 0
        for (r in rows) {
            val isActive = r.bd.effectivePositive > 0.001f ||
                r.bd.effectiveNegative > 0.001f ||
                abs(r.bd.score) > 0.001f
            val inPos = r.fn in topPos
            val inNeg = r.fn in topNeg
            val isShortListened = r.sig.plays >= 2 &&
                r.sig.avgFraction < 0.5f &&
                r.bd.directScore < 0f
            val isMixed = r.bd.positiveWeight > 0f && r.bd.negativeWeight > 0f
            // Counts must match the Taste screen's "Top Positive"/"Top
            // Negative" tab filters, which split on overall `score`
            // (directScore + similarityBoost) — see TasteScreen.kt.
            if (r.bd.score > 0f) posCount++
            else if (r.bd.score < 0f) negCount++
            // Push #69: suspicious-pattern detector.
            val (suspicious, reason) = detectSuspicious(r.sig)
            out[r.fn] = DecoratedRow(
                filename = r.fn,
                signal = r.sig,
                breakdown = r.bd,
                isActive = isActive,
                inTopPositive30 = inPos,
                inTopNegative30 = inNeg,
                inTop30 = inPos || inNeg,
                isShortListened = isShortListened,
                isMixed = isMixed,
                isHardRecommendationBlock = r.fn in hardBlocked,
                isSuspicious = suspicious,
                suspiciousReason = reason,
            )
        }
        _decorated.value = DecoratedSignals(
            rows = out,
            hardBlockedFilenames = hardBlocked,
            positiveActiveCount = posCount,
            negativeActiveCount = negCount,
            computedAt = now,
        )
    }

    private suspend fun persistTuning() {
        try {
            val t = _tuning.value
            appContext.dataStoreLocal.edit {
                it[KEY_ADV] = t.adventurous
                it[KEY_TASTE_INFLUENCE] = t.tasteInfluence
                it[KEY_NEG] = t.negativeStrength
                it[KEY_BLEND_MANUAL] = if (t.manualBlend) 1 else 0
                it[KEY_BLEND_CURRENT] = t.manualCurrentWeight
                it[KEY_BLEND_SESSION] = t.manualSessionWeight
                it.remove(KEY_FAM_LEGACY)
            }
        } catch (t: Throwable) {
            android.util.Log.w("TasteEngine", "persistTuning failed: ${t.message}")
        }
    }

    private suspend fun persistSignals() {
        try {
            val root = JSONObject()
            for ((fn, s) in _signals.value) {
                root.put(fn, JSONObject().apply {
                    put("plays", s.plays)
                    put("skips", s.skips)
                    put("avgFraction", s.avgFraction.toDouble())
                    put("xScore", s.xScore.toDouble())
                    put("directScore", s.directScore.toDouble())
                    put("similarityBoost", s.similarityBoost.toDouble())
                    put("favoritePrior", s.favoritePrior.toDouble())
                    put("dislikePrior", s.dislikePrior.toDouble())
                    put("isFavorite", s.isFavorite)
                    put("isDisliked", s.isDisliked)
                    put("lastPlayedAt", s.lastPlayedAt)
                    put("lastUpdatedAt", s.lastUpdatedAt)
                    put("fullListens", s.fullListens)
                    put("xScoreUpdatedAt", s.xScoreUpdatedAt)
                    put("similarityBoostUpdatedAt", s.similarityBoostUpdatedAt)
                    put("encounters", s.encounters)
                    put("earlyCount", s.earlyCount)
                    put("partialCount", s.partialCount)
                    put("listenCount", s.listenCount)
                    put("strongCount", s.strongCount)
                    put("queueRemovals", s.queueRemovals)
                    put("avgAllFraction", s.avgAllFraction.toDouble())
                    put("positiveEvidence", s.positiveEvidence.toDouble())
                    put("positiveEvidenceUpdatedAt", s.positiveEvidenceUpdatedAt)
                    put("earlySkipCount", s.earlySkipCount)
                    put("earlySkipPenalty", s.earlySkipPenalty.toDouble())
                    put("similarityContributors", JSONArray().apply {
                        s.similarityContributors.forEach { contributor ->
                            put(JSONObject().apply {
                                put("source", contributor.sourceFilename)
                                put("amount", contributor.amount.toDouble())
                                put("reason", contributor.reason)
                                put("updatedAt", contributor.updatedAt)
                            })
                        }
                    })
                })
            }
            appContext.dataStoreLocal.edit {
                it[KEY_SIGNALS] = root.toString()
                it[KEY_PROCESSED_EVENT_IDS] = eventIdsJson(processedPlaybackEventIds)
            }
        } catch (t: Throwable) {
            android.util.Log.w("TasteEngine", "persistSignals failed: ${t.message}")
        }
    }

    private fun parseSignals(raw: String): Map<String, TasteSignal> {
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, TasteSignal>(o.length())
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.optJSONObject(k) ?: continue
                out[k] = TasteSignal(
                    plays = v.optInt("plays", 0),
                    skips = v.optInt("skips", 0),
                    avgFraction = v.optDouble("avgFraction", 0.0).toFloat(),
                    xScore = v.optDouble("xScore", 0.0).toFloat(),
                    directScore = v.optDouble("directScore", 0.0).toFloat(),
                    similarityBoost = v.optDouble("similarityBoost", 0.0).toFloat(),
                    favoritePrior = v.optDouble("favoritePrior", 0.0).toFloat(),
                    dislikePrior = v.optDouble("dislikePrior", 0.0).toFloat(),
                    isFavorite = v.optBoolean("isFavorite", false),
                    isDisliked = v.optBoolean("isDisliked", false),
                    lastPlayedAt = v.optLong("lastPlayedAt", 0L),
                    lastUpdatedAt = v.optLong("lastUpdatedAt", 0L),
                    fullListens = v.optInt("fullListens", 0),
                    xScoreUpdatedAt = v.optLong("xScoreUpdatedAt", 0L),
                    similarityBoostUpdatedAt = v.optLong("similarityBoostUpdatedAt", 0L),
                    encounters = v.optInt("encounters", 0),
                    earlyCount = v.optInt("earlyCount", 0),
                    partialCount = v.optInt("partialCount", 0),
                    listenCount = v.optInt("listenCount", 0),
                    strongCount = v.optInt("strongCount", 0),
                    queueRemovals = v.optInt("queueRemovals", 0),
                    avgAllFraction = v.optDouble("avgAllFraction", 0.0).toFloat(),
                    positiveEvidence = v.optDouble("positiveEvidence", 0.0).toFloat(),
                    positiveEvidenceUpdatedAt = v.optLong("positiveEvidenceUpdatedAt", 0L),
                    earlySkipCount = v.optInt("earlySkipCount", 0),
                    earlySkipPenalty = v.optDouble("earlySkipPenalty", 0.0).toFloat(),
                    similarityContributors = parseSimilarityContributors(
                        v.optJSONArray("similarityContributors"),
                    ),
                )
            }
            out
        } catch (t: Throwable) {
            android.util.Log.w("TasteEngine", "parseSignals failed: ${t.message}")
            emptyMap()
        }
    }

    private fun parseSimilarityContributors(arr: JSONArray?): List<SimilarityContributor> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val value = arr.optJSONObject(i) ?: continue
                val source = value.optString("source", "")
                if (source.isBlank()) continue
                add(
                    SimilarityContributor(
                        sourceFilename = source,
                        amount = value.optDouble("amount", 0.0).toFloat(),
                        reason = value.optString("reason", ""),
                        updatedAt = value.optLong("updatedAt", 0L),
                    )
                )
            }
        }
    }

    private fun parseEventIds(raw: String): Set<String> {
        return try {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "")
                    if (id.isNotBlank()) add(id)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("TasteEngine", "parseEventIds failed: ${t.message}")
            emptySet()
        }
    }

    private fun eventIdsJson(ids: Set<String>): String {
        val arr = JSONArray()
        for (id in ids.takeLastIds(MAX_PROCESSED_EVENT_IDS)) arr.put(id)
        return arr.toString()
    }

    private fun Set<String>.takeLastIds(limit: Int): Set<String> {
        if (size <= limit) return this
        return toList().takeLast(limit).toSet()
    }
}

internal fun buildOutboundSimilarityIndex(
    signals: Map<String, TasteEngine.TasteSignal>,
): Map<String, List<TasteEngine.OutboundSimilarityImpact>> {
    val outbound = LinkedHashMap<String, MutableList<TasteEngine.OutboundSimilarityImpact>>()
    signals.forEach { (targetFilename, signal) ->
        signal.similarityContributors.forEach { contributor ->
            if (contributor.sourceFilename.isBlank() || contributor.amount == 0f) return@forEach
            outbound.getOrPut(contributor.sourceFilename) { mutableListOf() }
                .add(
                    TasteEngine.OutboundSimilarityImpact(
                        targetFilename = targetFilename,
                        amount = contributor.amount,
                        reason = contributor.reason,
                        updatedAt = contributor.updatedAt,
                    ),
                )
        }
    }
    return outbound.mapValues { (_, impacts) ->
        impacts.sortedWith(
            compareByDescending<TasteEngine.OutboundSimilarityImpact> { abs(it.amount) }
                .thenBy { it.targetFilename },
        )
    }
}

/*
 * Inactive v1 score reconstruction retained outside compilation for migration
 * history. Active scoring uses computeUnifiedScore exclusively.
 *
/**
 * Capacitor parity directScore (engine-taste.js:391-487), extended by
 * Push #73 with recency decay for xScore + similarityBoost and a
 * single-encounter rejection branch.
 *
 *   recencyMult        = 0.5^(daysSince / PROFILE_HALF_LIFE_DAYS)
 *                        (driven by lastPlayedAt — real listens only)
 *   xRecencyMult       = 0.5^(daysSinceXScore / PROFILE_HALF_LIFE_DAYS)
 *                        (driven by xScoreUpdatedAt — last skip/full-listen
 *                         event; falls back to lastUpdatedAt for legacy data)
 *   simRecencyMult     = 0.5^(daysSinceSim / PROFILE_HALF_LIFE_DAYS)
 *                        (driven by similarityBoostUpdatedAt)
 *
 *   positiveListen     = plays × avgFraction × recencyMult  (plays > 0)
 *   negativeListen     = plays × (1 − avgFraction) × recencyMult
 *                          when plays ≥ NEGATIVE_PLAY_THRESHOLD (2) AND
 *                               avgFraction < NEGATIVE_FRAC_THRESHOLD (0.5)
 *                        plays × (1 − avgFraction) × recencyMult × 0.5
 *                          when xScore > 0 (already-penalized song)
 *                        (1 − avgFraction) × recencyMult × 0.5
 *                          when plays = 1 AND avgFraction < 0.2
 *                          (Push #73: single-encounter rejection. One
 *                           horrible partial listen of an otherwise
 *                           unknown song still emits a signal.)
 *                        0 otherwise
 *
 *   effectiveX         = xScore × xRecencyMult
 *   effectiveBoost     = similarityBoost × simRecencyMult
 *
 *   positiveWeight     = positiveListen + favoritePrior
 *   negativeWeight     = max(0, effectiveX) + negativeListen + dislikePrior
 *   directScore        = positiveWeight − negativeWeight
 *   effectivePositive  = positiveWeight + max(0,  effectiveBoost)
 *   effectiveNegative  = negativeWeight + max(0, -effectiveBoost)
 *   score              = directScore + effectiveBoost
 *
 * Manual priors (favoritePrior, dislikePrior) are NOT recency-decayed —
 * they reflect explicit, ongoing user intent. dislikePrior decays on
 * fullListens (handled by computeDislikePrior); favoritePrior decays on
 * plays (handled by computeFavoritePrior).
 */
fun computeDirectScore(
    plays: Int,
    avgFraction: Float,
    xScore: Float,
    favoritePrior: Float,
    dislikePrior: Float,
    similarityBoost: Float,
    lastPlayedAt: Long,
    xScoreUpdatedAt: Long = 0L,
    similarityBoostUpdatedAt: Long = 0L,
    lastUpdatedAt: Long = 0L,
    now: Long = System.currentTimeMillis(),
): TasteEngine.DirectScoreBreakdown {
    val daysSinceListen = if (lastPlayedAt > 0L)
        (now - lastPlayedAt).toFloat() / TasteEngine.PROFILE_DAY_MS
    else 0f
    val recencyMult = 0.5f.pow(daysSinceListen / TasteEngine.PROFILE_HALF_LIFE_DAYS)
        .coerceIn(0f, 1f)

    // Legacy data may have xScoreUpdatedAt == 0; fall back to
    // lastUpdatedAt so old persisted skips fade reasonably instead of
    // being treated as "today." When both are zero (Capacitor-migrated
    // signals with no timestamps at all), treat the xScore as 90 days
    // old so ancient skips decay heavily (xRecencyMult ≈ 0.125) rather
    // than carrying full weight indefinitely.
    val xRefTs = when {
        xScoreUpdatedAt > 0L -> xScoreUpdatedAt
        lastUpdatedAt > 0L -> lastUpdatedAt
        else -> now - (90L * TasteEngine.PROFILE_DAY_MS)
    }
    val xRecencyMult = run {
        val days = (now - xRefTs).toFloat() / TasteEngine.PROFILE_DAY_MS
        0.5f.pow(days / TasteEngine.PROFILE_HALF_LIFE_DAYS).coerceIn(0f, 1f)
    }

    val simRefTs = when {
        similarityBoostUpdatedAt > 0L -> similarityBoostUpdatedAt
        lastUpdatedAt > 0L -> lastUpdatedAt
        else -> now - (90L * TasteEngine.PROFILE_DAY_MS)
    }
    val simRecencyMult = run {
        val days = (now - simRefTs).toFloat() / TasteEngine.PROFILE_DAY_MS
        0.5f.pow(days / TasteEngine.PROFILE_HALF_LIFE_DAYS).coerceIn(0f, 1f)
    }

    val effectiveX = xScore * xRecencyMult
    val effectiveBoost = similarityBoost * simRecencyMult

    val positiveListen = if (plays > 0) plays * avgFraction * recencyMult else 0f
    val negativeListen = when {
        plays >= 2 && avgFraction < 0.5f -> plays * (1f - avgFraction) * recencyMult
        effectiveX > 0f && plays > 0 -> plays * (1f - avgFraction) * recencyMult * 0.5f
        // Push #73: single-encounter rejection. plays=1 with very low
        // avg means the user bailed almost immediately — surface it as
        // a half-weight negative signal even without a 2nd play.
        plays == 1 && avgFraction < 0.2f -> (1f - avgFraction) * recencyMult * 0.5f
        else -> 0f
    }
    val positiveWeight = positiveListen + favoritePrior
    val negativeWeight = maxOf(0f, effectiveX) + negativeListen + dislikePrior
    val direct = positiveWeight - negativeWeight
    return TasteEngine.DirectScoreBreakdown(
        positiveWeight = positiveWeight,
        negativeWeight = negativeWeight,
        effectivePositive = positiveWeight + maxOf(0f, effectiveBoost),
        effectiveNegative = negativeWeight + maxOf(0f, -effectiveBoost),
        directScore = direct,
        score = direct + effectiveBoost,
        recencyMult = recencyMult,
    )
}
*/

/**
 * Unified v2 scoring. Playback contribution is accumulated directly instead
 * of being reconstructed from a positive-only average, so PARTIAL evidence is
 * represented consistently in song, session, and profile calculations.
 */
fun computeUnifiedScore(
    positiveEvidence: Float,
    positiveEvidenceUpdatedAt: Long,
    xScore: Float,
    favoritePrior: Float,
    dislikePrior: Float,
    similarityBoost: Float,
    xScoreUpdatedAt: Long,
    similarityBoostUpdatedAt: Long,
    lastUpdatedAt: Long,
    now: Long = System.currentTimeMillis(),
): TasteEngine.DirectScoreBreakdown {
    fun recency(reference: Long, legacyAgeDays: Long = 90L): Float {
        val ref = when {
            reference > 0L -> reference
            lastUpdatedAt > 0L -> lastUpdatedAt
            else -> now - legacyAgeDays * TasteEngine.PROFILE_DAY_MS
        }
        val days = (now - ref).coerceAtLeast(0L).toFloat() / TasteEngine.PROFILE_DAY_MS
        return 0.5f.pow(days / TasteEngine.PROFILE_HALF_LIFE_DAYS).coerceIn(0f, 1f)
    }

    val positive = positiveEvidence.coerceAtLeast(0f) * recency(positiveEvidenceUpdatedAt, 0L)
    val avoidance = xScore.coerceAtLeast(0f) * recency(xScoreUpdatedAt)
    val similarity = (similarityBoost * recency(similarityBoostUpdatedAt))
        .coerceIn(-TasteEngine.SIMILARITY_BOOST_MAX, TasteEngine.SIMILARITY_BOOST_MAX)
    val positiveWeight = positive + favoritePrior
    val negativeWeight = avoidance + dislikePrior
    val direct = positiveWeight - negativeWeight
    return TasteEngine.DirectScoreBreakdown(
        positiveWeight = positiveWeight,
        negativeWeight = negativeWeight,
        effectivePositive = positiveWeight + maxOf(0f, similarity),
        effectiveNegative = negativeWeight + maxOf(0f, -similarity),
        directScore = direct,
        score = direct + similarity,
        recencyMult = recency(positiveEvidenceUpdatedAt, 0L),
    )
}

private const val PROFILE_ANCHOR_FAVORITE_SHARE = 0.40f
private const val PROFILE_ANCHOR_BEHAVIOR_SHARE = 0.60f

fun profileAnchorScore(
    signal: TasteEngine.TasteSignal,
    breakdown: TasteEngine.DirectScoreBreakdown,
): Float {
    if (breakdown.directScore <= 0f || breakdown.score <= 0f) return 0f

    val favoriteComponent = if (signal.isFavorite || signal.favoritePrior > 0f) 1f else 0f
    val ownPositive = (breakdown.positiveWeight - signal.favoritePrior).coerceAtLeast(0f)
    val positiveSimilarity = (breakdown.effectivePositive - breakdown.positiveWeight).coerceAtLeast(0f)
    val behaviorComponent = ((ownPositive + positiveSimilarity) /
        UnifiedTasteContract.TASTE_SCORE_NORMALIZER).coerceIn(0f, 1f)

    return (PROFILE_ANCHOR_FAVORITE_SHARE * favoriteComponent) +
        (PROFILE_ANCHOR_BEHAVIOR_SHARE * behaviorComponent)
}

/**
 * Stats-only fallback used by the Taste page for songs that have history
 * data but no TasteSignal entry yet.
 */
fun signedDirectScore(plays: Int, avgFraction: Float): Float {
    if (plays <= 0) return 0f
    return plays * avgFraction
}
