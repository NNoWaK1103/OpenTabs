package org.opentabs.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opentabs.core.*

@Composable
fun SearchScreen(search: SearchSnapshot, screen: ScreenState, vm: OpenTabsViewModel) {
    var selected by remember { mutableStateOf<TabSearchResult?>(null) }
    var sourceInfo by remember { mutableStateOf(false) }
    val supported = remember(search.results) { search.results.flatMap { it.tags }.toSet() }
    val results = remember(search.results, screen.searchFilter, screen.nativeOnly) {
        search.results.filter {
            (screen.searchFilter == null || screen.searchFilter in it.tags) &&
                (!screen.nativeOnly || it.native in setOf(NativeContent.AVAILABLE, NativeContent.LOCAL))
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Find your next tab", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Saved tabs, a small CC0 guitar catalog, and worldwide song metadata.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TitleSearch(screen, vm, Modifier.weight(1f))
                ArtistSearch(screen, vm, Modifier.weight(.7f))
            } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TitleSearch(screen, vm, Modifier.fillMaxWidth())
                ArtistSearch(screen, vm, Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Search online", style = MaterialTheme.typography.labelLarge)
            Switch(checked = screen.onlineSearch, onCheckedChange = vm::onlineSearch,
                modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = "Search online sources" })
            if (search.pending > 0) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = vm::cancelSearch) { Text("Cancel") }
            } else TextButton(onClick = vm::refreshSearch) { Text("Refresh") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { sourceInfo = true }) { Text("Sources") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = screen.nativeOnly, onClick = { vm.nativeOnly(!screen.nativeOnly) }, label = { Text("Native tabs only") })
            ArrangementTag.entries.filter { it in supported }.forEach { tag ->
                FilterChip(selected = screen.searchFilter == tag,
                    onClick = { vm.searchFilter(if (screen.searchFilter == tag) null else tag) }, label = { Text(tag.label) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Website results", style = MaterialTheme.typography.labelMedium)
            WebTabSource.entries.forEach { source ->
                OutlinedButton(onClick = { vm.browse(source) },
                    modifier = Modifier.semantics { contentDescription = "Search ${source.label} website" }) { Text(source.label) }
            }
        }
        val problems = search.reports.filter { it.status in setOf(ProviderStatus.FAILED, ProviderStatus.TIMED_OUT) }
        if (problems.isNotEmpty()) Text(problems.joinToString("\n") { "${it.name}: ${it.message}" },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 4.dp))
        val searchingNewQuery = search.query != SearchQuery(screen.query, screen.artistQuery)
        if (searchingNewQuery) Text("Updating search…", style = MaterialTheme.typography.bodySmall)
        else Text("${results.size} ${if (results.size == 1) "result" else "results"} · ${if (screen.onlineSearch) "Metadata is not tablature" else "Offline library"}",
            Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (results.isEmpty()) Box(Modifier.weight(1f)) {
            EmptyState(if (search.pending > 0) "Searching sources…" else "No matching results",
                "Try a title or artist. Search ‘Ashen Meridian’ for the CC0 guitar demo. Popular-song metadata does not include licensed tablature.")
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { "${it.providerId}:${it.id}" }) { result ->
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(result.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${result.source} · ${result.arrangement}", style = MaterialTheme.typography.bodySmall)
                        Text("Tuning: ${result.tuning ?: "Not supplied"}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(result.native.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                color = if (result.native in setOf(NativeContent.LOCAL, NativeContent.AVAILABLE)) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(enabled = screen.downloading == null,
                                modifier = Modifier.semantics { contentDescription = "Details for ${result.title}, ${result.arrangement}" }, onClick = {
                                if (result.native == NativeContent.LOCAL) vm.openSearchResult(result) else selected = result
                            }) { Text(if (result.native == NativeContent.LOCAL) "Open tab" else "Details") }
                        }
                        if (screen.downloading == "${result.providerId}:${result.id}") LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    selected?.let { result -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(result.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${result.artist} · ${result.arrangement}")
                Text(result.native.label, fontWeight = FontWeight.Bold)
                Text(result.notice.ifBlank { "This source does not provide downloadable native tablature." })
                Text("${result.rights.license}\n${result.rights.attribution}", style = MaterialTheme.typography.bodySmall)
                Text(result.sourceUrl, style = MaterialTheme.typography.bodySmall)
                if (result.native == NativeContent.AVAILABLE) Text("Save & open stores an attributed offline text preview in My Tabs.")
                if (result.native !in setOf(NativeContent.AVAILABLE, NativeContent.LOCAL)) {
                    Text("Browse this song on a tab website", style = MaterialTheme.typography.labelLarge)
                    WebTabSource.entries.forEach { source ->
                        OutlinedButton(onClick = { selected = null; vm.browseResult(result, source) }) { Text("Browse ${source.label}") }
                    }
                }
            }
        }, confirmButton = {
            if (result.native == NativeContent.AVAILABLE && result.rights.save) TextButton(onClick = {
                selected = null; vm.openSearchResult(result)
            }) { Text("Save & open") }
            else TextButton(onClick = { selected = null }) { Text("Close") }
        }, dismissButton = { if (result.native == NativeContent.AVAILABLE) TextButton(onClick = { selected = null }) { Text("Cancel") } }) }
    if (sourceInfo) AlertDialog(onDismissRequest = { sourceInfo = false }, title = { Text("Search sources") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("My Tabs: your saved offline library.")
            Text("TabForge CC0: a small original guitar demo catalog with authored string/fret positions. Six-string arrangements can be saved and displayed natively.")
            Text("MusicBrainz: worldwide title and artist discovery through its documented API. Results contain song metadata, never tablature.")
            Text("Songsterr and Ultimate Guitar: browse each website’s search results and tabs inside OpenTabs. Website content, ads, sign-in, and subscriptions remain controlled by the source. External browser fallback is always available. No tab extraction or private API is used.")
            Text("Online search sends the title and artist to MusicBrainz and retrieves TabForge’s public CC0 score from GitHub. No accounts or background polling.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { sourceInfo = false }) { Text("Close") } })
}

@Composable
private fun TitleSearch(screen: ScreenState, vm: OpenTabsViewModel, modifier: Modifier) {
    OutlinedTextField(screen.query, vm::setQuery, modifier, label = { Text("Title or keywords") }, singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = {
            if (screen.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Clear title search") }
        })
}

@Composable
private fun ArtistSearch(screen: ScreenState, vm: OpenTabsViewModel, modifier: Modifier) {
    OutlinedTextField(screen.artistQuery, vm::setArtistQuery, modifier, label = { Text("Artist (optional)") }, singleLine = true,
        trailingIcon = { if (screen.artistQuery.isNotEmpty()) IconButton(onClick = { vm.setArtistQuery("") }) { Icon(Icons.Default.Close, "Clear artist search") } })
}
