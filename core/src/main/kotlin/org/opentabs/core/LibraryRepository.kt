package org.opentabs.core

import java.io.File
import java.io.InputStream
import java.io.IOException
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
                // Upgrade only unchanged bundled bodies. Legacy imports never acquire guessed timing.
                stored.songs.filter { !it.imported }.forEach { song ->
                    try {
                        val number = song.id.removePrefix("example-").toIntOrNull()
                        if (number != null && song.id == "example-$number" && !playback(song.id).exists() &&
                            Examples.exercises.getOrNull(number)?.second == body(song.id).takeIf { it.exists() }?.readText()) {
                            ExampleScores.forIndex(number)?.let { atomicWrite(playback(song.id), json.encodeToString(it)) }
                        }
                    } catch (_: IOException) {
                        // Optional audio migration must not block an existing text library on full/read-only storage.
                    } catch (_: SecurityException) {
                        // Keep existing metadata and text available when this optional write is not permitted.
                    }
                }
            } else {
                val songs = Examples.exercises.mapIndexed { number, (title, content) ->
                    val song = Song("example-$number", title, "OpenTabs exercises", imported = false)
                    atomicWrite(body(song.id), content)
                    ExampleScores.forIndex(number)?.let { atomicWrite(playback(song.id), json.encodeToString(it)) }
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

    /** GP7 text remains importable even when its authored timing is not supported. */
    suspend fun importGuitarPro(score: GuitarProScore, trackId: String): Song = withContext(Dispatchers.IO) {
        val track = score.tracks.firstOrNull { it.id == trackId } ?: error("Unknown arrangement.")
        val document = score.document(trackId)
        val practice = runCatching { score.practiceScore(trackId) }
        mutex.withLock {
            val song = Song(UUID.randomUUID().toString(), "${score.title} · ${track.name}".take(160), score.artist,
                tuning = tuningLabel(track.tuning), album = "GP7 written-order practice",
                playbackUnavailableReason = practice.exceptionOrNull()?.message?.take(400))
            atomicWrite(body(song.id), document.lines.joinToString("\n"))
            practice.getOrNull()?.let { atomicWrite(playback(song.id), json.encodeToString(it)) }
            save(state.value.copy(songs = state.value.songs + song))
            song
        }
    }

    /** A missing sidecar means no authored timing. Corrupt sidecars never prevent opening the text. */
    suspend fun practiceScore(id: String): PracticeScore? = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(state.value.songs.any { it.id == id }) { "This tab is no longer in the library." }
            val file = playback(id)
            if (!file.exists()) return@withLock null
            require(file.length() <= 8 * 1024 * 1024) { "Saved playback score is too large." }
            val bytes = file.inputStream().use { readBounded(it, 8 * 1024 * 1024) }
            json.decodeFromString<PracticeScore>(bytes.toString(Charsets.UTF_8))
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
            if (existing != null) {
                // Re-saving a reviewed arrangement upgrades an older text-only saved copy.
                if (tab.practiceScore != null && !playback(existing.id).exists() &&
                    body(existing.id).readText() == document.lines.joinToString("\n")) {
                    atomicWrite(playback(existing.id), json.encodeToString(tab.practiceScore.copy()))
                    if (existing.playbackUnavailableReason != null) {
                        val updated = existing.copy(playbackUnavailableReason = null)
                        save(state.value.copy(songs = state.value.songs.map { if (it.id == existing.id) updated else it }))
                        return@withLock updated
                    }
                }
                return@withLock existing
            }
            val song = Song(java.util.UUID.randomUUID().toString(), result.title, result.artist,
                tuning = result.tuning.orEmpty(), provenance = provenance,
                playbackUnavailableReason = tab.playbackUnavailableReason?.take(400))
            atomicWrite(body(song.id), document.lines.joinToString("\n"))
            tab.practiceScore?.let { atomicWrite(playback(song.id), json.encodeToString(it.copy())) }
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
            playback(id).delete()
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

    private fun playback(id: String): File {
        body(id) // Reuse the same identifier validation for sidecars.
        return File(directory, "$id.practice.json")
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
