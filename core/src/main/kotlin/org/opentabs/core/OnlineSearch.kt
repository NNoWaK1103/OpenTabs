package org.opentabs.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class Provenance(
    val provider: String,
    val sourceUrl: String,
    val license: String,
    val licenseUrl: String,
    val attribution: String,
    val arrangementId: String,
    val notice: String = "",
    val arrangement: String = "",
    val tags: Set<ArrangementTag> = emptySet(),
)

@Serializable
enum class ArrangementTag(val label: String) {
    GUITAR("Guitar"), BASS("Bass"), ACOUSTIC("Acoustic"), LEAD("Lead"), RHYTHM("Rhythm"), CHORDS("Chords")
}

enum class NativeContent(val label: String) {
    LOCAL("Saved tab"), AVAILABLE("Native tab available"), METADATA_ONLY("Song metadata only · no tablature"),
    UNSUPPORTED("Arrangement format not supported"), UNAUTHORIZED("Native access not authorized")
}

@Serializable
data class ContentRights(
    val display: Boolean = false,
    val cache: Boolean = false,
    val save: Boolean = false,
    val license: String = "Unknown",
    val licenseUrl: String = "",
    val attribution: String = "",
)

data class TabSearchResult(
    val providerId: String,
    val id: String,
    val title: String,
    val artist: String,
    val source: String,
    val sourceUrl: String,
    val arrangement: String,
    val tuning: String? = null,
    val tags: Set<ArrangementTag> = emptySet(),
    val native: NativeContent = NativeContent.METADATA_ONLY,
    val rights: ContentRights = ContentRights(),
    val localId: String? = null,
    val notice: String = "",
    val originArrangementId: String? = null,
    val keywords: String = "",
)

data class SearchQuery(val title: String, val artist: String = "") {
    val isEmpty get() = title.isBlank() && artist.isBlank()
    val words get() = "$title $artist".trim()
}

data class AuthorizedTab(val result: TabSearchResult, val document: TabDocument,
    val practiceScore: PracticeScore? = null, val playbackUnavailableReason: String? = null)

interface TabSearchProvider {
    val id: String
    val name: String
    val availability: Availability
    val online: Boolean
    suspend fun search(query: SearchQuery): List<TabSearchResult>
    suspend fun retrieve(result: TabSearchResult): AuthorizedTab = error("This source supplies metadata only, not tablature.")
}

class LocalTabSearchProvider(private val library: LibraryRepository) : TabSearchProvider {
    override val id = "local"
    override val name = "My Tabs"
    override val online = false
    override val availability = Availability.Available
    override suspend fun search(query: SearchQuery): List<TabSearchResult> =
        SearchRanking.rank(library.state.value.songs.map { song ->
            TabSearchResult(id, song.id, song.title, song.artist, name, song.provenance?.sourceUrl.orEmpty(),
                song.provenance?.arrangement?.ifBlank { "Plain-text guitar" } ?: "Plain-text guitar",
                song.tuning, song.provenance?.tags?.takeIf { it.isNotEmpty() } ?: setOf(ArrangementTag.GUITAR), NativeContent.LOCAL, localId = song.id,
                originArrangementId = song.provenance?.arrangementId, keywords = "${song.album} ${song.tuning}")
        }, query)
}

enum class ProviderStatus { COMPLETE, FAILED, TIMED_OUT, OFFLINE, UNAVAILABLE }
data class ProviderReport(val id: String, val name: String, val status: ProviderStatus, val message: String = "")
data class SearchSnapshot(
    val query: SearchQuery = SearchQuery(""),
    val results: List<TabSearchResult> = emptyList(),
    val reports: List<ProviderReport> = emptyList(),
    val pending: Int = 0,
)

