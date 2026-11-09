package org.opentabs.app

import org.opentabs.core.WebTabRequest
import org.opentabs.core.WebTabSource
import org.opentabs.core.WebsiteBookmarkUrls
import org.opentabs.core.WebTabUrls

/** Provider navigation only. No native library, sample creation, or catalog search is involved. */
data class ProviderTabsState(
    val query: String = "",
    val webTab: WebTabRequest? = null,
    val initialSavedUrl: String? = null,
    val fullScreen: Boolean = false,
    val keepAwake: Boolean = true,
    val revision: Long = 0,
) {
    fun withQuery(value: String) = copy(query = value.filterNot(Char::isISOControl).take(160))

    fun browse(source: WebTabSource): ProviderTabsState {
        val next = revision + 1
        return copy(webTab = WebTabRequest(source, query.trim(), "", next),
            initialSavedUrl = null, revision = next)
    }

    fun openSavedLink(url: String): ProviderTabsState {
        val canonical = WebsiteBookmarkUrls.canonical(url) ?: return this
        val next = revision + 1
        return copy(webTab = WebTabRequest(requireNotNull(WebTabUrls.source(canonical)), query.trim(), "", next),
            initialSavedUrl = canonical, fullScreen = false, revision = next)
    }

    fun closeBrowser() = copy(webTab = null, initialSavedUrl = null, fullScreen = false)
    fun fullScreen(enabled: Boolean) = copy(fullScreen = enabled && webTab != null)
}
