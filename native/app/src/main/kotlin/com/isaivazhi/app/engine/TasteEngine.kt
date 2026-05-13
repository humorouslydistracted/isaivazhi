package com.isaivazhi.app.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 *      [0.10, 0.09, …, 0.01], clamped to ±4.
 *   4. Recency multiplier: `0.5^(daysSince/30)` on listen weights.
 *   5. Hard-block list exposed via `decoratedSignals` for the
 *      Recommender to filter on.
 *   6. Per-song reset clears everything except isFavorite/isDisliked
 *      flags (those live in FavoritesEngine/DislikedEngine anyway).
 */
class TasteEngine(private val appContext: Context) {

    data class Tuning(
        val adventurous: Float = DEFAULT_ADVENTUROUS,
        val sessionBias: Float = DEFAULT_SESSION_BIAS,
        val negativeStrength: Float = DEFAULT_NEGATIVE_STRENGTH,
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
    )

    data class DecoratedSignals(
        val rows: Map<String, DecoratedRow>,
        val hardBlockedFilenames: Set<String>,
        val positiveActiveCount: Int,
        val negativeActiveCount: Int,
        val computedAt: Long,
    )

    companion object {
        const val DEFAULT_ADVENTUROUS = 0.8f
        const val DEFAULT_SESSION_BIAS = 0.5f
        const val DEFAULT_NEGATIVE_STRENGTH = 0.5f

        // Capacitor parity (engine-state.js): songs played < 50% are SKIPS;
        // strong listens at ≥ 70% decay xScore more aggressively.
        const val SKIP_THRESHOLD = 0.50f
        const val FULL_LISTEN_THRESHOLD = 0.70f

        // Push #63 (Capacitor parity): xScore deltas.
        // engine-state.js: USER_SKIP_NEGATIVE_STEP=0.1, NEG_LISTEN_DECAY=0.5,
        // NEG_X_DELTA=0.5 (queue-remove), NEG_SCORE_MAX=10.
        private const val USER_SKIP_NEGATIVE_STEP = 0.1f
        private const val NEG_LISTEN_DECAY = 0.5f
        private const val NEG_X_DELTA = 0.5f
        private const val NEG_SCORE_MAX = 10f

        // Prior coefficients from engine-favorites.js / engine-disliked.js.
        private const val FAVORITE_PRIOR_BASE = 2.0f
        private const val DISLIKE_PRIOR_BASE = 3.0f
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
        const val SIMILARITY_BOOST_MAX = 4.0f

        // Hard-block thresholds (engine-state.js:20-21).
        const val HARD_EXCLUDE_SHARE = 0.18f
        const val HARD_EXCLUDE_FLOOR = 1.5f

        // Decoration ranking window (engine-taste.js:644-660).
        private const val TOP_RANK_LIMIT = 30
    }

    private val KEY_ADV = floatPreferencesKey("taste_adventurous")
    private val KEY_NEG = floatPreferencesKey("taste_negative_strength")
    private val KEY_SES = floatPreferencesKey("taste_session_bias")
    private val KEY_FAM_LEGACY = floatPreferencesKey("taste_familiarity")
    private val KEY_SIGNALS = stringPreferencesKey("taste_signals_v1_json")

    private val _tuning = MutableStateFlow(Tuning())
    val tuning: StateFlow<Tuning> = _tuning.asStateFlow()

    private val _signals = MutableStateFlow<Map<String, TasteSignal>>(emptyMap())
    val signals: StateFlow<Map<String, TasteSignal>> = _signals.asStateFlow()

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

    init {
        scope.launch {
            try {
                val data = appContext.dataStoreLocal.data.first()
                val legacyFam = data[KEY_FAM_LEGACY]
                _tuning.value = Tuning(
                    adventurous = data[KEY_ADV] ?: DEFAULT_ADVENTUROUS,
                    sessionBias = data[KEY_SES] ?: legacyFam ?: DEFAULT_SESSION_BIAS,
                    negativeStrength = data[KEY_NEG] ?: DEFAULT_NEGATIVE_STRENGTH,
                )
                data[KEY_SIGNALS]?.let { _signals.value = parseSignals(it) }
                refreshDecorated()
            } catch (t: Throwable) {
                android.util.Log.w("TasteEngine", "load failed: ${t.message}")
            }
        }
    }

