package org.opentabs.core

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnlineSearchTest {
    private fun result(id: String = "1", title: String = "Evening", artist: String = "Ada", arrangement: String = "Lead") =
        TabSearchResult("test", id, title, artist, "Test", "", arrangement)
    private fun provider(id: String, online: Boolean = true, block: suspend () -> List<TabSearchResult>) = object : TabSearchProvider {
        override val id = id
        override val name = id
        override val online = online
        override val availability = Availability.Available
        override suspend fun search(query: SearchQuery) = block()
    }

    @Test fun `providers run concurrently and partial results appear before the slow provider`() = runTest {
        val engine = UnifiedTabSearch(listOf(
            provider("slow") { delay(2000); listOf(result("2")) },
            provider("fast") { delay(100); listOf(result()) },
        ))
        val snapshots = engine.search(SearchQuery("Evening"), true).toList()
        assertEquals(2000, testScheduler.currentTime)
        assertEquals(1, snapshots[1].pending)
        assertEquals("1", snapshots[1].results.single().id)
        assertEquals(0, snapshots.last().pending)
    }

    @Test fun `failure and timeout preserve successful results`() = runTest {
        val engine = UnifiedTabSearch(listOf(
            provider("broken") { throw IOException("offline") },
            provider("slow") { delay(5000); emptyList() },
            provider("local", false) { listOf(result()) },
        ), timeoutMillis = 1000)
        val state = engine.search(SearchQuery("Evening"), true).toList().last()
        assertEquals(1, state.results.size)
        assertTrue(state.reports.any { it.status == ProviderStatus.FAILED })
        assertTrue(state.reports.any { it.status == ProviderStatus.TIMED_OUT })
    }

    @Test fun `offline searches never invoke remote providers`() = runTest {
        val state = UnifiedTabSearch(listOf(provider("remote") { error("Must not be called") },
            provider("local", false) { listOf(result()) })).search(SearchQuery("Evening"), false).toList().last()
        assertEquals(1, state.results.size)
        assertTrue(state.reports.any { it.status == ProviderStatus.OFFLINE })
    }

    @Test fun `canceling a search cancels its providers`() = runTest {
        var canceled = false
        val engine = UnifiedTabSearch(listOf(provider("slow") {
            try { delay(60_000); emptyList() } finally { canceled = true }
        }))
        val job = launch { engine.search(SearchQuery("test"), true).toList() }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertTrue(canceled)
    }

    @Test fun `concurrency is bounded across remote sources`() = runTest {
        var active = 0
        var maximum = 0
        val engine = UnifiedTabSearch((1..6).map { index -> provider("p$index") {
            active++; maximum = maxOf(maximum, active); delay(100); active--; emptyList()
        } }, onlineLimit = 2)
        engine.search(SearchQuery("test"), true).toList()
        assertEquals(2, maximum)
    }

    @Test fun `exact title and artist outrank partial and fuzzy matches`() {
        val exact = result("exact", "Evening", "Ada")
        val partial = result("partial", "Evening study", "Ada Guitar")
        val fuzzy = result("fuzzy", "Evenin", "Ada")
        val query = SearchQuery("Evening", "Ada")
        val ranked = SearchRanking.rank(listOf(partial, fuzzy, exact), query)
        assertEquals("exact", ranked.first().id)
        assertTrue(ranked.any { it.id == "fuzzy" })
        assertTrue(SearchRanking.rank(listOf(exact), SearchQuery("Unrelated")).isEmpty())
    }

    @Test fun `duplicates merge without removing different arrangements`() {
        val lead = result()
        val rhythm = result("2", arrangement = "Rhythm")
        val duplicate = lead.copy(providerId = "another")
        assertEquals(2, SearchRanking.deduplicate(listOf(lead, duplicate, rhythm)).size)
        val remote = lead.copy(native = NativeContent.AVAILABLE)
        val saved = lead.copy(providerId = "local", id = "uuid", localId = "uuid", native = NativeContent.LOCAL,
            originArrangementId = "test:1")
        assertEquals(listOf(saved), SearchRanking.deduplicate(listOf(saved, remote)))
    }

    @Test fun `metadata cannot be downloaded or mistaken for native content`() = runTest {
        val engine = UnifiedTabSearch(emptyList())
        try { engine.retrieve(result()); fail("Metadata must not be downloadable") }
        catch (_: IllegalArgumentException) { }
        assertNotEquals(NativeContent.AVAILABLE, result().native)
        assertFalse(result().rights.display)
        assertFalse(result().rights.save)
    }
}
