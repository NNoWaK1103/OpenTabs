package org.opentabs.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A bookmark is only an address. It never contains downloaded tablature or account URLs. */
@Serializable
data class WebsiteBookmark(val url: String, val title: String, val savedAt: Long) {
    val source: WebTabSource get() = requireNotNull(WebTabUrls.source(url))
}

object WebsiteBookmarkUrls {
    /** Retain only stable tab-page paths. Discard fragments and query strings, including tokens. */
    fun canonical(url: String): String? {
        val source = WebTabUrls.source(url) ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.length > 1024 || path.contains('%') || path.contains("..")) return null
        return when (source) {
            WebTabSource.SONGSTERR -> if (path.matches(Regex("/a/wsa/[A-Za-z0-9_-]+-tab-s[0-9]+(?:/r[0-9]+)?")))
                "https://www.songsterr.com$path" else null
            WebTabSource.ULTIMATE_GUITAR -> if (uri.host.equals("tabs.ultimate-guitar.com", true) &&
                path.matches(Regex("/tab/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+-[0-9]+")))
                "https://tabs.ultimate-guitar.com$path" else null
        }
    }
}

/** Small, atomic, app-private online-link store, deliberately separate from the offline tab library. */
class WebsiteBookmarkRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex get() = storeMutex

    suspend fun list(): List<WebsiteBookmark> = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    suspend fun save(url: String, title: String): List<WebsiteBookmark> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val canonical = requireNotNull(WebsiteBookmarkUrls.canonical(url)) { "Open a Songsterr or Ultimate Guitar tab page before saving its link." }
            val current = read()
            require(current.size < MAX_LINKS || current.any { it.url == canonical }) { "You have saved $MAX_LINKS links. Remove an old link first." }
            val label = title.filterNot { it.isISOControl() }.trim().take(180).ifBlank { canonical.substringAfterLast('/') }
            val next = listOf(WebsiteBookmark(canonical, label, System.currentTimeMillis())) + current.filterNot { it.url == canonical }
            write(next)
            next
        }
    }

    suspend fun remove(url: String): List<WebsiteBookmark> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = read().filterNot { it.url == url }
            write(next)
            next
        }
    }

    private fun read(): List<WebsiteBookmark> {
        if (!file.exists()) return emptyList()
        require(file.length() <= MAX_BYTES) { "Saved website links are too large. The existing file has been preserved." }
        val saved = json.decodeFromString<List<WebsiteBookmark>>(file.readText())
        require(saved.size <= MAX_LINKS && saved.all {
            WebsiteBookmarkUrls.canonical(it.url) == it.url && it.title.length <= 180 && it.title.none(Char::isISOControl)
        } && saved.map { it.url }.distinct().size == saved.size) { "Saved website links could not be read. The existing file has been preserved." }
        return saved
    }

    private fun write(links: List<WebsiteBookmark>) {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } != false) { "Cannot create the saved-link folder." }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { output -> output.write(json.encodeToString(links).toByteArray()); output.fd.sync() }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        // A cancelled screen can finish an IO write after its replacement opens the same file.
        // Serialize across repository instances, not merely within one composition.
        private val storeMutex = Mutex()
        const val MAX_LINKS = 100
        private const val MAX_BYTES = 256 * 1024L
    }
}
