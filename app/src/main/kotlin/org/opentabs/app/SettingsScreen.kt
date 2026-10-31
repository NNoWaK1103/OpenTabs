package org.opentabs.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opentabs.core.Availability
import org.opentabs.core.ViewerSettings
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(settings: ViewerSettings, vm: OpenTabsViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settle into your sound", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Viewer preferences are saved on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        PreferenceSlider("Tab font size", settings.fontSize, 12..32, "sp") { value -> vm.settings { it.copy(fontSize = value) } }
        PreferenceSlider("Line spacing", settings.lineSpacing, 0..20, "sp") { value -> vm.settings { it.copy(lineSpacing = value) } }
        PreferenceSlider("Auto-scroll speed", settings.scrollSpeed, 5..120, "dp/s") { value -> vm.settings { it.copy(scrollSpeed = value) } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep screen awake", fontWeight = FontWeight.SemiBold)
                Text("While a tab is open in the viewer", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.keepAwake, onCheckedChange = { enabled -> vm.settings { it.copy(keepAwake = enabled) } })
        }
        HorizontalDivider()
        Text("Optional connections", style = MaterialTheme.typography.titleLarge)
        IntegrationCard("Spotify · not configured", (vm.spotify.availability as Availability.Unavailable).reason)
        IntegrationCard("Music identification · unavailable", (vm.recognition.availability as Availability.Unavailable).reason)
        IntegrationCard("Online tab catalogs · planned", "Search currently uses your local library. Songsterr and Ultimate Guitar require authorized access before a provider can be added.")
        HorizontalDivider()
        Text("Voice commands", style = MaterialTheme.typography.titleLarge)
        Text("Tap the microphone, then Start voice command. Availability and offline support depend on the Android speech service installed on your tablet.")
        Text("‘Find tabs for [title] by [artist]’\n‘Open my favorites’\n‘Show my practice list’\n‘Start scrolling’ / ‘Stop scrolling’\n‘Increase tab size’ / ‘Decrease tab size’",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("OpenTabs 0.1.0 · Native. Offline. Yours.", style = MaterialTheme.typography.labelLarge)
        Text("Plain-text .txt and .tab files, up to 512 KiB, 6,000 lines, and 1,000 columns. Guitar Pro, MusicXML, tab audio playback, and synchronized playback are not implemented. The play control starts scrolling only.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreferenceSlider(title: String, value: Int, range: IntRange, units: String, onSave: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Text("$title · ${draft.roundToInt()} $units", fontWeight = FontWeight.SemiBold)
        Slider(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onSave(draft.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
    }
}

@Composable
private fun IntegrationCard(title: String, description: String) {
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