/** Emits local / completed providers immediately; one slow or broken source cannot hold up others. */
class UnifiedTabSearch(
    private val providers: List<TabSearchProvider>,
    private val timeoutMillis: Long = 12_000,
    private val onlineLimit: Int = 2,
) {
    fun search(query: SearchQuery, online: Boolean): Flow<SearchSnapshot> = channelFlow {
        val results = mutableListOf<TabSearchResult>()
        val reports = mutableListOf<ProviderReport>()
        val lock = kotlinx.coroutines.sync.Mutex()
        val limit = Semaphore(onlineLimit)
        var pending = providers.size
        send(SearchSnapshot(query, pending = pending))
        coroutineScope {
            providers.map { provider -> async {
                var found = emptyList<TabSearchResult>()
                val report = when {
                    provider.availability is Availability.Unavailable -> ProviderReport(provider.id, provider.name,
                        ProviderStatus.UNAVAILABLE, (provider.availability as Availability.Unavailable).reason)
                    provider.online && !online -> ProviderReport(provider.id, provider.name, ProviderStatus.OFFLINE, "Online search is off; saved tabs remain available.")
                    else -> try {
                        val completed = withTimeoutOrNull(timeoutMillis) {
                            if (provider.online) limit.withPermit { provider.search(query) } else provider.search(query)
                        }
                        if (completed == null) ProviderReport(provider.id, provider.name, ProviderStatus.TIMED_OUT, "Timed out. Try again.")
                        else {
                            found = completed
                            ProviderReport(provider.id, provider.name, ProviderStatus.COMPLETE)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        ProviderReport(provider.id, provider.name, ProviderStatus.FAILED,
                            error.message?.take(220) ?: "Source unavailable. Saved tabs still work.")
                    }
                }
                lock.lock()
                try {
                    results += found
                    reports += report
                    pending--
                    send(SearchSnapshot(query, SearchRanking.deduplicate(SearchRanking.rank(results, query)), reports.toList(), pending))
                } finally { lock.unlock() }
            } }.awaitAll()
        }
    }

    suspend fun retrieve(result: TabSearchResult): AuthorizedTab {
        require(result.native == NativeContent.AVAILABLE && result.rights.display) { "Native tablature is not authorized for this result." }
        val provider = providers.firstOrNull { it.id == result.providerId } ?: error("Unknown source.")
        return withTimeoutOrNull(timeoutMillis) { provider.retrieve(result) }
            ?: error("The download timed out. Try again.")
    }
}

object SearchRanking {
    private fun normalized(value: String) = LocalSearch.normalize(value).take(300)

    fun rank(results: List<TabSearchResult>, query: SearchQuery): List<TabSearchResult> = results
        .map { it to score(it, query) }.filter { it.second >= 0 }
        .sortedWith(compareByDescending<Pair<TabSearchResult, Int>> { it.second }
            .thenByDescending { it.first.native == NativeContent.LOCAL }
            .thenByDescending { it.first.native == NativeContent.AVAILABLE }
            .thenBy { it.first.title }).map { it.first }

    fun score(result: TabSearchResult, query: SearchQuery): Int {
        if (query.isEmpty) return 0
        val title = normalized(result.title)
        val artist = normalized(result.artist)
        val wantedTitle = normalized(query.title)
        val wantedArtist = normalized(query.artist)
        if (wantedArtist.isNotEmpty() && !matches(artist, wantedArtist)) return -1
        if (wantedTitle.isNotEmpty() && !matches("$title $artist ${normalized(result.arrangement)} ${normalized(result.keywords)}", wantedTitle)) return -1
        return (if (wantedTitle.isNotBlank() && title == wantedTitle) 1000 else if (title.contains(wantedTitle) && wantedTitle.isNotBlank()) 300 else 0) +
            (if (wantedArtist.isNotBlank() && artist == wantedArtist) 800 else if (wantedArtist.isNotBlank()) 150 else 0)
    }

    private fun matches(text: String, wanted: String): Boolean {
        val candidates = text.split(' ')
        return wanted.split(' ').filter { it.isNotBlank() }.all { term ->
            candidates.any { candidate -> candidate.contains(term) || (term.length >= 4 && distance(candidate, term) <= 1) }
        }
    }

    private fun distance(first: String, second: String): Int {
        if (kotlin.math.abs(first.length - second.length) > 1) return 2
        var prior = IntArray(second.length + 1) { it }
        first.forEachIndexed { index, char ->
            val next = IntArray(second.length + 1)
            next[0] = index + 1
            second.forEachIndexed { column, other ->
                next[column + 1] = minOf(next[column] + 1, prior[column + 1] + 1, prior[column] + if (char == other) 0 else 1)
            }
            prior = next
        }
        return prior.last()
    }

    fun deduplicate(results: List<TabSearchResult>): List<TabSearchResult> {
        val unique = results.distinctBy { "${it.providerId}:${it.id}" }.distinctBy {
            "${normalized(it.title)}|${normalized(it.artist)}|${normalized(it.arrangement)}|${it.tuning.orEmpty()}|${it.native == NativeContent.METADATA_ONLY}"
        }
        // A saved arrangement replaces its downloadable duplicate, without merging distinct arrangements.
        val saved = unique.mapNotNull { it.originArrangementId }.toSet()
        return unique.filterNot { result -> result.native == NativeContent.AVAILABLE && "${result.providerId}:${result.id}" in saved }
    }
}
