package org.opentabs.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.opentabs.core.PracticeNote
import org.opentabs.core.PracticeScore
import org.opentabs.core.PracticeTempo

class PracticePlaybackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val outputs = mutableListOf<FakeOutput>()
    private val speeds = mutableListOf<Double>()
    private var nextGate: CompletableDeferred<Unit>? = null
    private val transport = PracticePlayback(scope, {
        FakeOutput(nextGate).also { outputs += it; nextGate = null }
    }, { _, speed -> speeds += speed; byteArrayOf(1) })
    private val score = PracticeScore("Exercise", "Guitar", listOf(40, 45, 50, 55, 59, 64), 0,
        listOf(PracticeNote(0, 960, 0, 0, 40, 1)), listOf(PracticeTempo(0, 120.0)), 960 * 16L)

    @After fun cleanup() { transport.release(); scope.cancel() }

    @Test fun textOnlyNeverCreatesOrStartsAudio() {
        transport.load("text", null)
        transport.play()
        assertFalse(transport.state.value.available)
        assertFalse(transport.state.value.playing)
        assertTrue(outputs.isEmpty())
    }

    @Test fun preparingNeverAutoplaysAndPauseRetainsPosition() {
        transport.load("score", score)
        assertTrue(transport.state.value.ready)
        assertFalse(transport.state.value.playing)
        transport.play()
        outputs.last().position = 2300
        transport.pause()
        assertFalse(transport.state.value.playing)
        assertEquals(2300L, transport.state.value.positionMillis)
        assertEquals(1, outputs.last().pauseCount)
        transport.play()
        assertEquals(2, outputs.last().playCount)
    }

    @Test fun stopRewindsAndSeekClampsToScore() {
        transport.load("score", score)
        transport.play()
        outputs.last().position = 2000
        transport.stop()
        assertFalse(transport.state.value.playing)
        assertEquals(0L, transport.state.value.positionMillis)
        assertEquals(0L, outputs.last().position)
        transport.seekTo(-100)
        assertEquals(0L, outputs.last().position)
        transport.seekTo(Long.MAX_VALUE)
        assertEquals(score.durationMillis(), outputs.last().position)
        transport.play()
        assertEquals(0L, outputs.last().position)
    }

    @Test fun speedRendersNewMidiPreservingScoreTimeAndPlayingState() {
        transport.load("score", score)
        transport.play()
        val first = outputs.last()
        first.position = 2500
        transport.setSpeed(0.5)
        assertEquals(listOf(1.0, 0.5), speeds)
        assertEquals(1, first.releaseCount)
        assertEquals(2500L, transport.state.value.positionMillis)
        assertEquals(5000L, outputs.last().position)
        assertTrue(transport.state.value.playing)
        transport.seekTo(3000)
        assertEquals(6000L, outputs.last().position)
        outputs.last().position = 6200
        transport.pause()
        assertEquals(3100L, transport.state.value.positionMillis)
    }

    @Test fun backgroundPauseWhileChangingSpeedPreventsDelayedAutoResume() {
        transport.load("score", score)
        transport.play()
        val gate = CompletableDeferred<Unit>()
        nextGate = gate
        transport.setSpeed(0.75)
        assertTrue(transport.state.value.loading)
        transport.pause()
        gate.complete(Unit)
        assertTrue(transport.state.value.ready)
        assertFalse(transport.state.value.playing)
        assertEquals(0, outputs.last().playCount)
    }

    @Test fun stopWhileChangingSpeedRewindsAndPreventsDelayedResume() {
        transport.load("score", score)
        transport.play()
        outputs.last().position = 3000
        val gate = CompletableDeferred<Unit>()
        nextGate = gate
        transport.setSpeed(0.5)
        transport.stop()
        gate.complete(Unit)
        assertTrue(transport.state.value.ready)
        assertFalse(transport.state.value.playing)
        assertEquals(0L, transport.state.value.positionMillis)
        assertEquals(0L, outputs.last().position)
        assertEquals(0, outputs.last().playCount)
    }

    @Test fun switchingSongsCancelsPreparationAndIgnoresStaleCallbacks() {
        val gate = CompletableDeferred<Unit>()
        nextGate = gate
        transport.load("first", score)
        val old = outputs.last()
        val lateError = old.onError
        val lateCompletion = old.onCompletion
        transport.load("second", score)
        gate.complete(Unit)
        lateError("Old decoder error")
        lateCompletion()
        assertEquals("second", transport.state.value.songId)
        assertTrue(transport.state.value.ready)
        assertNull(transport.state.value.error)
        assertEquals(1, old.releaseCount)
        assertEquals(0, old.playCount)
    }

    @Test fun switchingToTextReleasesSoundAndCannotResumePreviousSong() {
        transport.load("score", score)
        transport.play()
        val old = outputs.last()
        transport.load("text", null)
        transport.toggle()
        assertEquals(1, old.releaseCount)
        assertFalse(transport.state.value.ready)
        assertFalse(transport.state.value.playing)
    }

    @Test fun deniedAudioFocusKeepsPreparedAudioStopped() {
        transport.load("score", score)
        outputs.last().focusGranted = false
        transport.play()
        assertFalse(transport.state.value.playing)
        assertTrue(transport.state.value.ready)
        assertNotNull(transport.state.value.notice)
        outputs.last().focusGranted = true
        transport.play()
        assertTrue(transport.state.value.playing)
        assertNull(transport.state.value.notice)
    }

    @Test fun audioFocusLossAndHeadphoneInterruptionPauseWithoutAutomaticResume() {
        transport.load("score", score)
        transport.play()
        outputs.last().position = 1500
        outputs.last().onInterruption()
        assertFalse(transport.state.value.playing)
        assertEquals(1500L, transport.state.value.positionMillis)
        assertEquals(1, outputs.last().playCount)
        assertNotNull(transport.state.value.notice)
    }

    @Test fun loopRepeatsWholeScoreButLateCompletionCannotRestartPausedAudio() {
        transport.load("score", score)
        transport.setLoop(true)
        transport.play()
        outputs.last().onCompletion()
        assertTrue(transport.state.value.playing)
        assertEquals(0L, outputs.last().position)
        assertEquals(2, outputs.last().playCount)
        transport.pause()
        outputs.last().onCompletion()
        assertFalse(transport.state.value.playing)
        assertEquals(2, outputs.last().playCount)
        assertEquals(0L, transport.state.value.positionMillis)
    }

    @Test fun completionReleasesFocusAndReplayStartsAtBeginning() {
        transport.load("score", score)
        transport.play()
        outputs.last().onCompletion()
        assertFalse(transport.state.value.playing)
        assertEquals(score.durationMillis(), transport.state.value.positionMillis)
        assertEquals(1, outputs.last().pauseCount)
        transport.play()
        assertEquals(0L, outputs.last().position)
    }

    @Test fun decoderFailureReleasesOutputAndAllowsExplicitRetry() {
        transport.load("score", score)
        transport.play()
        val first = outputs.last()
        first.onError("Unsupported MIDI decoder")
        assertFalse(transport.state.value.ready)
        assertFalse(transport.state.value.playing)
        assertEquals(1, first.releaseCount)
        assertTrue(transport.state.value.error.orEmpty().contains("Unsupported MIDI decoder"))
        transport.retry()
        assertEquals(2, outputs.size)
        assertTrue(transport.state.value.ready)
        assertFalse(transport.state.value.playing)
    }

    @Test fun releaseIsIdempotentAndInvalidatesCallbacksAndPendingPreparation() {
        val gate = CompletableDeferred<Unit>()
        nextGate = gate
        transport.load("score", score)
        val output = outputs.last()
        val error = output.onError
        transport.release()
        transport.release()
        gate.complete(Unit)
        error("Late failure")
        transport.play()
        assertEquals(1, output.releaseCount)
        assertFalse(transport.state.value.ready)
        assertFalse(transport.state.value.playing)
        assertFalse(transport.state.value.loading)
    }

    @Test fun invalidSpeedIsIgnored() {
        transport.load("score", score)
        transport.setSpeed(Double.NaN)
        transport.setSpeed(Double.POSITIVE_INFINITY)
        assertEquals(1.0, transport.state.value.speed, 0.0)
        assertEquals(1, outputs.size)
    }

    private class FakeOutput(private val gate: CompletableDeferred<Unit>?) : PracticeAudioOutput {
        override var onCompletion: () -> Unit = {}
        override var onInterruption: () -> Unit = {}
        override var onError: (String) -> Unit = {}
        var position = 0L
        var focusGranted = true
        var playCount = 0
        var pauseCount = 0
        var releaseCount = 0
        private var released = false
        override suspend fun prepare(midi: ByteArray) { gate?.await() }
        override val positionMillis: Long get() = position
        override fun play(): Boolean { if (focusGranted) playCount++; return focusGranted }
        override fun pause() { pauseCount++ }
        override fun seekTo(positionMillis: Long) { position = positionMillis }
        override fun release() { if (!released) { releaseCount++; released = true } }
    }
}
