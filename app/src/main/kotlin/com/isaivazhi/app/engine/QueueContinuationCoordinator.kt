package com.isaivazhi.app.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Appends AI/shuffle recommendations when the playback queue reaches its last
 * song or ends, mirroring Capacitor `_doRefresh('queue_exhausted')`.
 *
 * Runs on [appScope] so queue-end rescue survives Activity stop/destroy.
 */
class QueueContinuationCoordinator(
    private val container: AppContainer,
    private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    @Volatile
    private var preappendJob: Job? = null

    private val _lastBlendInfo = MutableStateFlow<RecommendationBlendInfo?>(null)
    val lastBlendInfo: StateFlow<RecommendationBlendInfo?> = _lastBlendInfo.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            container.playback.queueEndedEvents.collect {
                appScope.launch(Dispatchers.Default) {
                    appendRecommendations(
                        trigger = "queue_ended_event",
                        playFirstWhenStopped = true,
                        debounceMs = 0L,
                        drainLedgerFirst = true,
                    )
                }
            }
        }
    }

    /** Called when playback state indicates the user is on the last queue item. */
    fun requestPreappend() {
        preappendJob?.cancel()
        preappendJob = appScope.launch(Dispatchers.Default) {
            appendRecommendations(
                trigger = "last_item_preappend",
                playFirstWhenStopped = false,
                debounceMs = 500L,
                drainLedgerFirst = false,
            )
        }
    }

    private suspend fun appendRecommendations(
        trigger: String,
        playFirstWhenStopped: Boolean,
        debounceMs: Long,
        drainLedgerFirst: Boolean,
    ) {
        var stateNow = container.playback.state.value
        val mediaId = stateNow.currentMediaId ?: return
        val queueSize = stateNow.queueFilenames.size
        if (queueSize == 0) return
        val curIdx = stateNow.queueIndex
        if (curIdx < queueSize - 1) {
            container.activityLog.log(
                category = "engine",
                type = "QUEUE_EXHAUST_SKIP",
                message = "not on last item (curIdx=$curIdx of $queueSize, ctx=${stateNow.queueContext})",
                data = mapOf(
                    "reason" to "not_last",
                    "curIdx" to curIdx,
                    "queueSize" to queueSize,
                    "trigger" to trigger,
                ),
            )
            return
        }

        val ctx = stateNow.queueContext
        if (QueueContinuationPolicy.shouldForceRepeatOffForContinuation(ctx, stateNow.repeatMode)) {
            container.playback.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF)
            stateNow = container.playback.state.value
        }
        if (!QueueContinuationPolicy.shouldAppendAiTail(ctx, stateNow.repeatMode)) {
            val reason = if (stateNow.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) {
                "loop_on"
            } else {
                "finite_section"
            }
            container.activityLog.log(
                category = "engine",
                type = "QUEUE_EXHAUST_SKIP",
                message = if (reason == "loop_on") {
                    "repeat=${stateNow.repeatMode} (OFF=0 ONE=1 ALL=2)"
                } else {
                    "section context $ctx (no AI ever)"
                },
                data = mapOf(
                    "reason" to reason,
                    "repeatMode" to stateNow.repeatMode,
                    "ctx" to ctx.name,
                    "trigger" to trigger,
                ),
            )
            return
        }

        val songs = container.library.value
        val current = songs.firstOrNull { it.filename == mediaId } ?: return

        if (debounceMs > 0L) {
            kotlinx.coroutines.delay(debounceMs)
            stateNow = container.playback.state.value
            if (stateNow.currentMediaId != mediaId) return
            if (stateNow.queueIndex < stateNow.queueFilenames.size - 1) return
        }

        if (drainLedgerFirst) {
            runCatching {
                container.playbackSignalProcessor.processPending(
                    songs = songs,
                    reason = trigger,
                    logWhenEmpty = false,
                )
            }.onFailure {
                android.util.Log.w("QueueExhaust", "ledger drain failed before $trigger: ${it.message}")
            }
        }

        val tuning = container.taste.tuning.value
        val recMode = container.preferences.recMode.first()
        val embeddingRows = runCatching { container.embeddingDb.rowCount() }.getOrDefault(0)

        android.util.Log.i(
            "QueueExhaust",
            "appending tail: trigger=$trigger playFirst=$playFirstWhenStopped ctx=$ctx " +
                "queueSize=$queueSize mediaId=$mediaId aiMode=$recMode emb=$embeddingRows",
        )

        val recentlySurfaced = container.recentlySurfacedTracker.recentlySurfaced()
        val excludeFns = QueueContinuationPolicy.recommendExcludeFilenames(
            playNextFilenames = stateNow.playNextFilenames,
            currentFilename = mediaId,
            recentlySurfaced = recentlySurfaced,
        )
        val blendedTriple = runCatching {
            container.recommender.buildBlendedVec(
                currentSongHash = current.contentHash,
                sessionListened = container.session.listened.value,
                profileVec = runCatching { container.preferences.loadProfileVec() }.getOrNull()?.vec,
                library = songs,
                mode = "play",
                sessionBias = tuning.sessionBias,
            )
        }.getOrNull()
        blendedTriple?.let { (_, weights, label) ->
            _lastBlendInfo.value = RecommendationBlendInfo(weights, label)
        }
        val blendedVec = blendedTriple?.first
        val tail = if (recMode && embeddingRows > 0) {
            runCatching {
                container.recommender.recommendUpcoming(
                    currentSong = current,
                    library = songs,
                    k = 50,
                    adventurous = tuning.adventurous,
                    extraExcludeFilenames = excludeFns,
                    hardBlockedFilenames = container.taste.hardBlockedFilenamesForPolicy(tuning.negativeStrength),
                    dislikedFilenames = container.disliked.disliked.value,
                    softExcludedFilenames = container.taste.softExcludedFilenamesForPolicy(tuning.negativeStrength),
                    blendedQueryVec = blendedVec,
                )
            }.getOrDefault(emptyList())
        } else {
            songs.filter { it.filePath != null && it.filename !in excludeFns }
                .shuffled()
                .take(50)
        }

        if (tail.isEmpty()) {
            container.activityLog.log(
                category = "engine",
                type = "QUEUE_EXHAUST_EMPTY",
                message = "No tracks to append at queue end (exclude=${excludeFns.size}, library=${songs.size})",
                data = mapOf(
                    "trigger" to trigger,
                    "excludeCount" to excludeFns.size,
                    "librarySize" to songs.size,
                    "aiMode" to recMode,
                    "embeddingRows" to embeddingRows,
                    "ctx" to ctx.name,
                ),
            )
            return
        }

        if (playFirstWhenStopped) {
            container.playback.appendToQueueAndPlayFirst(tail)
        } else {
            container.playback.appendToQueue(tail)
        }
        container.toaster.show(
            "Up Next refreshed with recommendations (${blendedTriple?.third ?: "shuffle"})",
        )
        container.activityLog.log(
            category = "engine",
            type = "QUEUE_EXHAUST_APPEND",
            message = "Appended ${tail.size} tracks at queue end",
            data = mapOf(
                "trigger" to trigger,
                "tailSize" to tail.size,
                "blend" to (blendedTriple?.third ?: "shuffle"),
                "ctx" to ctx.name,
            ),
        )
    }
}
