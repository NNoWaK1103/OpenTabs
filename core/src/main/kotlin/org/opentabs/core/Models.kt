package org.opentabs.core

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val tuning: String = "E A D G B e",
    val album: String = "",
    val favorite: Boolean = false,
    val practice: Boolean = false,
    val lastOpened: Long = 0,
    val imported: Boolean = true,
)

@Serializable
data class ViewerSettings(
    val fontSize: Int = 18,
    val lineSpacing: Int = 6,
    val scrollSpeed: Int = 28,
    val keepAwake: Boolean = true,
) {
    fun sanitized() = copy(
        fontSize = fontSize.coerceIn(12, 32),
        lineSpacing = lineSpacing.coerceIn(0, 20),
        scrollSpeed = scrollSpeed.coerceIn(5, 120),
    )
}

@Serializable
data class LibraryState(
    val version: Int = 1,
    val songs: List<Song> = emptyList(),
    val settings: ViewerSettings = ViewerSettings(),
    val currentSongId: String? = null,
)

object LocalSearch {
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun search(songs: List<Song>, query: String): List<Song> {
        val terms = normalize(query).split(' ').filter { it.isNotBlank() }
        return songs.filter { song ->
            val text = normalize("${song.title} ${song.artist} ${song.album} ${song.tuning}")
            terms.all { it in text }
        }.sortedWith(compareByDescending<Song> { it.lastOpened }.thenBy { it.title.lowercase(Locale.ROOT) })
    }

    fun matching(songs: List<Song>, track: TrackInfo): List<Song> = songs.filter {
        normalize(it.title) == normalize(track.title) && normalize(it.artist) == normalize(track.artist)
    }
}
