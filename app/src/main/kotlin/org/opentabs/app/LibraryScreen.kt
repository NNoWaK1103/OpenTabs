package org.opentabs.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opentabs.core.*

@Composable
fun LibraryScreen(library: LibraryState, screen: ScreenState, vm: OpenTabsViewModel) {
    var deleting by remember { mutableStateOf<Song?>(null) }
    var editing by remember { mutableStateOf<Song?>(null) }
    val songs = remember(library.songs, screen.query, screen.destination, screen.filter) {
        val results = LocalSearch.search(library.songs, screen.query)
        when {
            screen.destination == Destination.PRACTICE -> results.filter { it.practice }
            screen.destination == Destination.LIBRARY && screen.filter == LibraryFilter.FAVORITES -> results.filter { it.favorite }
            screen.destination == Destination.LIBRARY && screen.filter == LibraryFilter.RECENT -> results.filter { it.lastOpened > 0 }
            else -> results
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(when (screen.destination) {
            Destination.SEARCH -> "Find your next tab"
            Destination.PRACTICE -> "Make time to play"
            else -> "Your guitar library"
        }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(when (screen.destination) {
            Destination.SEARCH -> "Search saved titles, artists, albums, and tuning."
            Destination.PRACTICE -> "Your personal practice list. Pick a tab and settle in."
            else -> "Saved on this device. Ready whenever you are."
        }, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        OutlinedTextField(value = screen.query, onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Search local tabs") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (screen.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Clear search") }
            })
        if (screen.destination == Destination.LIBRARY) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryFilter.entries.forEach { filter ->
                FilterChip(selected = screen.filter == filter, onClick = { vm.filter(filter) }, label = { Text(filter.label) })
            }
        }
        if (screen.destination == Destination.SEARCH) Text(
            "Offline library only · Online catalogs are not supported yet.",
            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${songs.size} ${if (songs.size == 1) "tab" else "tabs"}", Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (songs.isEmpty()) Box(Modifier.weight(1f)) {
            EmptyState(if (screen.query.isNotBlank()) "No matching tabs" else "Nothing here yet",
                if (screen.query.isNotBlank()) "Try part of a title or artist name, or import a matching tab."
                else if (screen.destination == Destination.PRACTICE) "Open a tab and select Practice list to add it here."
                else "Import a tab or add a favorite from Now Playing.")
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(songs, key = { it.id }) { song ->
                SongRow(song, onOpen = { vm.open(song) }, onFavorite = { vm.favorite(song) },
                    onPractice = { vm.practice(song) }, onDelete = { deleting = song }, onEdit = { editing = song })
            }
        }
    }
    deleting?.let { song -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text("Delete ${song.title}?") }, text = { Text("This removes the imported copy, its favorite, and its practice entry from OpenTabs. Your original file is preserved.") },
        confirmButton = { TextButton(onClick = { vm.delete(song); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    editing?.let { song -> EditSongDialog(song, { editing = null }) { title, artist, tuning, album ->
        vm.edit(song, title, artist, tuning, album); editing = null
    } }
}

@Composable
private fun SongRow(song: Song, onOpen: () -> Unit, onFavorite: () -> Unit, onPractice: () -> Unit,
    onDelete: () -> Unit, onEdit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text("${song.tuning} · ${song.provenance?.arrangement ?: if (song.imported) "Imported text" else "Original exercise"}${if (song.practice) " · Practice" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconToggleButton(checked = song.favorite, onCheckedChange = { onFavorite() }) {
                Icon(Icons.Default.Favorite, if (song.favorite) "Remove ${song.title} from favorites" else "Favorite ${song.title}")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions for ${song.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open tab") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Edit details") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(if (song.practice) "Remove from practice" else "Add to practice") },
                        onClick = { menu = false; onPractice() })
                    if (song.imported) DropdownMenuItem(text = { Text("Delete imported tab") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun EditSongDialog(song: Song, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var title by rememberSaveable(song.id) { mutableStateOf(song.title) }
    var artist by rememberSaveable(song.id) { mutableStateOf(song.artist) }
    var tuning by rememberSaveable(song.id) { mutableStateOf(song.tuning) }
    var album by rememberSaveable(song.id) { mutableStateOf(song.album) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tab details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(160) }, label = { Text("Title") }, singleLine = true, isError = title.isBlank())
                OutlinedTextField(artist, { artist = it.take(160) }, label = { Text("Artist") }, singleLine = true)
                OutlinedTextField(tuning, { tuning = it.take(80) }, label = { Text("Tuning") }, singleLine = true)
                OutlinedTextField(album, { album = it.take(160) }, label = { Text("Album (optional)") }, singleLine = true)
            }
        }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(title, artist, tuning, album) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
