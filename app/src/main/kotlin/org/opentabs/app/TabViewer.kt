package org.opentabs.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.opentabs.core.LibraryState
import org.opentabs.core.TabDocument

@Composable
fun TabViewer(library: LibraryState, screen: ScreenState, document: DocumentState, vm: OpenTabsViewModel) {
    val song = library.songs.firstOrNull { it.id == library.currentSongId }
    if (song == null) {
        EmptyState("Your next session starts here", "Open a tab from My Tabs, or import a plain-text .txt or .tab file.") {
            Button(onClick = { vm.navigate(Destination.LIBRARY) }) { Text("Explore My Tabs") }
        }
        return
    }
    var editing by remember(song.id) { mutableStateOf(false) }
    var sourceDetails by remember(song.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = if (screen.fullScreen) 8.dp else 16.dp)) {
        if (screen.fullScreen) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(song.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                TextButton(onClick = { vm.fullScreen(false) }) { Text("Exit full screen") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NOW PLAYING · LOCAL TAB", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                    Text(song.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(listOf(song.artist, song.album, song.provenance?.arrangement.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                IconToggleButton(checked = song.favorite, onCheckedChange = { vm.favorite(song) }) {
                    Icon(Icons.Default.Favorite, if (song.favorite) "Remove from favorites" else "Add to favorites")
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = { editing = true }, label = { Text("Tuning  ${song.tuning}") })
                FilterChip(selected = song.practice, onClick = { vm.practice(song) }, label = { Text("Practice list") })
                TextButton(onClick = { editing = true }) { Text("Edit details") }
                TextButton(onClick = { vm.fullScreen(true) }) { Text("Full screen") }
                if (song.provenance != null) TextButton(onClick = { sourceDetails = true }) { Text("Source & license") }
            }
            song.provenance?.let { source ->
                Text("${source.provider} · ${source.license} · ${source.attribution}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        val tab = document.tab
        if (document.songId != song.id || (tab == null && document.error == null)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (document.error != null) {
            Box(Modifier.weight(1f)) { EmptyState("Cannot display this tab", document.error) }
        } else if (tab != null) key(song.id) {
            ScrollingTab(tab, library, screen, vm, Modifier.weight(1f))
        }
    }
    if (editing) EditSongDialog(song, onDismiss = { editing = false }, onSave = { title, artist, tuning, album ->
        vm.edit(song, title, artist, tuning, album)
        editing = false
    })
    if (sourceDetails) song.provenance?.let { source ->
        AlertDialog(onDismissRequest = { sourceDetails = false }, title = { Text("Source & license") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${source.provider} · ${source.arrangement}")
                Text(source.attribution)
                Text("${source.license}\n${source.licenseUrl}", style = MaterialTheme.typography.bodySmall)
                Text(source.sourceUrl, style = MaterialTheme.typography.bodySmall)
                Text(source.notice, style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { sourceDetails = false }) { Text("Close") } })
    }
}

@Composable
private fun ScrollingTab(tab: TabDocument, library: LibraryState, screen: ScreenState,
    vm: OpenTabsViewModel, modifier: Modifier) {
    val vertical = rememberLazyListState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = library.settings
    val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = settings.fontSize.sp,
        lineHeight = (settings.fontSize * 1.4f + settings.lineSpacing).sp)
    val measurer = rememberTextMeasurer()
    val textWidth = remember(tab.columns, textStyle, density) {
        with(density) {
            measurer.measure("M".repeat(tab.columns), style = textStyle, softWrap = false,
                constraints = Constraints()).size.width.toDp() + 48.dp
        }
    }
    LaunchedEffect(screen.scrolling, settings.scrollSpeed, tab) {
        if (screen.scrolling) {
            var previous = withFrameNanos { it }
            val pixelsPerSecond = with(density) { settings.scrollSpeed.dp.toPx() }
            while (true) {
                val now = withFrameNanos { it }
                val delta = ((now - previous) / 1_000_000_000f).coerceIn(0f, .1f)
                previous = now
                if (!vertical.canScrollForward) {
                    vm.scroll(false)
                    vm.notify("End of tab. Use Back to top to play it again.")
                    break
                }
                if (!vertical.isScrollInProgress) vertical.scrollBy(pixelsPerSecond * delta)
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(
            MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))) {
            val width = maxOf(maxWidth, textWidth)
            Box(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                LazyColumn(state = vertical, modifier = Modifier.width(width).fillMaxHeight()
                    .semantics { contentDescription = "Six-string guitar tablature. Swipe vertically or horizontally to scroll." },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)) {
                    itemsIndexed(tab.lines) { _, line ->
                        Text(line.ifEmpty { " " }, style = textStyle, softWrap = false, maxLines = 1,
                            color = if (line.contains('|')) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { vm.scroll(!screen.scrolling) }) {
                if (screen.scrolling) Text("Ⅱ", Modifier.padding(end = 8.dp), fontWeight = FontWeight.Bold)
                else Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                Text(if (screen.scrolling) "Pause scroll" else "Auto-scroll")
            }
            IconButton(onClick = { vm.scroll(false); scope.launch { vertical.scrollToItem(0); horizontal.scrollTo(0) } }) {
                Icon(Icons.Default.Refresh, "Back to top")
            }
            TextButton(onClick = { vm.settings { it.copy(fontSize = it.fontSize - 2) } }, enabled = settings.fontSize > 12) {
                Text("A−", Modifier.semantics { contentDescription = "Decrease tab size" })
            }
            Text("${settings.fontSize}", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { vm.settings { it.copy(fontSize = it.fontSize + 2) } }, enabled = settings.fontSize < 32) {
                Text("A+", Modifier.semantics { contentDescription = "Increase tab size" })
            }
            Text("Speed", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { vm.settings { it.copy(scrollSpeed = it.scrollSpeed - 5) } }, enabled = settings.scrollSpeed > 5) {
                Text("−", Modifier.semantics { contentDescription = "Decrease scrolling speed" })
            }
            Text("${settings.scrollSpeed} dp/s", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { vm.settings { it.copy(scrollSpeed = it.scrollSpeed + 5) } }, enabled = settings.scrollSpeed < 120) {
                Text("+", Modifier.semantics { contentDescription = "Increase scrolling speed" })
            }
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        action()
    }
}
