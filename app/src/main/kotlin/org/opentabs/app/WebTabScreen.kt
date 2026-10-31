package org.opentabs.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.opentabs.core.*

@Composable
fun WebTabScreen(
    request: WebTabRequest, memory: WebTabMemory, fullScreen: Boolean, keepAwake: Boolean,
    onClose: () -> Unit, onSource: (WebTabSource) -> Unit, onFullScreen: (Boolean) -> Unit,
    onKeepAwake: (Boolean) -> Unit, notify: (String) -> Unit,
    providedController: WebTabController? = null,
) {
    val browser = providedController ?: remember(memory) { WebTabController(memory) }
    val state by browser.state.collectAsStateWithLifecycle()
    val source = WebTabUrls.source(state.url) ?: request.source
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    fun external(url: String = browser.fallbackUrl()) {
        if (!WebTabUrls.safeHttps(url)) { notify("This address cannot be opened safely."); return }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) { notify("No browser is installed. Install or enable a browser in Android Settings.") }
        catch (_: SecurityException) { notify("Android could not open this link in an external browser.") }
    }
    DisposableEffect(browser, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> browser.resume()
            Lifecycle.Event.ON_STOP -> browser.pause()
            else -> Unit
        } }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) browser.resume() else browser.pause()
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.pageSerial, state.loading) {
        if (state.loading) { delay(30_000); browser.markSlow(state.pageSerial) }
    }
    BackHandler {
        if (fullScreen) onFullScreen(false) else if (!browser.goBack()) onClose()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("Native tabs") }
            WebTabSource.entries.forEach { candidate ->
                FilterChip(selected = source == candidate, onClick = { onSource(candidate) }, label = { Text(candidate.label) },
                    modifier = Modifier.semantics { contentDescription = "Browse ${candidate.label}" })
            }
            Text("Website content", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(enabled = state.canBack, onClick = { browser.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back in website") }
            TextButton(enabled = state.canForward, onClick = browser::goForward,
                modifier = Modifier.semantics { contentDescription = "Forward in website" }) { Text("→") }
            IconButton(onClick = { if (state.loading) browser.stop() else browser.refresh() }) {
                Icon(if (state.loading) Icons.Default.Close else Icons.Default.Refresh, if (state.loading) "Stop loading website" else "Refresh website")
            }
            TextButton(enabled = browser.webView != null, onClick = { browser.zoom(false) },
                modifier = Modifier.semantics { contentDescription = "Zoom website out" }) { Text("−") }
            Text("Zoom", style = MaterialTheme.typography.labelMedium)
            TextButton(enabled = browser.webView != null, onClick = { browser.zoom(true) },
                modifier = Modifier.semantics { contentDescription = "Zoom website in" }) { Text("+") }
            TextButton(onClick = { onFullScreen(!fullScreen) }) { Text(if (fullScreen) "Exit full screen" else "Full screen") }
            FilterChip(selected = keepAwake, onClick = { onKeepAwake(!keepAwake) }, label = { Text("Screen awake") })
            TextButton(onClick = { external() }) { Text("Open in browser") }
        }
        Text("${source.label} · ${state.title.ifBlank { "Search & tablature" }}", Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(state.url.ifBlank { request.url }, Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        else HorizontalDivider()
        val banner = state.failure?.takeUnless { it.coversPage }?.message ?: state.notice ?:
            if (state.slow) "Still loading? This website may limit embedded browsers. Wait, stop loading, or open it externally." else null
        if (banner != null) Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(banner, Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { external(state.externalLink ?: browser.fallbackUrl()) }) { Text("Open externally") }
                IconButton(onClick = browser::dismissNotice) { Icon(Icons.Default.Close, "Dismiss website notice") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(state.generation) {
                AndroidView(factory = { browser.createHost(it, request) }, update = { browser.show(request) },
                    onRelease = { browser.releaseHost(it) }, modifier = Modifier.fillMaxSize()
                        .semantics { contentDescription = "Embedded tab website" })
            }
            state.failure?.takeIf { it.coversPage }?.let { failure ->
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Website unavailable", style = MaterialTheme.typography.titleLarge)
                        Text(failure.message, Modifier.padding(vertical = 12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = browser::refresh) { Text("Retry website") }
                            OutlinedButton(onClick = { external() }) { Text("Open in browser") }
                        }
                    }
                }
            }
        }
    }
}
