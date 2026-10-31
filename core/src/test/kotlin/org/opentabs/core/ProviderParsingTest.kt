package org.opentabs.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderParsingTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/tabforge-ashen-meridian.gp")).readBytes()
    private fun http(bytes: ByteArray) = object : ProviderHttp {
        override suspend fun get(url: String, maxBytes: Int, cacheMillis: Long) = HttpPayload(bytes)
    }
    private fun zip(xml: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("Content/score.gpif")); it.write(xml.toByteArray()); it.closeEntry() }
        return bytes.toByteArray()
    }

    @Test fun `real CC0 GP7 score preserves authored arrangements and creates six string previews`() {
        val score = GuitarProPreview.decode(fixture().inputStream())
        assertEquals("Ashen Meridian", score.title)
        assertTrue(score.copyright.contains("CC0"))
        val supported = score.tracks.filter { score.nativeSupported(it) }
        assertEquals(listOf("Lead Gtr", "Lead Harmony", "Clean Gtr"), supported.map { it.name })
        supported.forEach { track ->
            val document = score.document(track.id)
            assertTrue(document.lines.any { it.startsWith("E|") })
            assertTrue(document.lines.any { it.startsWith("A|") })
            assertTrue(document.lines.any { it.contains("Measure 144") })
            assertTrue(document.lines.size <= PlainTextTabDecoder.MAX_LINES)
        }
    }

    @Test fun `GP7 corruption HTML legacy binary and malicious XML are rejected`() {
        listOf("<html>error</html>".toByteArray(), "FICHIER GUITAR PRO".toByteArray(),
            zip("<!DOCTYPE GPIF [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><GPIF><Score><Title>&xxe;</Title></Score></GPIF>"),
            zip("<html>not a score</html>")).forEach { bytes ->
            assertTrue(runCatching { GuitarProPreview.decode(bytes.inputStream()) }.isFailure)
        }
    }

    @Test fun `provider reports unsupported instruments without fabricating frets`() = runTest {
        val provider = TabForgeProvider(http(fixture()))
        val results = provider.search(SearchQuery("Ashen Meridian"))
        assertTrue(results.any { it.native == NativeContent.AVAILABLE && ArrangementTag.LEAD in it.tags })
        assertTrue(results.any { it.native == NativeContent.UNSUPPORTED && ArrangementTag.BASS in it.tags })
        assertTrue(results.filter { it.native == NativeContent.UNSUPPORTED }.none { it.rights.display || it.rights.save })
        val selected = results.first { it.native == NativeContent.AVAILABLE }
        val tab = provider.retrieve(selected)
        assertTrue(tab.document.lines.size > 6)
        val folder = temp.newFolder()
        val library = LibraryRepository(folder)
        library.initialize()
        val song = library.saveAuthorized(tab)
        library.toggleFavorite(song.id)
        library.togglePractice(song.id)
        val restarted = LibraryRepository(folder)
        restarted.initialize()
        assertEquals("CC0-1.0", restarted.state.value.songs.last().provenance?.license)
        assertEquals(selected.arrangement, restarted.state.value.songs.last().provenance?.arrangement)
        assertEquals(selected.tags, restarted.state.value.songs.last().provenance?.tags)
        assertTrue(restarted.state.value.songs.last().favorite)
        assertTrue(restarted.state.value.songs.last().practice)
        assertEquals(song.id, restarted.saveAuthorized(tab).id)
        assertEquals(4, restarted.state.value.songs.size)
        assertTrue(restarted.document(song.id).lines.size > 6)
    }

    @Test fun `saving unlicensed content is rejected before files are created`() = runTest {
        val library = LibraryRepository(temp.newFolder())
        library.initialize()
        val result = TabSearchResult("unknown", "1", "Song", "Artist", "Unknown", "", "Tab")
        val document = PlainTextTabDecoder.decode("test.txt", Examples.exercises.first().second.byteInputStream())
        assertTrue(runCatching { library.saveAuthorized(AuthorizedTab(result, document)) }.isFailure)
        assertEquals(3, library.state.value.songs.size)
    }

    @Test fun `MusicBrainz validates metadata and never claims tab access`() = runTest {
        val json = """{"recordings":[{"id":"fa337b72-eccb-439c-889c-39977493af90","title":"Nutshell","artist-credit":[{"name":"Alice in Chains","artist":{"name":"Alice in Chains"}}]}]}"""
        val result = MusicBrainzProvider(http(json.toByteArray())).search(SearchQuery("Nutshell", "Alice in Chains")).single()
        assertEquals("Alice in Chains", result.artist)
        assertEquals(NativeContent.METADATA_ONLY, result.native)
        assertFalse(result.rights.display)
        assertTrue(result.tags.isEmpty())
        assertTrue(runCatching { MusicBrainzProvider(http("<html>bad</html>".toByteArray())).search(SearchQuery("song")) }.isFailure)
        assertTrue(runCatching { MusicBrainzProvider(http("{}".toByteArray())).search(SearchQuery("song")) }.isFailure)
    }

    @Test fun `legacy v01 metadata still loads with new optional provenance fields`() = runTest {
        val folder = temp.newFolder()
        File(folder, "library.json").writeText("""{"version":1,"songs":[{"id":"old","title":"Saved","artist":"Artist","favorite":true,"practice":true}],"settings":{"fontSize":24},"currentSongId":"old"}""")
        val library = LibraryRepository(folder)
        library.initialize()
        val old = library.state.value.songs.single()
        assertTrue(old.favorite && old.practice)
        assertNull(old.provenance)
        assertEquals(24, library.state.value.settings.fontSize)
    }
}
