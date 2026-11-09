package org.opentabs.app

import org.junit.Assert.*
import org.junit.Test
import org.opentabs.core.WebTabSource

class ProviderTabsStateTest {
    @Test fun freshLaunchIsSearchOnlyAndDoesNotOpenAWebsite() {
        val state = ProviderTabsState()
        assertNull(state.webTab)
        assertNull(state.initialSavedUrl)
        assertEquals("", state.query)
        assertFalse(state.fullScreen)
        assertEquals(listOf("Songsterr", "Ultimate Guitar"), WebTabSource.entries.map { it.label })
    }

    @Test fun bothProvidersReceiveTheUserQueryAndBlankQueriesBrowseTheirHomePages() {
        WebTabSource.entries.forEach { source ->
            assertEquals(source.home, ProviderTabsState().browse(source).webTab!!.url)
            val state = ProviderTabsState().withQuery("  Alice in Chains Nutshell  ").browse(source)
            assertEquals(source.searchUrl("Alice in Chains Nutshell", ""), state.webTab!!.url)
            assertEquals(source, state.webTab.source)
        }
    }

    @Test fun changingSourceAndRepeatedSearchesHaveNewRevisions() {
        val first = ProviderTabsState().withQuery("Nutshell").browse(WebTabSource.SONGSTERR)
        val second = first.browse(WebTabSource.ULTIMATE_GUITAR)
        val third = second.browse(WebTabSource.ULTIMATE_GUITAR)
        assertEquals("Nutshell", second.webTab!!.title)
        assertTrue(second.revision > first.revision)
        assertTrue(third.revision > second.revision)
        assertNull(second.initialSavedUrl)
    }

    @Test fun returningToSearchPreservesQueryAndClearsFullscreenAndWebRequest() {
        val state = ProviderTabsState().withQuery("Nutshell").browse(WebTabSource.SONGSTERR).fullScreen(true)
        assertTrue(state.fullScreen)
        val closed = state.closeBrowser()
        assertNull(closed.webTab)
        assertNull(closed.initialSavedUrl)
        assertFalse(closed.fullScreen)
        assertEquals("Nutshell", closed.query)
        assertFalse(closed.fullScreen(true).fullScreen)
    }

    @Test fun savedProviderLinksUseCanonicalOnlineAddressesAndPreserveSearch() {
        val url = "https://tabs.ultimate-guitar.com/tab/alice-in-chains/nutshell-chords-12345"
        val saved = ProviderTabsState().withQuery("My search").openSavedLink("$url?tracking=removed#section")
        assertEquals(url, saved.initialSavedUrl)
        assertEquals(WebTabSource.ULTIMATE_GUITAR, saved.webTab!!.source)
        assertEquals("My search", saved.query)
        assertNull(saved.browse(WebTabSource.SONGSTERR).initialSavedUrl)
        assertTrue(saved.openSavedLink(url).revision > saved.revision)
    }

    @Test fun unsupportedAndUnsafeSavedLinksCannotNavigate() {
        val state = ProviderTabsState()
        listOf("https://example.org/tab/test-123", "http://www.songsterr.com/a/wsa/test-tab-s1",
            "https://www.songsterr.com/login", "file:///private/tab.txt", "javascript:alert(1)").forEach {
            assertEquals(state, state.openSavedLink(it))
        }
    }

    @Test fun queryIsBoundedAndControlCharactersAreRemoved() {
        assertEquals("SongArtist", ProviderTabsState().withQuery("Song\nArtist\t").query)
        assertEquals(160, ProviderTabsState().withQuery("a".repeat(300)).query.length)
    }
}
