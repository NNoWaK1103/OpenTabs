package org.opentabs.core

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderHttpTest {
    @get:Rule val temp = TemporaryFolder()
    private fun transport(server: MockWebServer, cache: LicensedHttpCache? = null) = OkHttpProviderHttp(cache,
        OkHttpClient.Builder().followRedirects(false).readTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).build(),
        approvedHosts = setOf(server.hostName), requireHttps = false)

    @Test fun `HTTP validates status body limits and refuses redirects`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val http = transport(server)
            server.enqueue(MockResponse().setBody("valid"))
            assertEquals("valid", http.get(server.url("/ok").toString(), 100).bytes.decodeToString())
            server.enqueue(MockResponse().setBody("too much data"))
            assertTrue(runCatching { http.get(server.url("/large").toString(), 4) }.isFailure)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/private"))
            assertTrue(runCatching { http.get(server.url("/redirect").toString(), 100) }.isFailure)
            server.enqueue(MockResponse().setResponseCode(429))
            assertTrue(runCatching { http.get(server.url("/limited").toString(), 100) }.isFailure)
        }
    }

    @Test fun `unapproved hosts are rejected before network access`() = runTest {
        val http = OkHttpProviderHttp()
        assertTrue(runCatching { http.get("http://127.0.0.1/private", 100) }.isFailure)
        assertTrue(runCatching { http.get("https://example.com/private", 100) }.isFailure)
    }

    @Test fun `only validated authorized responses enter cache and cached content survives offline`() = runTest {
        val cache = LicensedHttpCache(temp.newFolder())
        MockWebServer().use { server ->
            server.start()
            val http = transport(server, cache)
            val url = server.url("/public-cc0").toString()
            server.enqueue(MockResponse().setBody("valid"))
            val payload = http.get(url, 100, 60_000)
            assertNull(cache.read(url, 60_000, false))
            http.cacheValidated(url, payload)
            val cached = http.get(url, 100, 60_000)
            assertTrue(cached.cached)
            assertEquals("valid", cached.bytes.decodeToString())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `stale authorized cache is labeled and expires after seven days`() {
        var now = System.currentTimeMillis()
        val cache = LicensedHttpCache(temp.newFolder()) { now }
        cache.write("https://source/cc0", "data".toByteArray())
        now += 120_000
        assertNull(cache.read("https://source/cc0", 60_000, false))
        assertTrue(cache.read("https://source/cc0", 60_000, true)!!.stale)
        now += 8L * 24 * 60 * 60 * 1000
        assertNull(cache.read("https://source/cc0", 60_000, true))
    }

    @Test fun `network failure falls back to explicitly permitted stale data`() = runTest {
        var now = System.currentTimeMillis()
        val cache = LicensedHttpCache(temp.newFolder()) { now }
        val server = MockWebServer()
        server.start()
        val url = server.url("/cc0").toString()
        val http = transport(server, cache)
        cache.write(url, "offline catalog".toByteArray())
        now += 120_000
        server.shutdown()
        val payload = http.get(url, 100, 60_000)
        assertTrue(payload.cached && payload.stale)
        assertEquals("offline catalog", payload.bytes.decodeToString())
    }

    @Test fun `disk cache retains at most twelve small responses`() {
        val folder = temp.newFolder()
        val cache = LicensedHttpCache(folder)
        repeat(20) { cache.write("https://source/$it", ByteArray(100)) }
        assertTrue(folder.listFiles()!!.size <= 12)
    }
}
