package org.opentabs.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.opentabs.core.WebTabSource

@Composable
fun ProviderSearchScreen(query: String, onQuery: (String) -> Unit,
    onSource: (WebTabSource) -> Unit, onSavedLinks: () -> Unit) {
    val focus = LocalFocusManager.current
    var savingHelp by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("OpenTabs", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            TextButton(onClick = onSavedLinks) {
                Icon(Icons.Default.Star, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Saved links")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 680.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                horizontal = if (wide) 40.dp else 24.dp, vertical = if (wide) 40.dp else 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 920.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("TWO SOURCES. YOUR MUSIC.", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text("Find your next song.", style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold)
                        Text("Search Songsterr or Ultimate Guitar, then open a tab right here.",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(value = query, onValueChange = onQuery,
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Song or artist") },
                        placeholder = { Text("What do you want to play?") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Default.Close, "Clear search")
                        } },
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (query.isBlank()) "Choose a website" else "Choose where to search",
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val openSource: (WebTabSource) -> Unit = { focus.clearFocus(); onSource(it) }
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            WebTabSource.entries.forEach { source ->
                                ProviderCard(source, query.isNotBlank(), { openSource(source) }, Modifier.weight(1f))
                            }
                        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            WebTabSource.entries.forEach { source ->
                                ProviderCard(source, query.isNotBlank(), { openSource(source) }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(Icons.Default.Star, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Saving your tabs", style = MaterialTheme.typography.titleSmall)
                                Text("Save a link for later, or save a file when the website offers a download.",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { savingHelp = true }, contentPadding = PaddingValues(0.dp)) {
                                    Text("How saving works")
                                }
                            }
                        }
                    }
                    Text("Original websites · Internet required\nEach provider controls its content, sign-in, ads, and paid features.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (savingHelp) AlertDialog(onDismissRequest = { savingHelp = false },
        title = { Text("Saving your tabs") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saved links", style = MaterialTheme.typography.titleSmall)
                Text("Open a tab, then tap Save link. Find it again in Saved links. Internet required; a link does not download the tab or its audio.")
                HorizontalDivider()
                Text("Files offered by the website", style = MaterialTheme.typography.titleSmall)
                Text("Use the website’s own Download or Export button. If it offers a supported file, OpenTabs asks where to save it. The website may require sign-in or a paid plan. Saved files do not include the online player.")
                Text("Offline access in the provider’s own app is separate and depends on its features and plan.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { savingHelp = false }) { Text("Done") } })

}

@Composable
private fun ProviderCard(source: WebTabSource, searching: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = if (source == WebTabSource.SONGSTERR) Color(0xFF8AD7FF) else Color(0xFFFFD06A)
    val action = if (searching) "Search" else "Browse"
    OutlinedCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = .45f)),
        colors = CardDefaults.outlinedCardColors(containerColor = accent.copy(alpha = .06f))) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = .12f)) {
                    Text(if (source == WebTabSource.SONGSTERR) "S" else "UG", Modifier.padding(12.dp),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = accent)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(source.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (source == WebTabSource.SONGSTERR) "songsterr.com" else "ultimate-guitar.com",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$action ${source.label}", color = accent, style = MaterialTheme.typography.labelLarge)
        }
    }
}
