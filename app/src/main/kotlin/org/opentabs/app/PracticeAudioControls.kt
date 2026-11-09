package org.opentabs.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.opentabs.core.tuningLabel

/** Keep the real-time position subscription out of the app root and the six-line tab viewer. */
@Composable
internal fun PracticeAudioPanel(vm: OpenTabsViewModel) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    PracticeAudioControls(playback, vm::toggleAudio, vm::stopAudio, vm::seekAudio,
        vm::audioSpeed, vm::loopAudio, vm::retryAudio)
}

@Composable
internal fun PracticeAudioControls(
    playback: PracticePlaybackState,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Double) -> Unit,
    onLoop: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    var details by remember(playback.songId) { mutableStateOf(false) }
    var speedMenu by remember(playback.songId) { mutableStateOf(false) }
    var seekPosition by remember(playback.songId, playback.speed) { mutableStateOf<Float?>(null) }
    Surface(Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (playback.available) "OFFLINE MIDI · WRITTEN ORDER" else "Text tab · no timed audio",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (playback.available) Text("Audio: ${tuningLabel(playback.tuning)} · Capo ${playback.capo}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (playback.available || playback.error != null) {
                    TextButton(onClick = { details = true }) { Text("Playback details") }
                }
            }
            if (playback.available) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onToggle, enabled = playback.ready) {
                        if (playback.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else if (playback.playing) Text("Ⅱ", fontWeight = FontWeight.Bold)
                        else Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (playback.loading) "Preparing audio…" else if (playback.playing) "Pause audio" else "Play audio")
                    }
                    OutlinedButton(onClick = onStop, enabled = playback.ready || playback.loading) { Text("Stop audio") }
                    Box {
                        OutlinedButton(onClick = { speedMenu = true }, enabled = playback.ready,
                            modifier = Modifier.semantics { contentDescription = "Audio speed" }) {
                            Text("${(playback.speed * 100).toInt()}%")
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            listOf(0.5, 0.75, 1.0, 1.25, 1.5).forEach { speed ->
                                DropdownMenuItem(text = { Text("${(speed * 100).toInt()}% speed") },
                                    onClick = { speedMenu = false; onSpeed(speed) })
                            }
                        }
                    }
                    FilterChip(selected = playback.loop, onClick = { onLoop(!playback.loop) },
                        label = { Text("Loop score") }, enabled = playback.ready,
                        modifier = Modifier.semantics { contentDescription = "Loop whole score" })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(formatPracticeTime(seekPosition?.toLong() ?: playback.positionMillis),
                        style = MaterialTheme.typography.labelMedium)
                    Slider(value = seekPosition ?: playback.positionMillis.toFloat(),
                        onValueChange = { seekPosition = it },
                        onValueChangeFinished = { seekPosition?.let { onSeek(it.toLong()) }; seekPosition = null },
                        valueRange = 0f..playback.durationMillis.coerceAtLeast(1).toFloat(), enabled = playback.ready,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Audio position in score time" })
                    Text(formatPracticeTime(playback.durationMillis), style = MaterialTheme.typography.labelMedium)
                }
            } else if (playback.error == null) {
                Text("Auto-scroll moves the text only. Import a supported GP7 score for offline MIDI audio.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
            }
            playback.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (playback.available && !playback.loading) TextButton(onClick = onRetry) { Text("Retry audio") }
                }
            }
            playback.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp)) }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false },
        title = { Text("Offline practice audio") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (playback.available) {
                Text("Your device synthesizes the saved notes as MIDI audio. No internet connection is needed.")
                Text("Audio uses the authored tuning ${tuningLabel(playback.tuning)}, capo ${playback.capo}. Edit details changes library labels only; it does not transpose the saved notes.")
                Text("Measures play once in written order. Repeats and expressive guitar techniques are not performed.")
                Text("The timeline shows score time at 100% speed. Changing speed keeps pitch unchanged. Loop score repeats the whole arrangement.")
                Text("Text spacing and auto-scroll are not synchronized with the audio. Audio pauses when you leave the viewer, switch apps, unplug headphones, or another app takes audio focus.")
                playback.warnings.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else Text("This copy has no supported authored timing. Its six-string text tab and auto-scroll remain available.")
            playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
}

internal fun formatPracticeTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
