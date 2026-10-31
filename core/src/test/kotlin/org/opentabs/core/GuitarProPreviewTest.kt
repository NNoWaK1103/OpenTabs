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

class GuitarProPreviewTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/tabforge-ashen-meridian.gp")).readBytes()
    private fun zip(xml: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("Content/score.gpif")); it.write(xml.toByteArray()); it.closeEntry() }
        return bytes.toByteArray()
    }

    @Test fun `real CC0 GP7 import preserves authored arrangements and six string previews`() {
        val score = GuitarProPreview.decode(fixture().inputStream())
        assertEquals("Ashen Meridian", score.title)
        assertTrue(score.copyright.contains("CC0"))
        val supported = score.tracks.filter { score.nativeSupported(it) }
        assertEquals(listOf("Lead Gtr", "Lead Harmony", "Clean Gtr"), supported.map { it.name })
        assertTrue(score.tracks.filter { ArrangementTag.BASS in it.tags }.none { score.nativeSupported(it) })
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

    @Test fun `legacy v01 library still loads without optional provenance fields`() = runTest {
        val folder = temp.newFolder()
        File(folder, "library.json").writeText("""{"version":1,"songs":[{"id":"old","title":"Saved","artist":"Artist","favorite":true,"practice":true}],"settings":{"fontSize":24},"currentSongId":"old"}""")
        val library = LibraryRepository(folder)
        library.initialize()
        val old = library.state.value.songs.single()
        assertTrue(old.favorite && old.practice)
        assertNull(old.provenance)
        assertEquals(24, library.state.value.settings.fontSize)
    }

    @Test fun `previously saved v02 tab retains body attribution favorites and practice across updates`() = runTest {
        val folder = temp.newFolder()
        File(folder, "library.json").writeText("""{"version":1,"songs":[{"id":"saved-tab","title":"Saved arrangement","artist":"Artist","favorite":true,"practice":true,"provenance":{"provider":"Previous source","sourceUrl":"https://example.org/score","license":"CC0-1.0","licenseUrl":"https://creativecommons.org/publicdomain/zero/1.0/","attribution":"Original author","arrangementId":"previous:lead","arrangement":"Lead Gtr","tags":["GUITAR","LEAD"]}}],"currentSongId":"saved-tab"}""")
        File(folder, "saved-tab.txt").writeText(Examples.exercises.first().second)
        val library = LibraryRepository(folder)
        library.initialize()
        val saved = library.state.value.songs.single()
        assertTrue(saved.favorite && saved.practice)
        assertEquals("CC0-1.0", saved.provenance?.license)
        assertEquals(setOf(ArrangementTag.GUITAR, ArrangementTag.LEAD), saved.provenance?.tags)
        assertEquals("Original author", saved.provenance?.attribution)
        assertTrue(library.document(saved.id).lines.size >= 6)
        library.updateSettings { it.copy(fontSize = 24) }
        val restarted = LibraryRepository(folder)
        restarted.initialize()
        assertEquals(saved, restarted.state.value.songs.single())
        assertEquals(library.document(saved.id), restarted.document(saved.id))
    }
}
