package org.opentabs.core

import java.net.URI

object TunerUrls {
    const val HOME = "https://guitar-tuner.org/"
    fun trustedPage(url: String): Boolean = WebTabUrls.safeHttps(url) &&
        URI(url).host.equals("guitar-tuner.org", ignoreCase = true)

    fun trustedOrigin(origin: String): Boolean = trustedPage(origin) && URI(origin).let {
        (it.rawPath.isNullOrEmpty() || it.rawPath == "/") && it.rawQuery == null && it.rawFragment == null
    }
}

interface TunerMicrophoneRequest {
    val origin: String
    val resources: Set<String>
    fun grantAudio()
    fun deny()
}

enum class TunerMicrophoneStatus { IDLE, WAITING, ALLOWED, DENIED, BLOCKED }

/** Permission decisions only: no recording, Android references, or remembered origin grants. */
class TunerMicrophoneGate(
    private val hasAndroidPermission: () -> Boolean,
    private val isForegroundTrustedPage: () -> Boolean,
    private val requestAndroidPermission: () -> Unit,
    private val status: (TunerMicrophoneStatus) -> Unit,
) {
    companion object { const val AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE" }
    private var pending: TunerMicrophoneRequest? = null
    private var waitingForAndroid = false

    private fun eligible(request: TunerMicrophoneRequest) =
        TunerUrls.trustedOrigin(request.origin) && AUDIO_CAPTURE in request.resources && isForegroundTrustedPage()

    fun request(request: TunerMicrophoneRequest) {
        if (!eligible(request)) {
            deny(request); status(TunerMicrophoneStatus.BLOCKED); return
        }
        if (waitingForAndroid || pending != null) {
            deny(request); return // A new page cannot consume an older permission dialog's result.
        }
        if (hasAndroidPermission()) { grant(request); return }
        pending = request
        waitingForAndroid = true
        status(TunerMicrophoneStatus.WAITING)
        runCatching { requestAndroidPermission() }.onFailure { androidResult(false) }
    }

    fun androidResult(granted: Boolean) {
        if (!waitingForAndroid) return
        waitingForAndroid = false
        val request = pending.also { pending = null } ?: return
        if (granted && hasAndroidPermission() && eligible(request)) grant(request)
        else { deny(request); status(TunerMicrophoneStatus.DENIED) }
    }

    fun canceled(request: TunerMicrophoneRequest) {
        if (pending === request) {
            pending = null // Android says a canceled WebView request must no longer receive a response.
            status(TunerMicrophoneStatus.IDLE)
        }
    }

    fun pause() {
        pending?.let(::deny)
        pending = null
        status(TunerMicrophoneStatus.IDLE)
        // Keep an in-flight Android dialog marked until its callback; late results never grant a new page.
    }

    private fun grant(request: TunerMicrophoneRequest) {
        runCatching { request.grantAudio() }.onSuccess { status(TunerMicrophoneStatus.ALLOWED) }
            .onFailure { deny(request); status(TunerMicrophoneStatus.DENIED) }
    }
    private fun deny(request: TunerMicrophoneRequest) { runCatching { request.deny() } }
}
