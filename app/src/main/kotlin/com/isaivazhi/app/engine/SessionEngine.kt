package com.isaivazhi.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Push #63: in-memory session counters used by the SignalTimeline event
 * snapshot. Captures the rolling "since app launch" totals that Capacitor
 * exposes as `sessionBefore` / `sessionAfter` in playback signal updates.
 *
 * Not persisted — resets on app process death. That matches Capacitor's
 * sessionState lifecycle: a session is a single uninterrupted run of the
 * app, not a calendar day.
 *
 * Counter semantics:
 *   - `encounters` increments on every recorded playback event (skip or listen)
 *   - `skips` increments only when the event was classified as a real skip
 *     (fraction < 0.50 AND non-manual source)
 *   - `positives` increments on any non-skip listen
 *   - `weightFraction` is the running average of all recorded fractions
 *     across the session and remains available for diagnostics.
 */
class SessionEngine {

    companion object {
        const val INACTIVITY_TIMEOUT_MS: Long = 2 * 60 * 60 * 1000L
    }

    data class SessionCounters(
        val encounters: Int = 0,
        val skips: Int = 0,
        val positives: Int = 0,
        val weightFraction: Float = 0f,
        val early: Int = 0,
        val partial: Int = 0,
        val listens: Int = 0,
        val strong: Int = 0,
        val evidenceUnits: Float = 0f,
    )

    /**
     * Push #67: a single recent listen in the rolling session window.
     * Capacitor parity (`state.listened` in engine.js). The session
     * vector is computed as the weighted average of these entries'
     * embeddings.
     */
    data class ListenedEntry(
        val filename: String,
        val fraction: Float,
        val source: String,
        val timestamp: Long,
        val contribution: Float = fraction,
        val evidenceUnits: Float = 1f,
        val outcome: PlaybackOutcome = UnifiedTasteContract.outcomeFor(fraction),
    )

    /** Max retained session listen entries (Capacitor MAX_RECENT_PLAYBACK_SIGNALS=60). */
    private val MAX_RECENT_LISTENS = 60

    private val _counters = MutableStateFlow(SessionCounters())
    val counters: StateFlow<SessionCounters> = _counters.asStateFlow()

    /**
     * Push #67: rolling window of the last [MAX_RECENT_LISTENS] events.
     * Resets on app start (session-scoped). The Recommender reads this
     * to build the session vector for the blended recommendation query.
     */
    private val _listened = MutableStateFlow<List<ListenedEntry>>(emptyList())
    val listened: StateFlow<List<ListenedEntry>> = _listened.asStateFlow()

    private val _explicitAnchorFilename = MutableStateFlow<String?>(null)
    val explicitAnchorFilename: StateFlow<String?> = _explicitAnchorFilename.asStateFlow()

    @Volatile private var lastActivityAt: Long = 0L

    fun current(): SessionCounters = _counters.value

    @Synchronized
    fun ensureActiveSession(now: Long = System.currentTimeMillis()) {
        if (lastActivityAt > 0L && now - lastActivityAt >= INACTIVITY_TIMEOUT_MS) {
            resetInternal()
        }
    }

    @Synchronized
    fun markPlaybackActivity(now: Long = System.currentTimeMillis()) {
        ensureActiveSession(now)
        lastActivityAt = now
    }

    fun markExplicitAnchor(filename: String, reason: String = "unknown") {
        markPlaybackActivity()
        _explicitAnchorFilename.value = filename.takeIf { it.isNotBlank() }
        android.util.Log.d(
            "BlendDebug",
            "markExplicitAnchor filename=$filename reason=$reason",
        )
    }

    fun clearExplicitAnchor(reason: String = "unknown") {
        android.util.Log.d(
            "BlendDebug",
            "clearExplicitAnchor prev=${_explicitAnchorFilename.value} reason=$reason",
        )
        _explicitAnchorFilename.value = null
    }

    /**
     * Record a playback event into the session counters. Returns the
     * before/after snapshot pair so callers can stamp them into the
     * SignalTimeline event.
     */
    fun recordEvent(
        fraction: Float,
        isSkip: Boolean,
        isManual: Boolean,
        filename: String = "",
        source: String = "",
    ): Pair<SessionCounters, SessionCounters> {
        val now = System.currentTimeMillis()
        ensureActiveSession(now)
        val before = _counters.value
        val frac = fraction.coerceIn(0f, 1f)
        val outcome = UnifiedTasteContract.outcomeFor(frac)
        val contribution = UnifiedTasteContract.positiveContribution(frac)
        val newEncounters = before.encounters + 1
        // Transport/recovery reasons no longer imply preference. Preserve the
        // legacy field for compatibility, but explicit skip gestures should
        // still surface in session diagnostics.
        val newSkips = if (isSkip && !isManual) before.skips + 1 else before.skips
        val newPositives = if (contribution > 0f) before.positives + 1 else before.positives
        val newWeight = if (newEncounters == 0) 0f
            else ((before.weightFraction * before.encounters) + frac) / newEncounters
        val after = SessionCounters(
            encounters = newEncounters,
            skips = newSkips,
            positives = newPositives,
            weightFraction = newWeight,
            early = before.early + if (outcome == PlaybackOutcome.EARLY) 1 else 0,
            partial = before.partial + if (outcome == PlaybackOutcome.PARTIAL) 1 else 0,
            listens = before.listens + if (outcome == PlaybackOutcome.LISTEN) 1 else 0,
            strong = before.strong + if (outcome == PlaybackOutcome.STRONG) 1 else 0,
            evidenceUnits = before.evidenceUnits + outcome.evidenceUnits,
        )
        _counters.value = after
        // Only outcomes with positive evidence enter the session vector.
        if (filename.isNotBlank() && contribution > 0f) {
            val entry = ListenedEntry(
                filename = filename,
                fraction = frac,
                source = source,
                timestamp = now,
                contribution = contribution,
                evidenceUnits = outcome.evidenceUnits,
                outcome = outcome,
            )
            val newList = (_listened.value + entry).takeLast(MAX_RECENT_LISTENS)
            _listened.value = newList
        }
        lastActivityAt = now
        return before to after
    }

    /** Wipe counters — paired with Reset Engine. */
    fun reset() {
        resetInternal()
        lastActivityAt = 0L
    }

    private fun resetInternal() {
        _counters.value = SessionCounters()
        _listened.value = emptyList()
        _explicitAnchorFilename.value = null
    }
}
