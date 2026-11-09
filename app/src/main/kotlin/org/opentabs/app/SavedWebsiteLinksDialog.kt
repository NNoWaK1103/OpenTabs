package org.opentabs.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.opentabs.core.WebsiteBookmark
import org.opentabs.core.WebsiteBookmarkRepository

/** The same online bookmark picker is reachable from Search and the provider viewer. */
@Composable
fun SavedWebsiteLinksDialog(repository: WebsiteBookmarkRepository, onOpen: (String) -> Unit,
    onDismiss: () -> Unit, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var links by remember { mutableStateOf<List<WebsiteBookmark>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var readAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, readAttempt) {
        busy = true
        try { links = repository.list(); error = null }
        catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            error = "Saved links could not be read. Existing links have been preserved."
        } finally { busy = false }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Saved website links") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Internet required. These are links to the original websites, not offline tab copies.",
                    style = MaterialTheme.typography.bodySmall)
                when {
                    error != null -> {
                        Text(error.orEmpty())
                        TextButton(onClick = { readAttempt++ }, enabled = !busy) { Text("Try again") }
                    }
                    busy && links.isEmpty() -> Text("Loading links…")
                    links.isEmpty() -> Text("Open a tab on Songsterr or Ultimate Guitar, then tap Save link.")
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(links, key = { it.url }) { link ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(modifier = Modifier.weight(1f), onClick = { onOpen(link.url) }) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(link.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${link.source.label} · Internet required", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                IconButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try { links = repository.remove(link.url) }
                                        catch (exception: Exception) {
                                            if (exception is CancellationException) throw exception
                                            notify("Could not remove the saved link.")
                                        } finally { busy = false }
                                    }
                                }) { Icon(Icons.Default.Close, "Remove saved link ${link.title}") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
