package org.opentabs.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.opentabs.core.WebTabSource
import org.opentabs.core.WebsiteBookmarkRepository

/** The launch model deliberately never constructs or migrates the legacy LibraryRepository. */
class ProviderTabsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("provider-viewer", Application.MODE_PRIVATE)
    private val mutableScreen = MutableStateFlow(ProviderTabsState(keepAwake = preferences.getBoolean("keep-awake", true)))
    val screen = mutableScreen.asStateFlow()
    val webTabMemory = WebTabMemory()
    val bookmarks = WebsiteBookmarkRepository(File(application.filesDir, "website-links.json"))
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()

    fun setQuery(query: String) { mutableScreen.value = screen.value.withQuery(query) }
    fun browse(source: WebTabSource) { mutableScreen.value = screen.value.browse(source) }
    fun closeBrowser() { mutableScreen.value = screen.value.closeBrowser() }
    fun fullScreen(enabled: Boolean) { mutableScreen.value = screen.value.fullScreen(enabled) }
    fun keepAwake(enabled: Boolean) {
        mutableScreen.value = screen.value.copy(keepAwake = enabled)
        preferences.edit().putBoolean("keep-awake", enabled).apply()
    }
    fun openSavedLink(url: String) {
        val next = screen.value.openSavedLink(url)
        if (next == screen.value) { notify("This is not a supported saved tab link."); return }
        // Reuse the browser's bounded restore path without turning a link into a local tab copy.
        webTabMemory.clear()
        webTabMemory.revision = next.revision
        webTabMemory.lastUrl = requireNotNull(next.initialSavedUrl)
        mutableScreen.value = next
    }
    fun notify(message: String) { notifications.trySend(message) }
}
