package org.opentabs.core

import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Documented, key-free recording search. Core recording/artist metadata is CC0, never tablature. */
class MusicBrainzProvider(private val http: ProviderHttp) : TabSearchProvider {
    override val id = "musicbrainz"
    override val name = "MusicBrainz"
    override val online = true
    override val availability = Availability.Available
    private val rate = Mutex()
    private var lastRequest = 0L

    override suspend fun search(query: SearchQuery): List<TabSearchResult> {
        if (query.isEmpty) return emptyList()
        val expression = if (query.artist.isNotBlank()) {
            listOfNotNull(query.title.takeIf { it.isNotBlank() }?.let { "recording:(${terms(it)})" },
                "artist:(${terms(query.artist)})").joinToString(" AND ")
        } else terms(query.title)
        val url = "https://musicbrainz.org/ws/2/recording/?query=${URLEncoder.encode(expression, "UTF-8")}&fmt=json&limit=25"
        // A single instance serves all searches; canceled searches cannot reset the one-request/second interval.
        val payload = rate.withLock {
            val now = System.nanoTime() / 1_000_000
            delay((1100 - (now - lastRequest)).coerceAtLeast(0))
            lastRequest = System.nanoTime() / 1_000_000
            http.get(url, 256 * 1024, 5 * 60 * 1000L)
        }
        val root = Json.parseToJsonElement(payload.bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        val recordings = root["recordings"] as? JsonArray ?: error("Invalid MusicBrainz response: recordings are missing.")
        val results = recordings.take(25).map { element ->
            val recording = element.jsonObject
            val recordingId = recording["id"]?.jsonPrimitive?.content.orEmpty()
            val title = recording["title"]?.jsonPrimitive?.content.orEmpty()
            require(recordingId.matches(Regex("[0-9a-fA-F-]{36}")) && title.isNotBlank()) { "Invalid recording metadata." }
            val credits = recording["artist-credit"] as? JsonArray ?: error("Invalid artist metadata.")
            val artist = credits.joinToString("") { credit ->
                val obj = credit.jsonObject
                val artistObject = obj["artist"]?.jsonObject
                (obj["name"]?.jsonPrimitive?.content ?: artistObject?.get("name")?.jsonPrimitive?.content.orEmpty()) +
                    obj["joinphrase"]?.jsonPrimitive?.content.orEmpty()
            }.take(300)
            require(artist.isNotBlank()) { "Invalid artist metadata." }
            TabSearchResult(id, recordingId, title.take(300), artist, name, "https://musicbrainz.org/recording/$recordingId",
                "No arrangement supplied", native = NativeContent.METADATA_ONLY,
                rights = ContentRights(license = "CC0-1.0 metadata", licenseUrl = "https://musicbrainz.org/doc/About/Data_License", attribution = "MusicBrainz contributors"),
                notice = if (payload.stale) "Cached song metadata (offline). No tablature supplied."
                    else if (payload.cached) "Cached song metadata. No tablature supplied." else "Live song metadata. No tablature supplied.")
        }
        if (http is OkHttpProviderHttp) withContext(Dispatchers.IO) { http.cacheValidated(url, payload) }
        return SearchRanking.rank(results, query)
    }

    private fun terms(value: String) = LocalSearch.normalize(value).split(' ').filter { it.isNotBlank() }
        .take(12).joinToString(" AND ") { "($it* OR $it~1)" }
}
