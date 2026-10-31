package org.opentabs.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Metadata stays small; tab bodies live in separate private files and are loaded on demand. */
class LibraryRepository(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LibraryState())
    val state = mutableState.asStateFlow()
    private val index get() = File(directory, "library.json")

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create the library folder." }
            if (index.exists()) {
                val stored = json.decodeFromString<LibraryState>(index.readText())
                check(stored.version == 1) { "This library needs a newer version of OpenTabs." }
                mutableState.value = stored.copy(settings = stored.settings.sanitized())
            } else {
                val songs = Examples.exercises.mapIndexed { number, (title, content) ->
                    val song = Song("example-$number", title, "OpenTabs exercises", imported = false)
                    atomicWrite(body(song.id), content)
                    song
                }
                save(LibraryState(songs = songs, currentSongId = songs.first().id))
            }
        }
    }

    suspend fun importTab(fileName: String, input: InputStream): Song = withContext(Dispatchers.IO) {
        val document = input.use { PlainTextTabDecoder.decode(fileName, it) }
        mutex.withLock {
            val song = Song(UUID.randomUUID().toString(),
                fileName.substringBeforeLast('.').take(160).ifBlank { "Imported tab" }, "Unknown artist")
            atomicWrite(body(song.id), document.lines.joinToString("\n"))
            save(state.value.copy(songs = state.value.songs + song))
            song
        }
    }

    suspend fun saveAuthorized(tab: AuthorizedTab): Song = withContext(Dispatchers.IO) {
        val result = tab.result
        require(result.rights.display && result.rights.save && result.rights.cache) {
            "This source does not permit saving an offline copy."
        }
        // Revalidate native text before indexing, even when the provider already parsed its format.
        val document = PlainTextTabDecoder.decode("authorized.txt", tab.document.lines.joinToString("\n").byteInputStream())
        mutex.withLock {
            val provenance = Provenance(result.source, result.sourceUrl, result.rights.license, result.rights.licenseUrl,
                result.rights.attribution, "${result.providerId}:${result.id}", result.notice, result.arrangement, result.tags)
            val existing = state.value.songs.firstOrNull { it.provenance?.arrangementId == provenance.arrangementId }
            if (existing != null) return@withLock existing
            val song = Song(java.util.UUID.randomUUID().toString(), result.title, result.artist,
                tuning = result.tuning.orEmpty(), provenance = provenance)
            atomicWrite(body(song.id), document.lines.joinToString("\n"))
            save(state.value.copy(songs = state.value.songs + song))
            song
        }
    }

    suspend fun document(id: String): TabDocument = withContext(Dispatchers.IO) {
        require(state.value.songs.any { it.id == id }) { "This tab is no longer in the library." }
        body(id).inputStream().use { PlainTextTabDecoder.decode("tab.txt", it) }
    }

    suspend fun open(id: String) = update { old ->
        require(old.songs.any { it.id == id }) { "This tab is no longer in the library." }
        old.copy(currentSongId = id, songs = old.songs.map { if (it.id == id) it.copy(lastOpened = System.currentTimeMillis()) else it })
    }

    suspend fun toggleFavorite(id: String) = changeSong(id) { it.copy(favorite = !it.favorite) }
    suspend fun togglePractice(id: String) = changeSong(id) { it.copy(practice = !it.practice) }
    suspend fun edit(id: String, title: String, artist: String, tuning: String, album: String) = changeSong(id) {
        require(title.isNotBlank()) { "A title is required." }
        it.copy(title = title.trim().take(160), artist = artist.trim().take(160),
            tuning = tuning.trim().take(80), album = album.trim().take(160))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val song = state.value.songs.firstOrNull { it.id == id } ?: return@withLock
            require(song.imported) { "Built-in exercises cannot be deleted." }
            save(state.value.copy(songs = state.value.songs.filterNot { it.id == id },
                currentSongId = state.value.currentSongId?.takeUnless { it == id }))
            // An interrupted cleanup leaves an unreferenced file, never a broken library entry.
            body(id).delete()
        }
    }

    suspend fun updateSettings(transform: (ViewerSettings) -> ViewerSettings) = update {
        it.copy(settings = transform(it.settings).sanitized())
    }

    private suspend fun changeSong(id: String, transform: (Song) -> Song) = update { old ->
        old.copy(songs = old.songs.map { if (it.id == id) transform(it) else it })
    }

    private suspend fun update(transform: (LibraryState) -> LibraryState) = withContext(Dispatchers.IO) {
        mutex.withLock { save(transform(state.value)) }
    }

    private fun save(next: LibraryState) {
        atomicWrite(index, json.encodeToString(next))
        mutableState.value = next
    }

    private fun body(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid library identifier." }
        return File(directory, "$id.txt")
    }

    private fun atomicWrite(file: File, text: String) {
        val temporary = File(directory, "${file.name}.tmp")
        java.io.FileOutputStream(temporary).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