    fun setAdventurous(value: Float) {
        _tuning.value = _tuning.value.copy(adventurous = value.coerceIn(0f, 1f))
        scope.launch { persistTuning() }
    }

    fun setSessionBias(value: Float) {
        _tuning.value = _tuning.value.copy(sessionBias = value.coerceIn(0f, 1f))
        scope.launch { persistTuning() }
    }

    fun setNegativeStrength(value: Float) {
        _tuning.value = _tuning.value.copy(negativeStrength = value.coerceIn(0f, 1f))
        scope.launch { persistTuning() }
    }

    fun resetAdventurous() = setAdventurous(DEFAULT_ADVENTUROUS)
    fun resetSessionBias() = setSessionBias(DEFAULT_SESSION_BIAS)
    fun resetNegativeStrength() = setNegativeStrength(DEFAULT_NEGATIVE_STRENGTH)

    fun signalFor(filename: String): TasteSignal = _signals.value[filename] ?: TasteSignal()

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
    ): Pair<TasteSignal, TasteSignal> {
        val before = signalFor(filename)
        val frac = fraction.coerceIn(0f, 1f)
        val isManual = source.startsWith("manual_") || source == "song_tap" ||
            source == "queue_tap" || source == "neutral_skip"
        val isSkip = frac < SKIP_THRESHOLD
        val isFullListen = frac >= FULL_LISTEN_THRESHOLD
        val now = System.currentTimeMillis()

        // Plays counter: only increments on non-skip (fraction >= 0.50).
        val newPlays = if (isSkip) before.plays else before.plays + 1
        val newSkips = if (isSkip && !isManual) before.skips + 1 else before.skips
        // avgFraction tracks only non-skip listens. When the event is a
        // skip, the existing avgFraction is preserved.
        val newAvgFrac = when {
            isSkip -> before.avgFraction
            before.plays == 0 -> frac
            else -> ((before.avgFraction * before.plays) + frac) / newPlays
        }
        val newX = when {
            isSkip && !isManual ->
                (before.xScore + USER_SKIP_NEGATIVE_STEP).coerceAtMost(NEG_SCORE_MAX)
            isFullListen ->
                (before.xScore - NEG_LISTEN_DECAY).coerceAtLeast(0f)
            else -> before.xScore
        }
        // Recompute manual priors against the new plays count.
        val newFavPrior = if (before.isFavorite) computeFavoritePrior(newPlays) else 0f
        val newDisPrior = if (before.isDisliked) computeDislikePrior(newPlays) else 0f
        // lastPlayedAt — only stamp on real listens, not skips. A skip
        // doesn't mean "I played this," it means "I rejected this."
        val newLastPlayedAt = if (isSkip) before.lastPlayedAt else now
        val newBreakdown = computeDirectScore(
            plays = newPlays,
            avgFraction = newAvgFrac,
            xScore = newX,
            favoritePrior = newFavPrior,
            dislikePrior = newDisPrior,
            similarityBoost = before.similarityBoost,
            lastPlayedAt = newLastPlayedAt,
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
        )
        android.util.Log.i(
            "TasteEngine",
            "recordPlaybackEvent fn=$filename frac=$frac src=$source isSkip=$isSkip isFull=$isFullListen " +
                "plays=${before.plays}→$newPlays skips=${before.skips}→$newSkips avg=${"%.2f".format(before.avgFraction)}→${"%.2f".format(newAvgFrac)} " +
                "x=${"%.2f".format(before.xScore)}→${"%.2f".format(newX)} direct=${"%.2f".format(before.directScore)}→${"%.2f".format(newBreakdown.directScore)} recencyMult=${"%.2f".format(newBreakdown.recencyMult)}"
        )

        _signals.value = _signals.value + (filename to after)
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
        val newDisPrior = if (isDisliked) computeDislikePrior(before.plays) else 0f
        val now = System.currentTimeMillis()
        val newBreakdown = computeDirectScore(
            plays = before.plays,
            avgFraction = before.avgFraction,
            xScore = before.xScore,
            favoritePrior = newFavPrior,
            dislikePrior = newDisPrior,
            similarityBoost = before.similarityBoost,
            lastPlayedAt = before.lastPlayedAt,
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
        val newX = (before.xScore + NEG_X_DELTA).coerceAtMost(NEG_SCORE_MAX)
        val now = System.currentTimeMillis()
        val newBreakdown = computeDirectScore(
            plays = before.plays,
            avgFraction = before.avgFraction,
            xScore = newX,
            favoritePrior = before.favoritePrior,
            dislikePrior = before.dislikePrior,
            similarityBoost = before.similarityBoost,
            lastPlayedAt = before.lastPlayedAt,
            now = now,
        )
        val after = before.copy(
            xScore = newX,
            directScore = newBreakdown.directScore,
            lastUpdatedAt = now,
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
     * Each neighbor's accumulated similarityBoost is clamped to ±4.
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
    ) {
        if (sourceFilename.isBlank() || abs(scoreDelta) < 0.001f) return
        val lookup = neighborLookup ?: run {
            android.util.Log.w("TasteEngine", "propagate src=$sourceFilename Δ=$scoreDelta reason=$reason: no neighborLookup wired")
            return
        }
        val neighbors = try { lookup(sourceFilename, SIMILARITY_NEIGHBOR_COUNT) }
            catch (t: Throwable) {
                android.util.Log.w("TasteEngine", "propagate: lookup failed: ${t.message}")
                return
            }
        if (neighbors.isEmpty()) return
        val now = System.currentTimeMillis()
        var changedCount = 0
        var next = _signals.value
        val limit = minOf(neighbors.size, SIMILARITY_NEIGHBOR_WEIGHTS.size)
        for (i in 0 until limit) {
            val nfn = neighbors[i]
            if (nfn == sourceFilename) continue
            val w = SIMILARITY_NEIGHBOR_WEIGHTS[i]
            val delta = scoreDelta * w
            if (abs(delta) < 0.001f) continue
            val cur = next[nfn] ?: TasteSignal()
            val newBoost = (cur.similarityBoost + delta).coerceIn(-SIMILARITY_BOOST_MAX, SIMILARITY_BOOST_MAX)
            if (newBoost == cur.similarityBoost) continue
            val nb = computeDirectScore(
                plays = cur.plays,
                avgFraction = cur.avgFraction,
                xScore = cur.xScore,
                favoritePrior = cur.favoritePrior,
                dislikePrior = cur.dislikePrior,
                similarityBoost = newBoost,
                lastPlayedAt = cur.lastPlayedAt,
                now = now,
            )
            next = next + (nfn to cur.copy(
                similarityBoost = newBoost,
                directScore = nb.directScore,
                lastUpdatedAt = now,
            ))
            changedCount++
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
    }

    /**
     * Per-song reset on the Taste page. Push #63: full clear.
     * isFavorite/isDisliked are held by FavoritesEngine/DislikedEngine
     * (separate stores) and therefore survive automatically.
     */
    fun resetSignalForSong(filename: String) {
        if (filename.isBlank() || filename !in _signals.value) return
        _signals.value = _signals.value - filename
        scope.launch { persistSignals() }
        refreshDecorated()
        android.util.Log.i("TasteEngine", "resetSignalForSong fn=$filename")
    }

    /** Clears every per-song signal — paired with Reset Engine. */
    fun resetAllSignals() {
        _signals.value = emptyMap()
        scope.launch { persistSignals() }
        refreshDecorated()
    }

    private fun computeFavoritePrior(plays: Int): Float {
        val w = FAVORITE_PRIOR_BASE * 0.5f.pow(plays / MANUAL_PRIOR_HALF_LIFE_PLAYS)
        return if (w >= MANUAL_PRIOR_MIN) w else 0f
    }

    private fun computeDislikePrior(plays: Int): Float {
        val w = DISLIKE_PRIOR_BASE * 0.5f.pow(plays / MANUAL_PRIOR_HALF_LIFE_PLAYS)
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
                computeDirectScore(
                    plays = sig.plays,
                    avgFraction = sig.avgFraction,
                    xScore = sig.xScore,
                    favoritePrior = sig.favoritePrior,
                    dislikePrior = sig.dislikePrior,
                    similarityBoost = sig.similarityBoost,
                    lastPlayedAt = sig.lastPlayedAt,
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
        // Step 3: hard-block — top 18% of negatives by |directScore| with
        // floor 1.5. (Filepath/embedding gate happens at Recommender call
        // site; we just expose the filename set here.)
        val negativeStrongRows = rows.filter { it.bd.directScore < 0f }
            .sortedByDescending { abs(it.bd.directScore) }
        val hardBlocked = HashSet<String>()
        if (negativeStrongRows.isNotEmpty()) {
            val cap = max(1, ceil(negativeStrongRows.size * HARD_EXCLUDE_SHARE).toInt())
            for (i in 0 until minOf(cap, negativeStrongRows.size)) {
                if (abs(negativeStrongRows[i].bd.directScore) >= HARD_EXCLUDE_FLOOR) {
                    hardBlocked += negativeStrongRows[i].fn
                }
            }
        }
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
            if (r.bd.directScore > 0f) posCount++
            else if (r.bd.directScore < 0f) negCount++
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
                it[KEY_SES] = t.sessionBias
                it[KEY_NEG] = t.negativeStrength
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
                })
            }
            appContext.dataStoreLocal.edit { it[KEY_SIGNALS] = root.toString() }
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
                )
            }
            out
        } catch (t: Throwable) {
            android.util.Log.w("TasteEngine", "parseSignals failed: ${t.message}")
            emptyMap()
        }
    }
}

