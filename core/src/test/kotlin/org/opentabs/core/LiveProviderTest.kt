package org.opentabs.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real HTTPS probes. Deterministic fixture tests are the default CI gate. */
class LiveProviderTest {
    @Test fun `documented MusicBrainz API returns live title and artist metadata`() = runBlocking {
        assumeTrue(System.getProperty("opentabs.liveProviders") == "true")
        val provider = MusicBrainzProvider(OkHttpProviderHttp())
        val results = provider.search(SearchQuery("Nutshell", "Alice in Chains"))
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.title.equals("Nutshell", true) && it.artist.equals("Alice in Chains", true) })
        assertTrue(results.all { it.native == NativeContent.METADATA_ONLY })
        val partial = provider.search(SearchQuery("Nutsh", "Alice in Cha"))
        assertTrue(partial.any { it.title.equals("Nutshell", true) })
    }

    @Test fun `real online CC0 source returns authored native tablature`() = runBlocking {
        assumeTrue(System.getProperty("opentabs.liveProviders") == "true")
        val provider = TabForgeProvider(OkHttpProviderHttp())
        val results = provider.search(SearchQuery("Ashen Meridian"))
        val lead = results.first { it.arrangement == "Lead Gtr" }
        assertEquals(NativeContent.AVAILABLE, lead.native)
        assertEquals("CC0-1.0", lead.rights.license)
        assertTrue(provider.retrieve(lead).document.lines.any { it.startsWith("E|") })
    }
}
