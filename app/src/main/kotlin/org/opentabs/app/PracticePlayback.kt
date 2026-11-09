package org.opentabs.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opentabs.core.PracticeMidi
import org.opentabs.core.PracticeScore

/** A single prepared MIDI output. All methods and callbacks run on the owning main thread. */
internal interface PracticeAudioOutput {
    var onCompletion: () -> Unit
    var onInterruption: () -> Unit
    var onError: (String) -> Unit
    suspend fun prepare(midi: ByteArray)
    val positionMillis: Long
    /** False means another app/system policy denied audio focus. */
    fun play(): Boolean
    fun pause()
    fun seekTo(positionMillis: Long)
    fun release()
}

data class PracticePlaybackState(
    val songId: String? = null,
    val available: Boolean = false,
    val loading: Boolean = false,
    val ready: Boolean = false,
    val playing: Boolean = false,
    /** Positions are in score time, independent of practice speed. */
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val speed: Double = 1.0,
    val loop: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val warnings: List<String> = emptyList(),
    val tuning: List<Int> = emptyList(),
    val capo: Int = 0,
)

/**
 * Lifecycle-owned transport, independent of Android's MIDI decoder for deterministic tests.
 * There is deliberately no inferred synchronization between text columns and audio time.
 */
internal class PracticePlayback(
    private val scope: CoroutineScope,
    private val outputFactory: () -> PracticeAudioOutput,
    private val encode: suspend (PracticeScore, Double) -> ByteArray = { score, speed ->
        withContext(Dispatchers.Default) { PracticeMidi.encode(score, speed) }
    },
) {
    private val mutableState = MutableStateFlow(PracticePlaybackState())
    val state = mutableState.asStateFlow()
    private var score: PracticeScore? = null
    private var output: PracticeAudioOutput? = null
    private var preparing: Job? = null
    private var polling: Job? = null
    private var revision = 0L
    private var closed = false
    private var wantsPlayback = false

    fun load(songId: String?, score: PracticeScore?, unavailableReason: String? = null) {
        if (closed) return
        invalidateOutput()
        this.score = score
        mutableState.value = PracticePlaybackState(songId = songId, available = score != null,
            durationMillis = score?.durationMillis() ?: 0, error = unavailableReason,
            warnings = score?.warnings.orEmpty(), tuning = score?.tuning.orEmpty(), capo = score?.capo ?: 0)
        if (score != null) prepare(0)
    }

    private fun prepare(position: Long) {
        val currentScore = score ?: return
        val generation = revision
        mutableState.value = state.value.copy(loading = true, ready = false, playing = false,
            positionMillis = position, error = null, notice = null)
        preparing = scope.launch {
            var candidate: PracticeAudioOutput? = null
            try {
                val midi = encode(currentScore, state.value.speed)
                if (generation != revision || closed) return@launch
                val engine = outputFactory()
                candidate = engine
                output = engine
                engine.onCompletion = {
                    if (generation == revision && output === engine) completed()
                }
                engine.onInterruption = {
                    if (generation == revision && output === engine) {
                        pause()
                        mutableState.value = state.value.copy(notice = "Audio paused. Tap Play audio to resume.")
                    }
                }
                engine.onError = { message ->
                    if (generation == revision && output === engine) failed(message)
                }
                check(withTimeoutOrNull(15_000) { engine.prepare(midi); true } == true) {
                    "The device took too long to prepare this score. Try audio again."
                }
                if (generation != revision || closed || output !== engine) return@launch
                engine.seekTo(toMediaTime(state.value.positionMillis))
                mutableState.value = state.value.copy(loading = false, ready = true)
                if (wantsPlayback) play()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == revision && !closed) failed(error.message ?: "This device could not play the MIDI audio.")
            } finally {
                // An obsolete asynchronous preparation may finish after a new song is selected.
                if (candidate != null && output !== candidate) candidate.release()
            }
        }
    }

    fun toggle() { if (state.value.playing) pause() else play() }

    fun play() {
        if (closed || !state.value.ready) return
        val engine = output ?: return
        try {
            if (state.value.positionMillis >= state.value.durationMillis) {
                engine.seekTo(0)
                mutableState.value = state.value.copy(positionMillis = 0)
            }
            if (!engine.play()) {
                wantsPlayback = false
                mutableState.value = state.value.copy(notice = "Audio is in use by another app. Try Play audio again.")
                return
            }
            wantsPlayback = true
            mutableState.value = state.value.copy(playing = true, notice = null)
            polling?.cancel()
            polling = scope.launch {
                while (state.value.playing) {
                    delay(100)
                    updatePosition()
                }
            }
        } catch (error: Exception) { failed(error.message ?: "Audio playback could not start.") }
    }

    /** Also cancels a pending automatic resume after changing speed. */
    fun pause() {
        wantsPlayback = false
        polling?.cancel()
        polling = null
        if (!state.value.playing) return
        try {
            updatePosition()
            output?.pause()
            mutableState.value = state.value.copy(playing = false)
        } catch (error: Exception) { failed(error.message ?: "Audio playback stopped.") }
    }

    fun stop() {
        pause()
        if (state.value.ready) seekTo(0)
        else if (state.value.loading) mutableState.value = state.value.copy(positionMillis = 0)
    }

    fun seekTo(positionMillis: Long) {
        if (!state.value.ready || closed) return
        val position = positionMillis.coerceIn(0, state.value.durationMillis)
        try {
            output?.seekTo(toMediaTime(position))
            mutableState.value = state.value.copy(positionMillis = position, notice = null)
        } catch (error: Exception) { failed(error.message ?: "Cannot seek in this audio.") }
    }

    fun setSpeed(speed: Double) {
        if (!speed.isFinite() || closed || !state.value.ready) return
        val next = speed.coerceIn(0.25, 2.0)
        if (next == state.value.speed) return
        val resume = state.value.playing
        pause()
        val position = state.value.positionMillis
        invalidateOutput()
        wantsPlayback = resume
        mutableState.value = state.value.copy(speed = next)
        prepare(position)
    }

    fun setLoop(enabled: Boolean) {
        if (!closed) mutableState.value = state.value.copy(loop = enabled)
    }

    fun retry() {
        if (score == null || closed) return
        invalidateOutput()
        prepare(state.value.positionMillis)
    }

    fun release() {
        if (closed) return
        closed = true
        invalidateOutput()
        mutableState.value = state.value.copy(playing = false, ready = false, loading = false)
    }

    private fun completed() {
        if (!state.value.playing) return
        polling?.cancel()
        mutableState.value = state.value.copy(playing = false, positionMillis = state.value.durationMillis)
        if (state.value.loop && wantsPlayback) {
            seekTo(0)
            play()
        } else {
            wantsPlayback = false
            try { output?.pause() } // Releases focus without discarding the prepared song.
            catch (error: Exception) { failed(error.message ?: "Audio playback stopped.") }
        }
    }

    private fun updatePosition() {
        val engine = output ?: return
        try {
            val position = (engine.positionMillis * state.value.speed).toLong().coerceIn(0, state.value.durationMillis)
            mutableState.value = state.value.copy(positionMillis = position)
        } catch (error: Exception) { failed(error.message ?: "Cannot read the audio position.") }
    }

    private fun toMediaTime(scoreTime: Long) = (scoreTime / state.value.speed).toLong().coerceAtLeast(0)

    private fun failed(message: String) {
        invalidateOutput()
        mutableState.value = state.value.copy(loading = false, ready = false, playing = false,
            error = "Audio unavailable: $message")
    }

    private fun invalidateOutput() {
        ++revision
        wantsPlayback = false
        preparing?.cancel()
        preparing = null
        polling?.cancel()
        polling = null
        val old = output
        output = null
        old?.release()
    }
}
