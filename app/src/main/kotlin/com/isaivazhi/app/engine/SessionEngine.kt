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
 *     across the session; drives the "Session pull X% → Y%" timeline line.
 */
class SessionEngine {

    data class SessionCounters(
        val encounters: Int = 0,
        val skips: Int = 0,
        val positives: Int = 0,
        val weightFraction: Float = 0f,
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

    /**
     * Push #64: rolling tick of qualified non-skip listens since the last
     * For You refresh. Capacitor parity (`tickForYouListenWindow`): after
     * 5 qualified listens within a session, the recommender's "For You"
     * cache is invalidated so the page tracks where the user's session
     * mood has drifted. The Discover LE keys on this counter; when it
     * hits 5 it triggers an auto-refresh and immediately calls
     * [resetForYouTick] to start the next window.
     */
    private val _forYouTick = MutableStateFlow(0)
    val forYouTick: StateFlow<Int> = _forYouTick.asStateFlow()

    fun current(): SessionCounters = _counters.value

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
        val before = _counters.value
        val frac = fraction.coerceIn(0f, 1f)
        val newEncounters = before.encounters + 1
        val newSkips = if (isSkip && !isManual) before.skips + 1 else before.skips
        val newPositives = if (!isSkip) before.positives + 1 else before.positives
        val newWeight = if (newEncounters == 0) 0f
            else ((before.weightFraction * before.encounters) + frac) / newEncounters
        val after = SessionCounters(
            encounters = newEncounters,
            skips = newSkips,
            positives = newPositives,
            weightFraction = newWeight,
        )
        _counters.value = after
        // Push #67: append to the rolling listened list (capped). This
        // drives the session vector used by the Recommender's blended
        // query. Skip events with frac < 0.10 (noise) are excluded.
        if (filename.isNotBlank() && frac >= 0.10f) {
            val entry = ListenedEntry(filename, frac, source, System.currentTimeMillis())
            val newList = (_listened.value + entry).takeLast(MAX_RECENT_LISTENS)
            _listened.value = newList
        }
        // Push #64: only "qualified" listens (non-skip, non-manual, >=50%)
        // count toward the For You auto-refresh window.
        if (!isSkip && !isManual && frac >= 0.5f) {
            _forYouTick.value = _forYouTick.value + 1
        }
        return before to after
    }

    /**
     * Push #64: called by Discover LE after firing an auto-refresh so the
     * next 5 qualified listens start a fresh window.
     */
    fun resetForYouTick() { _forYouTick.value = 0 }

    /** Wipe counters — paired with Reset Engine. */
    fun reset() {
        _counters.value = SessionCounters()
        _forYouTick.value = 0
        _listened.value = emptyList()
    }
}
