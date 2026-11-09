package org.opentabs.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test

class WebsiteBookmarksTest {
    private inline fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected failure", failed)
    }

    private val songsterr = "https://www.songsterr.com/a/wsa/original-practice-tab-s123"
    private val ultimate = "https://tabs.ultimate-guitar.com/tab/original-artist/practice-tabs-123"

    @Test fun keepsOnlyTabPathsAndDiscardsSensitiveQueryStrings() {
        assertEquals(songsterr, WebsiteBookmarkUrls.canonical("$songsterr?token=secret#part"))
        assertEquals(ultimate, WebsiteBookmarkUrls.canonical("$ultimate/?session=secret"))
        assertEquals("$songsterr/r42", WebsiteBookmarkUrls.canonical("$songsterr/r42?t=1"))
        listOf("https://www.songsterr.com/?pattern=practice", "https://www.songsterr.com/sign-in",
            "https://auth.ultimate-guitar.com/tab/original-artist/practice-tabs-123", "https://example.com/tab/a/b-123",
            "http://tabs.ultimate-guitar.com/tab/a/b-123", "https://user:pass@www.songsterr.com/a/wsa/practice-tab-s123",
            "https://www.songsterr.com/a/wsa/%2e%2e-tab-s123", "https://www.songsterr.com:444/a/wsa/practice-tab-s123")
            .forEach { assertNull(it, WebsiteBookmarkUrls.canonical(it)) }
    }

    @Test fun savesDeduplicatesReopensAndRemovesOnlyLinks() = runBlocking {
        val directory = Files.createTempDirectory("web-links").toFile()
        try {
            val file = directory.resolve("links.json")
            val store = WebsiteBookmarkRepository(file)
            assertTrue(store.list().isEmpty())
            store.save(songsterr, "Original exercise")
            store.save(ultimate, "Other exercise")
            val links = store.save("$songsterr?track=1", "Renamed\n exercise")
            assertEquals(2, links.size)
            assertEquals("Renamed exercise", links.first().title)
            assertEquals(links, WebsiteBookmarkRepository(file).list())
            assertEquals(listOf(ultimate), store.remove(songsterr).map { it.url })
            assertFalse(file.readText().contains("track="))
        } finally { directory.deleteRecursively() }
    }

    @Test fun refusesCorruptOrUnsafeExistingDataWithoutOverwritingIt() = runBlocking {
        val directory = Files.createTempDirectory("bad-links").toFile()
        try {
            val file = directory.resolve("links.json")
            file.writeText("not json")
            val store = WebsiteBookmarkRepository(file)
            assertFails { store.save(songsterr, "Practice") }
            assertEquals("not json", file.readText())
            file.writeText("[{\"url\":\"https://example.com/\",\"title\":\"Unsafe\",\"savedAt\":0}]")
            assertFails { store.list() }
            assertFails { store.remove(songsterr) }
            assertTrue(file.readText().contains("example.com"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun limitsCollectionAndStillAllowsUpdatingExistingLink() = runBlocking {
        val directory = Files.createTempDirectory("full-links").toFile()
        try {
            val store = WebsiteBookmarkRepository(directory.resolve("links.json"))
            repeat(WebsiteBookmarkRepository.MAX_LINKS) { store.save("${songsterr}$it", "Exercise $it") }
            assertFails { store.save(ultimate, "Extra") }
            assertEquals(WebsiteBookmarkRepository.MAX_LINKS, store.save("${songsterr}0", "Updated").size)
        } finally { directory.deleteRecursively() }
    }
    @Test fun multipleInstancesSerializeWritesAcrossScreenRecreation() = runBlocking {
        val directory = Files.createTempDirectory("parallel-links").toFile()
        try {
            val file = directory.resolve("links.json")
            val first = WebsiteBookmarkRepository(file)
            val second = WebsiteBookmarkRepository(file)
            (0 until 30).map { index -> async {
                (if (index % 2 == 0) first else second).save("${songsterr}$index", "Exercise $index")
            } }.awaitAll()
            assertEquals(30, first.list().size)
            assertEquals(first.list(), second.list())
            assertFalse(directory.resolve("links.json.tmp").exists())
        } finally { directory.deleteRecursively() }
    }

}
