package org.opentabs.core

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

enum class WebTabSource(val label: String, val home: String, val hosts: Set<String>) {
    SONGSTERR("Songsterr", "https://www.songsterr.com/", setOf("songsterr.com", "www.songsterr.com")),
    ULTIMATE_GUITAR("Ultimate Guitar", "https://www.ultimate-guitar.com/",
        setOf("ultimate-guitar.com", "www.ultimate-guitar.com", "tabs.ultimate-guitar.com", "my.ultimate-guitar.com", "auth.ultimate-guitar.com"));

    fun searchUrl(title: String, artist: String): String {
        val query = listOf(title.trim().take(160), artist.trim().take(160)).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return home
        val encoded = URLEncoder.encode(query, "UTF-8")
        return when (this) {
            SONGSTERR -> "$home?pattern=$encoded"
            ULTIMATE_GUITAR -> "${home}search.php?search_type=title&value=$encoded"
        }
    }
}

data class WebTabRequest(val source: WebTabSource, val title: String, val artist: String, val revision: Long) {
    val url get() = source.searchUrl(title, artist)
    fun switchTo(source: WebTabSource) = copy(source = source, revision = revision + 1)
}

enum class WebNavigation { EMBEDDED, EXTERNAL, BLOCKED }

object WebTabUrls {
    /** Only parsed HTTPS URLs without credentials, ambiguous authorities, or nonstandard ports. */
    fun safeHttps(url: String): Boolean = runCatching {
        if (url.length > 8192 || url.any { it.isISOControl() || it == '\\' }) return false
        val uri = URI(url)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && !uri.host.endsWith('.')
    }.getOrDefault(false)

    fun source(url: String): WebTabSource? {
        if (!safeHttps(url)) return null
        val host = URI(url).host.lowercase(Locale.ROOT)
        return WebTabSource.entries.firstOrNull { host in it.hosts }
    }

    fun navigation(url: String): WebNavigation = when {
        !safeHttps(url) -> WebNavigation.BLOCKED
        source(url) != null -> WebNavigation.EMBEDDED
        else -> WebNavigation.EXTERNAL
    }

    fun fallback(current: String, request: WebTabRequest): String = current.takeIf { source(it) != null } ?: request.url
}

enum class WebFailureKind { NETWORK, TLS, HTTP, RENDERER, UNAVAILABLE, UNSAFE, SAFE_BROWSING }

data class WebFailure(val kind: WebFailureKind, val message: String, val coversPage: Boolean) {
    companion object {
        fun http(code: Int) = WebFailure(WebFailureKind.HTTP,
            if (code in setOf(401, 403, 429)) "The website restricted this request (HTTP $code). Sign-in, a subscription, or an external browser may be required."
            else "The website returned HTTP $code. Its response is shown below.", false)
        fun network() = WebFailure(WebFailureKind.NETWORK, "This page could not load. Check your connection, retry, or open it in your browser.", true)
        fun tls() = WebFailure(WebFailureKind.TLS, "The secure connection failed. Certificate errors are never ignored.", true)
        fun renderer() = WebFailure(WebFailureKind.RENDERER, "The web renderer stopped. Reload this page or open it in your browser.", true)
        fun unavailable() = WebFailure(WebFailureKind.UNAVAILABLE, "Android System WebView is unavailable. Update or enable it in Android Settings, or open the site in your browser.", true)
    }
}
