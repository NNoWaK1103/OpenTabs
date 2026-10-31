package org.opentabs.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.opentabs.core.WebTabSource

/** Uses the existing provider search routes and WebView; there is no song-metadata lookup. */
@Composable
fun SearchScreen(
    screen: ScreenState, onTitle: (String) -> Unit, onArtist: (String) -> Unit,
    onSource: (WebTabSource) -> Unit, onSearch: () -> Unit, onLibrary: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val hasQuery = screen.query.isNotBlank() || screen.artistQuery.isNotBlank()
    fun submit() {
        if (!hasQuery) return
        keyboard?.hide()
        focus.clearFocus()
        onSearch()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Find guitar tabs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Search Songsterr or Ultimate Guitar, then select a tab in the embedded viewer.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            @Composable fun title(modifier: Modifier) = OutlinedTextField(
                screen.query, onTitle, modifier, label = { Text("Song title") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (screen.query.isNotEmpty()) IconButton(onClick = { onTitle("") }) {
                    Icon(Icons.Default.Close, "Clear title search")
                } })
            @Composable fun artist(modifier: Modifier) = OutlinedTextField(
                screen.artistQuery, onArtist, modifier, label = { Text("Artist") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailingIcon = { if (screen.artistQuery.isNotEmpty()) IconButton(onClick = { onArtist("") }) {
                    Icon(Icons.Default.Close, "Clear artist search")
                } })
            if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                title(Modifier.weight(1f)); artist(Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                title(Modifier.fillMaxWidth()); artist(Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WebTabSource.entries.forEach { source ->
                FilterChip(selected = screen.tabSource == source, onClick = { onSource(source) },
                    label = { Text(source.label) }, modifier = Modifier.semantics {
                        contentDescription = "Tab source ${source.label}"
                    })
            }
        }
        Button(onClick = { submit() }, enabled = hasQuery, modifier = Modifier.fillMaxWidth()) {
            Text("Find tabs on ${screen.tabSource.label}")
        }
        Text("Enter a song title, an artist, or both. Results open on the selected website inside OpenTabs.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("If a website cannot open here, use Open in browser. Website sign-in, ads, and paid features remain controlled by the source.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        OutlinedButton(onClick = onLibrary) { Text("My Tabs · saved six-line tablature") }
    }
}
