package org.opentabs.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class TrackInfo(val title: String, val artist: String, val album: String? = null)

sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reason: String) : Availability
}

interface TabSearchProvider {
    val name: String
    val availability: Availability
    suspend fun search(query: String): List<Song>
}

class LocalTabSearchProvider(private val library: LibraryRepository) : TabSearchProvider {
    override val name = "Local library"
    override val availability = Availability.Available
    override suspend fun search(query: String) = LocalSearch.search(library.state.value.songs, query)
}

interface NowPlayingProvider {
    val availability: Availability
    val tracks: Flow<TrackInfo?>
    suspend fun connect(): Availability
    suspend fun disconnect()
}

class UnconfiguredSpotifyProvider : NowPlayingProvider {
    override val availability = Availability.Unavailable(
        "Spotify is not configured in v0.1. A registered Spotify app, PKCE authentication, and an official API adapter are required."
    )
    override val tracks: Flow<TrackInfo?> = flowOf(null)
    override suspend fun connect() = availability
    override suspend fun disconnect() = Unit
}

sealed interface RecognitionResult {
    data class Match(val track: TrackInfo) : RecognitionResult
    data class Unavailable(val reason: String) : RecognitionResult
    data object NoMatch : RecognitionResult
}

/** A configured adapter must request audio permission on explicit user action and bound recording. */
interface MusicRecognitionProvider {
    val availability: Availability
    suspend fun recognize(): RecognitionResult
    fun cancel()
}

class UnconfiguredRecognitionProvider : MusicRecognitionProvider {
    override val availability = Availability.Unavailable(
        "Song identification is unavailable in v0.1. ShazamKit requires its Android SDK and a developer-token service. Voice commands are available separately."
    )
    override suspend fun recognize() = RecognitionResult.Unavailable(availability.reason)
    override fun cancel() = Unit
}
