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
    NOW_PLAYING("Now Playing"), SEARCH("Search"), LIBRARY("My Tabs"), PRACTICE("Practice"), TUNER("Tuner"), SETTINGS("Settings")
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
    val tabSource: WebTabSource = WebTabSource.SONGSTERR,
    val webTab: WebTabRequest? = null,
    val tunerReturnDestination: Destination = Destination.NOW_PLAYING,
    val tunerTab: WebTabRequest? = null,
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
    private val mutableGuitarProImport = MutableStateFlow<GuitarProImport?>(null)
    val guitarProImport = mutableGuitarProImport.asStateFlow()
    val webTabMemory = WebTabMemory()
    val tunerMemory = TunerMemory()
    private var webRevision = 0L
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
        val current = screen.value
        if (destination == Destination.TUNER) {
            if (current.destination == Destination.TUNER) return
            mutableScreen.value = current.copy(destination = destination, scrolling = false, fullScreen = false,
                tunerReturnDestination = current.destination, tunerTab = current.webTab, webTab = null)
        } else {
            val restore = current.tunerTab.takeIf {
                current.destination == Destination.TUNER && destination == current.tunerReturnDestination
            }
            mutableScreen.value = current.copy(destination = destination, scrolling = false, fullScreen = false,
                webTab = restore, tunerTab = null)
        }
    }
    fun closeTuner() { navigate(screen.value.tunerReturnDestination) }
    fun setQuery(query: String) {
        mutableScreen.value = screen.value.copy(query = query.take(160))
    }
    fun selectTabSource(source: WebTabSource) { mutableScreen.value = screen.value.copy(tabSource = source) }
    fun searchTabs() {
        if (screen.value.query.isBlank() && screen.value.artistQuery.isBlank()) return
        browse(screen.value.tabSource)
    }
    fun browse(source: WebTabSource) {
        val previous = screen.value.webTab
        mutableScreen.value = screen.value.copy(destination = Destination.SEARCH, scrolling = false, tabSource = source,
            webTab = WebTabRequest(source, previous?.title ?: screen.value.query,
                previous?.artist ?: screen.value.artistQuery, ++webRevision))
    }
    fun closeBrowser() { navigate(Destination.SEARCH) }
    fun setArtistQuery(artist: String) {
        mutableScreen.value = screen.value.copy(artistQuery = artist.take(160))
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
                    artistQuery = original?.groupValues?.get(2).orEmpty(), tunerTab = null)
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
