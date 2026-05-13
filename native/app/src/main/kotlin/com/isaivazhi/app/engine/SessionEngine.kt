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

    private val _counters = MutableStateFlow(SessionCounters())
    val counters: StateFlow<SessionCounters> = _counters.asStateFlow()

    fun current(): SessionCounters = _counters.value

    /**
     * Record a playback event into the session counters. Returns the
     * before/after snapshot pair so callers can stamp them into the
     * SignalTimeline event.
     */
    fun recordEvent(fraction: Float, isSkip: Boolean, isManual: Boolean): Pair<SessionCounters, SessionCounters> {
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
        return before to after
    }

    /** Wipe counters — paired with Reset Engine. */
    fun reset() {
        _counters.value = SessionCounters()
    }
}
