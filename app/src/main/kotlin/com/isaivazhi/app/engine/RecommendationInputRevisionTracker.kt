package com.isaivazhi.app.engine

import java.util.concurrent.atomic.AtomicLong

class RecommendationInputRevisionTracker(initialRevision: Long = 0L) {
    private val revision = AtomicLong(initialRevision)

    fun currentRevision(): Long = revision.get()

    fun invalidate(): Long = revision.incrementAndGet()
}
