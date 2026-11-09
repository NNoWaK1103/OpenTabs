package org.opentabs.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android's offline MIDI synthesizer. No network, microphone, or background service is used. */
internal class AndroidMidiOutput(context: Context) : PracticeAudioOutput {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var released = false
    private var focusHeld = false
    private var receiverRegistered = false
    private var startAfterSeek = false
    private var seekInFlight = false
    private var queuedSeek: Long? = null
    private var requestedPosition: Long? = null
    override var onCompletion: () -> Unit = {}
    override var onInterruption: () -> Unit = {}
    override var onError: (String) -> Unit = {}
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onInterruption()
        }
    }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (!released && change != AudioManager.AUDIOFOCUS_GAIN) onInterruption()
            // Never auto-resume after calls or another app taking focus.
        }, Handler(Looper.getMainLooper())).build()

    override suspend fun prepare(midi: ByteArray) {
        check(!released) { "The audio session was closed." }
        var temporary: File? = null
        try {
            withContext(Dispatchers.IO) {
                temporary = File.createTempFile("practice-", ".mid", app.cacheDir)
                checkNotNull(temporary).writeBytes(midi)
            }
            check(!released) { "The audio session was closed." }
            file = temporary
        } catch (error: Exception) {
            // withContext waits for the write before delivering cancellation; do not orphan its file.
            temporary?.delete()
            throw error
        }
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(attributes)
        suspendCancellableCoroutine<Unit> { continuation ->
            media.setOnPreparedListener { if (continuation.isActive) continuation.resume(Unit) }
            media.setOnCompletionListener {
                startAfterSeek = false
                if (!released) onCompletion()
            }
            media.setOnSeekCompleteListener {
                if (!released) {
                    val queued = queuedSeek
                    queuedSeek = null
                    try {
                        if (queued != null) media.seekTo(queued.toInt())
                        else {
                            seekInFlight = false
                            requestedPosition = null
                            if (startAfterSeek) media.start()
                        }
                    } catch (error: Exception) { onError(error.message ?: "Cannot seek in this MIDI score.") }
                }
            }
            media.setOnErrorListener { _, what, extra ->
                val message = "The device MIDI decoder could not play this score ($what/$extra)."
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                else if (!released) onError(message)
                true
            }
            try {
                media.setDataSource(checkNotNull(file).absolutePath)
                media.prepareAsync()
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            // The owning transport releases the player on cancellation, on its main thread.
        }
    }

    override val positionMillis: Long get() = requestedPosition ?: player?.currentPosition?.toLong() ?: 0

    override fun play(): Boolean {
        check(!released)
        val media = checkNotNull(player)
        if (!focusHeld) {
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
            focusHeld = true
        }
        try {
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(app, noisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            }
            startAfterSeek = true
            if (!seekInFlight) media.start()
            return true
        } catch (error: Exception) {
            abandonFocus()
            throw error
        }
    }

    override fun pause() {
        startAfterSeek = false
        player?.let { if (it.isPlaying) it.pause() }
        abandonFocus()
    }

    override fun seekTo(positionMillis: Long) {
        val media = player ?: return
        val position = positionMillis.coerceIn(0, Int.MAX_VALUE.toLong())
        requestedPosition = position
        if (media.isPlaying) media.pause()
        if (seekInFlight) queuedSeek = position
        else {
            seekInFlight = true
            media.seekTo(position.toInt())
        }
    }

    override fun release() {
        if (released) return
        released = true
        startAfterSeek = false
        onCompletion = {}
        onInterruption = {}
        onError = {}
        val old = player
        player = null
        old?.setOnPreparedListener(null)
        old?.setOnCompletionListener(null)
        old?.setOnErrorListener(null)
        old?.setOnSeekCompleteListener(null)
        old?.release()
        abandonFocus()
        file?.delete()
        file = null
    }

    private fun abandonFocus() {
        if (receiverRegistered) {
            app.unregisterReceiver(noisyReceiver)
            receiverRegistered = false
        }
        if (focusHeld) {
            focusHeld = false
            audioManager.abandonAudioFocusRequest(focus)
        }
    }
}
