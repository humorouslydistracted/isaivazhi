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
        nListened: Int,
        hasCurrent: Boolean,
        hasSession: Boolean,
        hasProfile: Boolean,
    ): Weights {
        if (mode == "refresh") {
            if (hasSession && hasProfile && hasCurrent) return Weights(0.30f, 0.40f, 0.30f)
            if (hasSession && hasProfile) return Weights(0f, 0.60f, 0.40f)
            if (hasSession && hasCurrent) return Weights(0.30f, 0.70f, 0f)
            if (hasSession) return Weights(0f, 1.0f, 0f)
            if (hasProfile && hasCurrent) return Weights(0.40f, 0f, 0.60f)
            if (hasProfile) return Weights(0f, 0f, 1.0f)
            return Weights(1.0f, 0f, 0f)
        }
        if (!hasCurrent) {
            return Weights(
                0f,
                if (hasSession) 0.6f else 0f,
                if (hasProfile) (if (hasSession) 0.4f else 1f) else 0f,
            )
        }
        if (!hasSession && !hasProfile) return Weights(1.0f, 0f, 0f)
        if (!hasSession) {
            val t = (nListened.toFloat() / 8f).coerceAtMost(1f)
            val wCurrent = 0.5f + 0.1f * t
            return Weights(wCurrent, 0f, 1f - wCurrent)
        }
        if (!hasProfile) {
            val t = (nListened.toFloat() / 10f).coerceAtMost(1f)
            val wCurrent = 0.6f - 0.2f * t
            return Weights(wCurrent, 1f - wCurrent, 0f)
        }
        val t = (nListened.toFloat() / 10f).coerceAtMost(1f)
        var wCurrent = 0.50f - 0.12f * t
        var wSession = 0.52f * t
        var wProfile = 1f - wCurrent - wSession
        if (wProfile < 0.08f) wProfile = 0.08f
        val total = wCurrent + wSession + wProfile
        return Weights(wCurrent / total, wSession / total, wProfile / total)
    }
}