/**
 * Capacitor parity directScore (engine-taste.js:391-487).
 *
 *   recencyMult        = 0.5^(daysSince / PROFILE_HALF_LIFE_DAYS)
 *   positiveListen     = plays × avgFraction × recencyMult  (plays > 0)
 *   negativeListen     = plays × (1 − avgFraction) × recencyMult
 *                          when plays ≥ NEGATIVE_PLAY_THRESHOLD (2) AND
 *                               avgFraction < NEGATIVE_FRAC_THRESHOLD (0.5)
 *                        plays × (1 − avgFraction) × recencyMult × 0.5
 *                          when xScore > 0 (already-penalized song)
 *                        0 otherwise
 *   positiveWeight     = positiveListen + favoritePrior
 *   negativeWeight     = max(0, xScore) + negativeListen + dislikePrior
 *   directScore        = positiveWeight − negativeWeight
 *   effectivePositive  = positiveWeight + max(0,  similarityBoost)
 *   effectiveNegative  = negativeWeight + max(0, -similarityBoost)
 *   score              = directScore + similarityBoost
 *
 * Manual priors (favoritePrior, dislikePrior) are NOT recency-decayed —
 * they reflect explicit, ongoing user intent.
 */
fun computeDirectScore(
    plays: Int,
    avgFraction: Float,
    xScore: Float,
    favoritePrior: Float,
    dislikePrior: Float,
    similarityBoost: Float,
    lastPlayedAt: Long,
    now: Long = System.currentTimeMillis(),
): TasteEngine.DirectScoreBreakdown {
    val daysSince = if (lastPlayedAt > 0L)
        (now - lastPlayedAt).toFloat() / TasteEngine.PROFILE_DAY_MS
    else 0f
    val recencyMult = 0.5f.pow(daysSince / TasteEngine.PROFILE_HALF_LIFE_DAYS)
        .coerceIn(0f, 1f)
    val positiveListen = if (plays > 0) plays * avgFraction * recencyMult else 0f
    val negativeListen = when {
        plays >= 2 && avgFraction < 0.5f -> plays * (1f - avgFraction) * recencyMult
        xScore > 0f && plays > 0 -> plays * (1f - avgFraction) * recencyMult * 0.5f
        else -> 0f
    }
    val positiveWeight = positiveListen + favoritePrior
    val negativeWeight = maxOf(0f, xScore) + negativeListen + dislikePrior
    val direct = positiveWeight - negativeWeight
    return TasteEngine.DirectScoreBreakdown(
        positiveWeight = positiveWeight,
        negativeWeight = negativeWeight,
        effectivePositive = positiveWeight + maxOf(0f, similarityBoost),
        effectiveNegative = negativeWeight + maxOf(0f, -similarityBoost),
        directScore = direct,
        score = direct + similarityBoost,
        recencyMult = recencyMult,
    )
}

/**
 * Stats-only fallback used by the Taste page for songs that have history
 * data but no TasteSignal entry yet. Equivalent to the positive arm of
 * [computeDirectScore] with priors=0 and recencyMult=1.
 */
fun signedDirectScore(plays: Int, avgFraction: Float): Float {
    if (plays <= 0) return 0f
    return plays * avgFraction
}
