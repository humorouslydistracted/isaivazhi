package com.isaivazhi.app.engine

import com.isaivazhi.app.NativeAccelerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs [EmbeddingHealthAnalyzer] over the library in the background and
 * keeps the latest report in memory (it is cheap enough to recompute per
 * process, so nothing is persisted).
 */
class EmbeddingHealthEngine(
    private val embeddingDb: EmbeddingDbFacade,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data class Running(val progress: Float) : State
        data class Ready(
            val report: EmbeddingHealthAnalyzer.Report,
            val computedAtMs: Long,
            val elapsedMs: Long,
        ) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val analyzer = EmbeddingHealthAnalyzer { q, v, count, dim, out ->
        NativeAccelerator.dotProductBatch(q, v, count, dim, out)
    }

    @Volatile private var job: Job? = null
    @Volatile private var lowExposureRaw: Set<String> = emptySet()
    @Volatile private var lowExposureCentered: Set<String> = emptySet()
    @Volatile private var lastAutoStartMs = 0L

    val isRunning: Boolean get() = job?.isActive == true

    fun analyze(library: List<Song>): Job? {
        job?.takeIf { it.isActive }?.let { return it }
        val songs = library
        val started = scope.launch(Dispatchers.Default) {
            val startedAt = System.currentTimeMillis()
            _state.value = State.Running(0f)
            try {
                val hashes = embeddedHashes(songs).toList()
                val vecs = embeddingDb.getVecsByHashes(hashes)
                if (vecs.size < 2) {
                    _state.value = State.Failed("Need at least 2 embedded songs (found ${vecs.size}).")
                    return@launch
                }
                val ctx = this.coroutineContext
                val report = analyzer.analyze(
                    vectors = vecs,
                    onProgress = { p -> _state.value = State.Running(p) },
                    isCancelled = { !ctx.isActive },
                )
                lowExposureRaw = report.raw.lowExposure(report.k)
                lowExposureCentered = report.centered.lowExposure(report.k)
                _state.value = State.Ready(report, System.currentTimeMillis(), System.currentTimeMillis() - startedAt)
            } catch (c: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
                throw c
            } catch (t: Throwable) {
                _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
        job = started
        return started
    }

    /**
     * Low-exposure hashes for the discovery slot. Kicks off an analysis
     * when there is no report yet, or when the embedded song count has
     * drifted since the last one (at most once per [AUTO_REFRESH_MIN_MS]).
     */
    fun lowExposureHashes(centered: Boolean, library: List<Song>): Set<String>? {
        val current = _state.value
        val ready = current as? State.Ready
        if (ready == null) {
            if (current !is State.Running) autoStart(library)
            return null
        }
        if (embeddedHashes(library).size != ready.report.songCount) autoStart(library)
        return if (centered) lowExposureCentered else lowExposureRaw
    }

    private fun embeddedHashes(library: List<Song>): Set<String> =
        library.mapNotNullTo(LinkedHashSet()) { s ->
            s.contentHash?.takeIf { it.isNotBlank() && s.filePath != null }
        }

    private fun autoStart(library: List<Song>) {
        val now = System.currentTimeMillis()
        if (isRunning || library.isEmpty()) return
        if (now - lastAutoStartMs < AUTO_REFRESH_MIN_MS && _state.value !is State.Idle) return
        lastAutoStartMs = now
        analyze(library)
    }

    companion object {
        private const val AUTO_REFRESH_MIN_MS = 30 * 60 * 1000L
    }
}
