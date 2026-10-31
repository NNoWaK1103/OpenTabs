package org.opentabs.core

import org.junit.Assert.*
import org.junit.Test

class TunerPermissionsTest {
    private class Request(override val origin: String = TunerUrls.HOME,
        override val resources: Set<String> = setOf(TunerMicrophoneGate.AUDIO_CAPTURE)) : TunerMicrophoneRequest {
        var grants = 0
        var denials = 0
        override fun grantAudio() { grants++ }
        override fun deny() { denials++ }
    }
    private var permission = false
    private var foreground = true
    private var prompts = 0
    private val statuses = mutableListOf<TunerMicrophoneStatus>()
    private fun gate() = TunerMicrophoneGate({ permission }, { foreground }, { prompts++ }, statuses::add)

    @Test fun `only the exact secure tuner host and origin are trusted`() {
        listOf(TunerUrls.HOME, "https://guitar-tuner.org/acoustic-guitar-tuning", "https://GUITAR-TUNER.ORG:443/")
            .forEach { assertTrue(it, TunerUrls.trustedPage(it)) }
        listOf("http://guitar-tuner.org/", "https://guitar-tuner.org.evil.example/", "https://evil@guitar-tuner.org/",
            "https://guitar-tuner.org:444/", "https://www.guitar-tuner.org/", "https://guitar-tuner.org./",
            "https://guitar-tuner.org\\@evil.example/", "https://songsterr.com/", "file:///tuner", "javascript:alert(1)")
            .forEach { assertFalse(it, TunerUrls.trustedPage(it)) }
        assertTrue(TunerUrls.trustedOrigin("https://guitar-tuner.org"))
        assertFalse(TunerUrls.trustedOrigin("https://guitar-tuner.org/path"))
        assertFalse(TunerUrls.trustedOrigin("https://guitar-tuner.org/?origin=other"))
    }

    @Test fun `microphone is never granted before Android actually permits it`() {
        val gate = gate()
        val request = Request()
        gate.request(request)
        assertEquals(1, prompts)
        assertEquals(0, request.grants)
        gate.androidResult(true) // A callback alone cannot replace a real permission check.
        assertEquals(0, request.grants)
        assertEquals(1, request.denials)
    }

    @Test fun `trusted request is granted after Android approval`() {
        val gate = gate()
        val request = Request()
        gate.request(request)
        permission = true
        gate.androidResult(true)
        assertEquals(1, request.grants)
        assertEquals(TunerMicrophoneStatus.ALLOWED, statuses.last())
    }

    @Test fun `already granted Android permission still requires a trusted foreground request`() {
        permission = true
        val gate = gate()
        val request = Request()
        gate.request(request)
        assertEquals(1, request.grants)
        assertEquals(0, prompts)
        foreground = false
        val hidden = Request()
        gate.request(hidden)
        assertEquals(1, hidden.denials)
    }

    @Test fun `foreign origins camera and unknown requests are denied without prompting Android`() {
        val gate = gate()
        listOf(Request("https://ads.example/"), Request("https://guitar-tuner.org.evil.example/"),
            Request(resources = setOf("android.webkit.resource.VIDEO_CAPTURE")),
            Request(resources = setOf("future.permission")), Request(resources = emptySet())).forEach {
            gate.request(it); assertEquals(1, it.denials); assertEquals(0, it.grants)
        }
        assertEquals(0, prompts)
    }

    @Test fun `mixed resource request only receives the audio operation`() {
        permission = true
        val request = Request(resources = setOf(TunerMicrophoneGate.AUDIO_CAPTURE, "android.webkit.resource.VIDEO_CAPTURE"))
        gate().request(request)
        assertEquals(1, request.grants) // The interface exposes grantAudio, never a blanket resource grant.
    }

    @Test fun `denial and canceled requests cannot later receive a grant`() {
        val gate = gate()
        val denied = Request()
        gate.request(denied)
        gate.androidResult(false)
        assertEquals(1, denied.denials)
        val canceled = Request()
        gate.request(canceled)
        gate.canceled(canceled)
        permission = true
        gate.androidResult(true)
        assertEquals(0, canceled.grants)
        assertEquals(0, canceled.denials)
    }

    @Test fun `leaving tuner denies pending request and ignores late Android approval`() {
        val gate = gate()
        val request = Request()
        gate.request(request)
        foreground = false
        gate.pause()
        permission = true
        gate.androidResult(true)
        assertEquals(1, request.denials)
        assertEquals(0, request.grants)
    }

    @Test fun `navigation cannot transfer an older Android approval to a new document`() {
        val gate = gate()
        val first = Request()
        gate.request(first)
        gate.pause()
        val second = Request()
        gate.request(second)
        assertEquals(1, second.denials)
        assertEquals(1, prompts)
        permission = true
        gate.androidResult(true)
        assertEquals(0, first.grants)
        assertEquals(0, second.grants)
        val fresh = Request()
        gate.request(fresh)
        assertEquals(1, fresh.grants)
    }
}
