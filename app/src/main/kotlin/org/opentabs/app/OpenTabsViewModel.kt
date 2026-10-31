package org.opentabs.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opentabs.core.*

enum class Destination(val label: String) {
    NOW_PLAYING("Now Playing"), SEARCH("Search"), LIBRARY("My Tabs"), PRACTICE("Practice"), SETTINGS("Settings")
}

enum class LibraryFilter(val label: String) { ALL("All tabs"), FAVORITES("Favorites"), RECENT("Recent") }

data class ScreenState(
    val destination: Destination = Destination.NOW_PLAYING,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val query: String = "",
    val scrolling: Boolean = false,
    val fullScreen: Boolean = false,
    val importing: Boolean = false,
    val ready: Boolean = false,
    val startupError: String? = null,
    val artistQuery: String = "",
    val onlineSearch: Boolean = true,
    val searchFilter: ArrangementTag? = null,
    val nativeOnly: Boolean = false,
    val downloading: String? = null,
)

data class DocumentState(val songId: String? = null, val tab: TabDocument? = null, val error: String? = null)
data class GuitarProImport(val score: GuitarProScore, val tracks: List<GuitarTrack>)

class OpenTabsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(File(application.filesDir, "library"))
    val library = repository.state
    private val mutableScreen = MutableStateFlow(ScreenState())
    val screen = mutableScreen.asStateFlow()
    private val mutableDocument = MutableStateFlow(DocumentState())
    val document = mutableDocument.asStateFlow()
    private val mutableSearch = MutableStateFlow(SearchSnapshot())
    val search = mutableSearch.asStateFlow()
    private val mutableGuitarProImport = MutableStateFlow<GuitarProImport?>(null)
    val guitarProImport = mutableGuitarProImport.asStateFlow()
    private val http = providerHttp(File(application.cacheDir, "licensed-search"))
    private val searchEngine = UnifiedTabSearch(listOf(LocalTabSearchProvider(repository), MusicBrainzProvider(http), TabForgeProvider(http)))
    private var searchJob: Job? = null
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()
    val spotify = UnconfiguredSpotifyProvider()
    val recognition = UnconfiguredRecognitionProvider()
    private var opening: Job? = null

    init {
        initialize()
        viewModelScope.launch {
            library.map { it.currentSongId }.distinctUntilChanged().collect { id ->
                mutableDocument.value = DocumentState(songId = id)
                if (id != null) {
                    try { mutableDocument.value = DocumentState(id, repository.document(id)) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableDocument.value = DocumentState(id, error = error.message ?: "Cannot read this tab.")
                    }
                }
            }
        }
    }

    fun initialize() {
        mutableScreen.value = screen.value.copy(startupError = null)
        viewModelScope.launch {
            try {
                repository.initialize()
                mutableScreen.value = screen.value.copy(ready = true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableScreen.value = screen.value.copy(startupError = "Cannot open your library: ${error.message}. Your files have been preserved.")
            }
        }
    }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            notify(error.message ?: "The operation could not be completed.")
        }
    }

    fun notify(message: String) { notifications.trySend(message) }
    fun navigate(destination: Destination) {
        mutableScreen.value = screen.value.copy(destination = destination, scrolling = false, fullScreen = false)
        if (destination == Destination.SEARCH) scheduleSearch(false) else cancelSearch()
    }
    fun setQuery(query: String) {
        mutableScreen.value = screen.value.copy(query = query.take(160), searchFilter = null)
        if (screen.value.destination == Destination.SEARCH) scheduleSearch()
    }
    fun setArtistQuery(artist: String) {
        mutableScreen.value = screen.value.copy(artistQuery = artist.take(160), searchFilter = null)
        scheduleSearch()
    }
    fun onlineSearch(enabled: Boolean) {
        mutableScreen.value = screen.value.copy(onlineSearch = enabled)
        scheduleSearch(false)
    }
    fun searchFilter(tag: ArrangementTag?) { mutableScreen.value = screen.value.copy(searchFilter = tag) }
    fun nativeOnly(enabled: Boolean) { mutableScreen.value = screen.value.copy(nativeOnly = enabled) }
    fun refreshSearch() { scheduleSearch(false) }
    fun cancelSearch() {
        searchJob?.cancel()
        mutableSearch.value = search.value.copy(pending = 0)
    }
    private fun scheduleSearch(debounce: Boolean = true) {
        cancelSearch()
        val query = SearchQuery(screen.value.query, screen.value.artistQuery)
        searchJob = action {
            if (debounce) delay(450)
            searchEngine.search(query, screen.value.onlineSearch && !query.isEmpty).collect { mutableSearch.value = it }
        }
    }
    fun openSearchResult(result: TabSearchResult) {
        result.localId?.let { id -> library.value.songs.firstOrNull { it.id == id }?.let(::open); return }
        if (screen.value.downloading != null) return
        mutableScreen.value = screen.value.copy(downloading = "${result.providerId}:${result.id}")
        action {
            try {
                val song = repository.saveAuthorized(searchEngine.retrieve(result))
                repository.open(song.id)
                navigate(Destination.NOW_PLAYING)
                notify("Saved ${song.title} for offline practice, with source attribution.")
            } finally { mutableScreen.value = screen.value.copy(downloading = null) }
        }
    }
    fun filter(filter: LibraryFilter) { mutableScreen.value = screen.value.copy(filter = filter) }
    fun fullScreen(enabled: Boolean) { mutableScreen.value = screen.value.copy(fullScreen = enabled) }
    fun scroll(enabled: Boolean) {
        if (enabled && document.value.tab == null) { notify("Open a tab before starting auto-scroll."); return }
        mutableScreen.value = screen.value.copy(scrolling = enabled)
    }

    fun open(song: Song) {
        scroll(false)
        opening?.cancel()
        opening = action {
            repository.open(song.id)
            navigate(Destination.NOW_PLAYING)
        }
    }
    fun favorite(song: Song) { action { repository.toggleFavorite(song.id) } }
    fun practice(song: Song) { action { repository.togglePractice(song.id) } }
    fun delete(song: Song) { action { repository.delete(song.id); notify("Deleted ${song.title}") } }
    fun edit(song: Song, title: String, artist: String, tuning: String, album: String) {
        action { repository.edit(song.id, title, artist, tuning, album) }
    }
    fun settings(change: (ViewerSettings) -> ViewerSettings) { action { repository.updateSettings(change) } }

    fun importTab(uri: Uri) {
        if (screen.value.importing) return
        mutableScreen.value = screen.value.copy(importing = true)
        action {
            try {
                val song = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "imported.txt"
                    val input = resolver.openInputStream(uri) ?: error("Cannot read the selected file.")
                    if (name.substringAfterLast('.').equals("gp", ignoreCase = true)) {
                        val score = input.use { withContext(Dispatchers.Default) { GuitarProPreview.decode(it) } }
                        val tracks = withContext(Dispatchers.Default) { score.tracks.filter { score.nativeSupported(it) } }
                        require(tracks.isNotEmpty()) { "No supported six-string, single-staff arrangements with authored frets were found." }
                        mutableGuitarProImport.value = GuitarProImport(score, tracks)
                        null
                    } else repository.importTab(name, input)
                }
                if (song != null) {
                    repository.open(song.id)
                    navigate(Destination.NOW_PLAYING)
                    notify("Imported ${song.title}. Use Edit details to set artist and tuning.")
                }
            } finally { mutableScreen.value = screen.value.copy(importing = false) }
        }
    }

    fun dismissGuitarProImport() { mutableGuitarProImport.value = null }
    fun importGuitarTrack(track: GuitarTrack) {
        val pending = guitarProImport.value ?: return
        mutableGuitarProImport.value = null
        mutableScreen.value = screen.value.copy(importing = true)
        action {
            try {
                val tab = withContext(Dispatchers.Default) { pending.score.document(track.id) }
                val title = "${pending.score.title} · ${track.name}"
                val song = repository.importTab("$title.txt", tab.lines.joinToString("\n").byteInputStream())
                repository.edit(song.id, title, pending.score.artist, tuningLabel(track.tuning), "GP7 position preview")
                repository.open(song.id)
                navigate(Destination.NOW_PLAYING)
                notify("Imported authored positions. Written measures only; repeats, techniques, and playback are not interpreted.")
            } finally { mutableScreen.value = screen.value.copy(importing = false) }
        }
    }

    fun voice(text: String) {
        when (val command = VoiceCommandParser.parse(text)) {
            is VoiceCommand.Find -> {
                val original = Regex("(?i)^(?:find tabs for|search for|find) (.+?) by (.+?)[.!?]*$").matchEntire(text.trim())
                mutableScreen.value = screen.value.copy(query = original?.groupValues?.get(1) ?: command.query,
                    artistQuery = original?.groupValues?.get(2).orEmpty(), searchFilter = null, nativeOnly = false)
                navigate(Destination.SEARCH)
            }
            VoiceCommand.Favorites -> { setQuery(""); filter(LibraryFilter.FAVORITES); navigate(Destination.LIBRARY) }
            VoiceCommand.Practice -> { setQuery(""); navigate(Destination.PRACTICE) }
            VoiceCommand.StartScroll -> { navigate(Destination.NOW_PLAYING); scroll(true) }
            VoiceCommand.StopScroll -> scroll(false)
            VoiceCommand.IncreaseSize -> settings { it.copy(fontSize = it.fontSize + 2) }
            VoiceCommand.DecreaseSize -> settings { it.copy(fontSize = it.fontSize - 2) }
            VoiceCommand.Unknown -> notify("Command not recognized. Try ‘find tabs for…’, ‘open my favorites’, or ‘start scrolling’.")
        }
    }
}
