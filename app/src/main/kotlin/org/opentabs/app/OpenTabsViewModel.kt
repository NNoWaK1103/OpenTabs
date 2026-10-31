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
)

data class DocumentState(val songId: String? = null, val tab: TabDocument? = null, val error: String? = null)

class OpenTabsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(File(application.filesDir, "library"))
    val library = repository.state
    private val mutableScreen = MutableStateFlow(ScreenState())
    val screen = mutableScreen.asStateFlow()
    private val mutableDocument = MutableStateFlow(DocumentState())
    val document = mutableDocument.asStateFlow()
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
    }
    fun setQuery(query: String) { mutableScreen.value = screen.value.copy(query = query) }
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
                    repository.importTab(name, input)
                }
                repository.open(song.id)
                navigate(Destination.NOW_PLAYING)
                notify("Imported ${song.title}. Use Edit details to set artist and tuning.")
            } finally { mutableScreen.value = screen.value.copy(importing = false) }
        }
    }

    fun voice(text: String) {
        when (val command = VoiceCommandParser.parse(text)) {
            is VoiceCommand.Find -> { setQuery(command.query); navigate(Destination.SEARCH) }
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
