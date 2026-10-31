package org.opentabs.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SearchAndVoiceTest {
    private val songs = listOf(
        Song("1", "Evening arpeggios", "OpenTabs exercises", album = "Quiet evenings"),
        Song("2", "Café rhythm", "Ada Guitar", tuning = "D A D G B e", lastOpened = 20),
        Song("3", "Evening study", "Ada Guitar", lastOpened = 10),
    )

    @Test fun `search matches partial titles artist keywords album and accents`() {
        assertEquals(listOf("3", "1"), LocalSearch.search(songs, "even").map { it.id })
        assertEquals(listOf("2", "3"), LocalSearch.search(songs, "ADA").map { it.id })
        assertEquals("3", LocalSearch.search(songs, "study ada").single().id)
        assertEquals("2", LocalSearch.search(songs, "cafe").single().id)
        assertEquals("1", LocalSearch.search(songs, "quiet").single().id)
        assertTrue(LocalSearch.search(songs, "no matching song").isEmpty())
        assertEquals(3, LocalSearch.search(songs, "   ").size)
    }

    @Test fun `recognized tracks match title and artist without false positives`() {
        assertEquals("2", LocalSearch.matching(songs, TrackInfo("Cafe rhythm", "Ada Guitar")).single().id)
        assertTrue(LocalSearch.matching(songs, TrackInfo("Evening study", "Someone else")).isEmpty())
    }

    @Test fun `voice search separates title from artist`() {
        assertEquals(VoiceCommand.Find("nutshell alice in chains"),
            VoiceCommandParser.parse("Find tabs for Nutshell by Alice in Chains."))
        assertEquals(VoiceCommand.Find("evening"), VoiceCommandParser.parse("Search for evening"))
    }

    @Test fun `supported commands parse deterministically`() {
        val commands = mapOf(
            "Open my favorites." to VoiceCommand.Favorites,
            "Show my practice list" to VoiceCommand.Practice,
            "  START   SCROLLING! " to VoiceCommand.StartScroll,
            "Stop scrolling" to VoiceCommand.StopScroll,
            "Increase tab size" to VoiceCommand.IncreaseSize,
            "Decrease tab size" to VoiceCommand.DecreaseSize,
            "never start scrolling" to VoiceCommand.Unknown,
            "find tabs for" to VoiceCommand.Unknown,
            "" to VoiceCommand.Unknown,
        )
        commands.forEach { (spoken, expected) -> assertEquals(spoken, expected, VoiceCommandParser.parse(spoken)) }
    }

    @Test fun `unconfigured providers explicitly return unavailable`() = runTest {
        val spotify: NowPlayingProvider = UnconfiguredSpotifyProvider()
        val recognition: MusicRecognitionProvider = UnconfiguredRecognitionProvider()
        assertTrue(spotify.connect() is Availability.Unavailable)
        assertTrue(recognition.recognize() is RecognitionResult.Unavailable)
    }
}
