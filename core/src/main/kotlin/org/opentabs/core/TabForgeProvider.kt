package org.opentabs.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A small, real catalog: TabForge's original CC0 demo, not a commercial-song catalog. */
class TabForgeProvider(private val http: ProviderHttp) : TabSearchProvider {
    override val id = "tabforge"
    override val name = "TabForge CC0"
    override val online = true
    override val availability = Availability.Available
    private val mutex = Mutex()

    companion object {
        const val URL = "https://raw.githubusercontent.com/cobhc95/TabForge/main/samples/TabForge%20Demo%20-%20Ashen%20Meridian.gp"
        const val SOURCE = "https://github.com/cobhc95/TabForge/tree/main/samples"
        const val LICENSE = "https://creativecommons.org/publicdomain/zero/1.0/"
    }

    private suspend fun score(): Pair<GuitarProScore, HttpPayload> = mutex.withLock {
        val payload = http.get(URL, GuitarProPreview.MAX_ARCHIVE, 24 * 60 * 60 * 1000L)
        val score = withContext(Dispatchers.Default) { GuitarProPreview.decode(payload.bytes.inputStream()) }
        require(score.copyright.contains("CC0", ignoreCase = true)) { "The upstream score no longer declares CC0; download has been disabled." }
        if (http is OkHttpProviderHttp) withContext(Dispatchers.IO) { http.cacheValidated(URL, payload) }
        score to payload
    }

    override suspend fun search(query: SearchQuery): List<TabSearchResult> {
        // This reviewed source contains one composition. Avoid downloading/parsing it for unrelated songs.
        val catalogHint = TabSearchResult(id, "catalog-hint", "Ashen Meridian", "TabForge Demo", name, SOURCE,
            "Guitar bass lead rhythm clean harmony")
        if (SearchRanking.score(catalogHint, query) < 0) return emptyList()
        val (score, payload) = score()
        return withContext(Dispatchers.Default) {
            SearchRanking.rank(score.tracks.filter { ArrangementTag.GUITAR in it.tags || ArrangementTag.BASS in it.tags }.map { track ->
                val supported = score.nativeSupported(track)
                TabSearchResult(id, "ashen-meridian-${track.id}", score.title, score.artist, name, SOURCE, track.name,
                    tuningLabel(track.tuning).takeIf { it.isNotBlank() }, track.tags,
                    if (supported) NativeContent.AVAILABLE else NativeContent.UNSUPPORTED,
                    ContentRights(supported, true, supported, "CC0-1.0", LICENSE, "TabForge contributors — Ashen Meridian"),
                    notice = "Original CC0 demo. ${if (payload.stale) "Cached catalog (offline). " else ""}" +
                        if (supported) "Authored positions with optional offline practice audio. Written order only; expressive techniques are simplified."
                        else "${track.tuning.size}-string / unsupported arrangement. Native preview supports six-string, single-staff guitar only.")
            }, query)
        }
    }

    override suspend fun retrieve(result: TabSearchResult): AuthorizedTab {
        require(result.providerId == id && result.rights.display && result.rights.save) { "This arrangement is not authorized for native saving." }
        val (score, _) = score()
        val trackId = result.id.removePrefix("ashen-meridian-")
        val document = withContext(Dispatchers.Default) { score.document(trackId) }
        val playback = withContext(Dispatchers.Default) { runCatching { score.practiceScore(trackId) } }
        return AuthorizedTab(result, document, playback.getOrNull(), playback.exceptionOrNull()?.message?.take(400))
    }
}
