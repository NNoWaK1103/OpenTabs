package org.opentabs.core

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class WebTabsTest {
    @Test fun `the only online discovery destinations are usable provider search pages`() {
        assertEquals(setOf(WebTabSource.SONGSTERR, WebTabSource.ULTIMATE_GUITAR), WebTabSource.entries.toSet())
        WebTabSource.entries.forEach { source ->
            val request = WebTabRequest(source, "Nutshell", "Alice in Chains", 1)
            assertEquals(WebNavigation.EMBEDDED, WebTabUrls.navigation(request.url))
            assertEquals(source, WebTabUrls.source(request.url))
            assertTrue(request.url.contains("Nutshell"))
            val tabUrl = if (source == WebTabSource.SONGSTERR) "https://www.songsterr.com/a/wsa/test-tab-s1"
                else "https://tabs.ultimate-guitar.com/tab/test/guitar-tabs-1"
            assertEquals(WebNavigation.EMBEDDED, WebTabUrls.navigation(tabUrl))
            assertEquals(tabUrl, WebTabUrls.fallback(tabUrl, request))
        }
    }

    @Test fun `search builds public website URLs with separately encoded artist and title`() {
        val title = "Nutshell & Friends? #1"
        val artist = "Alice + Chains / live"
        WebTabSource.entries.forEach { source ->
            val url = source.searchUrl(title, artist)
            assertEquals(WebNavigation.EMBEDDED, WebTabUrls.navigation(url))
            assertEquals(source, WebTabUrls.source(url))
            val query = URI(url).rawQuery.split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            assertEquals("$title $artist", query[if (source == WebTabSource.SONGSTERR) "pattern" else "value"])
            if (source == WebTabSource.ULTIMATE_GUITAR) assertEquals("title", query["search_type"])
        }
    }

    @Test fun `artist only title only and empty queries navigate normally`() {
        WebTabSource.entries.forEach { source ->
            assertEquals(source.home, source.searchUrl(" ", " "))
            assertTrue(source.searchUrl("", "Bach").contains("Bach"))
            assertTrue(source.searchUrl("Etude", "").contains("Etude"))
        }
    }

    @Test fun `source switching preserves the search and advances its request revision`() {
        val original = WebTabRequest(WebTabSource.SONGSTERR, "Nutshell", "Alice in Chains", 5)
        val changed = original.switchTo(WebTabSource.ULTIMATE_GUITAR)
        assertEquals(original.title, changed.title)
        assertEquals(original.artist, changed.artist)
        assertEquals(6, changed.revision)
        assertEquals(WebTabSource.ULTIMATE_GUITAR, WebTabUrls.source(changed.url))
    }

    @Test fun `only exact trusted website hosts render internally`() {
        listOf("https://www.songsterr.com/a/wsa/example-tab-s1", "https://songsterr.com/", "https://tabs.ultimate-guitar.com/tab/example",
            "HTTPS://WWW.SONGSTERR.COM/", "https://www.ultimate-guitar.com:443/search.php").forEach {
            assertEquals(it, WebNavigation.EMBEDDED, WebTabUrls.navigation(it))
        }
        listOf("https://songsterr.com.evil.example/", "https://not-songsterr.com/", "https://evil.example/?next=www.songsterr.com",
            "https://ultimate-guitar.com.evil.example/", "https://example.org/sign-in").forEach {
            assertEquals(it, WebNavigation.EXTERNAL, WebTabUrls.navigation(it))
        }
    }

    @Test fun `credentials dangerous schemes malformed hosts and ambiguous URLs are blocked`() {
        listOf("javascript:alert(1)", "file:///sdcard/private", "content://provider/tab", "intent://tab#Intent;end",
            "http://www.songsterr.com/", "https://www.songsterr.com@evil.example/", "https://evil@example.org/",
            "https://www.songsterr.com:444/", "https://www.songsterr.com./", "https://www.songsterr.com\\@evil.example/",
            "https://www.songsterr.com\n.evil/", "https://%77ww.songsterr.com/", "https:///empty", "data:text/html,tab", "about:blank").forEach {
            assertEquals(it, WebNavigation.BLOCKED, WebTabUrls.navigation(it))
        }
    }

    @Test fun `search values cannot change the destination host or execute URL schemes`() {
        val url = WebTabSource.SONGSTERR.searchUrl("javascript:alert(1)&pattern=bad", "https://evil.example/#")
        assertEquals("www.songsterr.com", URI(url).host)
        assertEquals(WebNavigation.EMBEDDED, WebTabUrls.navigation(url))
    }

    @Test fun `fallback never forwards an unsafe current page or an arbitrary external redirect`() {
        val request = WebTabRequest(WebTabSource.SONGSTERR, "Study", "Bach", 1)
        assertEquals(request.url, WebTabUrls.fallback("javascript:bad", request))
        assertEquals(request.url, WebTabUrls.fallback("https://evil.example/", request))
        assertEquals("https://www.songsterr.com/a/wsa/tab-s1", WebTabUrls.fallback("https://www.songsterr.com/a/wsa/tab-s1", request))
    }

    @Test fun `site restrictions preserve delivered pages and hard errors expose recovery`() {
        listOf(401, 403, 429).forEach { code ->
            val failure = WebFailure.http(code)
            assertFalse(failure.coversPage)
            assertTrue(failure.message.contains(code.toString()))
        }
        assertFalse(WebFailure.http(404).coversPage)
        assertTrue(WebFailure.network().coversPage)
        assertTrue(WebFailure.tls().coversPage)
        assertTrue(WebFailure.renderer().coversPage)
        assertTrue(WebFailure.unavailable().coversPage)
    }
}
