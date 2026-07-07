package com.isaivazhi.app.engine

/**
 * Pure blend-weight schedule for Up Next (Capacitor parity). Extracted
 * for JVM unit tests without [EmbeddingDbFacade].
 */
internal object BlendWeightLogic {

    data class Weights(
        val wCurrent: Float,
        val wSession: Float,
        val wProfile: Float,
    )

    fun compute(
        mode: String,
        nListened: Float,
        hasCurrent: Boolean,
        hasSession: Boolean,
        hasProfile: Boolean,
    ): Weights {
        @Suppress("UNUSED_VARIABLE")
        val ignoredMode = mode
        // Let the explicitly chosen song guide the opening recs, then
        // hand control to session evidence within the first few listens.
        val progress = (nListened / 5f).coerceIn(0f, 1f)
        val current = if (hasCurrent) 0.35f * (1f - progress) else 0f
        val remaining = 1f - current
        val session = if (hasSession) remaining * 0.60f * progress else 0f
        val profile = if (hasProfile) (remaining - session).coerceAtLeast(0f) else 0f
        return normalizeAvailable(current, session, profile, hasCurrent, hasSession, hasProfile)
    }

    fun computeManual(
        currentWeight: Float,
        sessionWeight: Float,
        hasCurrent: Boolean,
        hasSession: Boolean,
        hasProfile: Boolean,
    ): Weights {
        val current = currentWeight.coerceIn(0f, 1f)
        val session = sessionWeight.coerceIn(0f, 1f - current)
        val profile = (1f - current - session).coerceAtLeast(0f)
        return normalizeAvailable(current, session, profile, hasCurrent, hasSession, hasProfile)
    }

    private fun normalizeAvailable(
        current: Float,
        session: Float,
        profile: Float,
        hasCurrent: Boolean,
        hasSession: Boolean,
        hasProfile: Boolean,
    ): Weights {
        var c = if (hasCurrent) current else 0f
        var s = if (hasSession) session else 0f
        var p = if (hasProfile) profile else 0f
        var total = c + s + p
        if (total <= 0f) {
            c = if (hasCurrent) 1f else 0f
            s = if (!hasCurrent && hasSession) 1f else 0f
            p = if (!hasCurrent && !hasSession && hasProfile) 1f else 0f
            total = c + s + p
        }
        return if (total > 0f) Weights(c / total, s / total, p / total)
        else Weights(0f, 0f, 0f)
    }

    fun compute(
        mode: String,
        nListened: Int,
        hasCurrent: Boolean,
        hasSession: Boolean,
        hasProfile: Boolean,
    ): Weights = compute(mode, nListened.toFloat(), hasCurrent, hasSession, hasProfile)
}
