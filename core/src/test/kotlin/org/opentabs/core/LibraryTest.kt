package org.opentabs.core

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `first run seeds three valid original exercises and later launches preserve metadata`() = runTest {
        val folder = temporary.newFolder()
        val first = LibraryRepository(folder)
        first.initialize()
        assertEquals(3, first.state.value.songs.size)
        first.state.value.songs.forEach { assertTrue(first.document(it.id).lines.size > 6) }
        val selected = first.state.value.songs[1]
        first.open(selected.id)
        first.edit(selected.id, "Renamed", "Guitarist", "D A D G B e", "Practice notes")
        val reopened = LibraryRepository(folder)
        reopened.initialize()
        assertEquals(selected.id, reopened.state.value.currentSongId)
        val song = reopened.state.value.songs.first { it.id == selected.id }
        assertEquals("Renamed", song.title)
        assertEquals("Practice notes", song.album)
        assertTrue(song.lastOpened > 0)
        assertEquals(3, reopened.state.value.songs.size)
    }

    @Test fun `import copies content and persists favorites and practice independently`() = runTest {
        val folder = temporary.newFolder()
        val repo = LibraryRepository(folder)
        repo.initialize()
        val source = File(temporary.newFolder(), "My tab.txt").apply { writeText(Examples.exercises.first().second) }
        val song = repo.importTab(source.name, source.inputStream())
        source.delete()
        repo.toggleFavorite(song.id)
        repo.togglePractice(song.id)
        val reopened = LibraryRepository(folder)
        reopened.initialize()
        assertTrue(reopened.state.value.songs.last().favorite)
        assertTrue(reopened.state.value.songs.last().practice)
        assertTrue(reopened.document(song.id).columns > 0)
        reopened.toggleFavorite(song.id)
        assertFalse(reopened.state.value.songs.last().favorite)
        assertTrue(reopened.state.value.songs.last().practice)
        reopened.togglePractice(song.id)
        val final = LibraryRepository(folder)
        final.initialize()
        assertFalse(final.state.value.songs.last().practice)
        assertFalse(final.state.value.songs.last().favorite)
    }

    @Test fun `deleting selected import removes its metadata file and list entries`() = runTest {
        val folder = temporary.newFolder()
        val repo = LibraryRepository(folder)
        repo.initialize()
        val song = repo.importTab("new.tab", Examples.exercises.first().second.byteInputStream())
        repo.open(song.id)
        repo.toggleFavorite(song.id)
        repo.togglePractice(song.id)
        repo.delete(song.id)
        val reopened = LibraryRepository(folder)
        reopened.initialize()
        assertNull(reopened.state.value.currentSongId)
        assertFalse(reopened.state.value.songs.any { it.id == song.id })
        assertFalse(File(folder, "${song.id}.txt").exists())
    }

    @Test fun `invalid import never creates a library entry`() = runTest {
        val repo = LibraryRepository(temporary.newFolder())
        repo.initialize()
        try { repo.importTab("bad.txt", "hello".byteInputStream()); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(3, repo.state.value.songs.size)
    }

    @Test fun `viewer settings survive restart and invalid ranges are clamped`() = runTest {
        val folder = temporary.newFolder()
        val repo = LibraryRepository(folder)
        repo.initialize()
        repo.updateSettings { ViewerSettings(26, 12, 70, false) }
        val reopened = LibraryRepository(folder)
        reopened.initialize()
        assertEquals(ViewerSettings(26, 12, 70, false), reopened.state.value.settings)
        reopened.updateSettings { ViewerSettings(100, -2, 1000, true) }
        assertEquals(ViewerSettings(32, 0, 120, true), reopened.state.value.settings)
    }

    @Test fun `concurrent updates do not lose independent flags`() = runTest {
        val repo = LibraryRepository(temporary.newFolder())
        repo.initialize()
        val id = repo.state.value.songs.first().id
        listOf(async { repo.toggleFavorite(id) }, async { repo.togglePractice(id) }).awaitAll()
        assertTrue(repo.state.value.songs.first().favorite)
        assertTrue(repo.state.value.songs.first().practice)
    }

    @Test fun `corrupt metadata is reported without overwriting user files`() = runTest {
        val folder = temporary.newFolder()
        val metadata = File(folder, "library.json").apply { writeText("broken metadata") }
        try { LibraryRepository(folder).initialize(); fail("Expected failure") }
        catch (_: IllegalArgumentException) { }
        assertEquals("broken metadata", metadata.readText())
    }

    @Test fun `built in exercises cannot be deleted`() = runTest {
        val repo = LibraryRepository(temporary.newFolder())
        repo.initialize()
        try { repo.delete(repo.state.value.songs.first().id); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(3, repo.state.value.songs.size)
    }
}
