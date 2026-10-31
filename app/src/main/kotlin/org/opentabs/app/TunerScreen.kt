package org.opentabs.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.opentabs.core.*

@Composable
fun TunerScreen(memory: TunerMemory, onClose: () -> Unit, notify: (String) -> Unit,
    controllerFactory: ((TunerMemory, () -> Boolean, () -> Unit) -> TunerController)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var explainMicrophone by remember { mutableStateOf(false) }
    val tuner = remember(memory, context) {
        val permitted = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
        val ask = { explainMicrophone = true }
        controllerFactory?.invoke(memory, permitted, ask) ?: TunerController(memory, permitted, ask)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), tuner::androidPermissionResult)
    val state by tuner.state.collectAsStateWithLifecycle()
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(tuner, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> { tuner.resume(); foreground = true }
            Lifecycle.Event.ON_STOP -> {
                if (explainMicrophone) tuner.androidPermissionResult(false)
                foreground = false; explainMicrophone = false; tuner.pause()
            }
            else -> Unit
        } }
        lifecycle.lifecycle.addObserver(observer)
        if (foreground) tuner.resume() else tuner.pause()
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            if (explainMicrophone) tuner.androidPermissionResult(false)
            tuner.pause()
        }
    }
    LaunchedEffect(state.microphone) {
        if (explainMicrophone && state.microphone != TunerMicrophoneStatus.WAITING) {
            explainMicrophone = false
            tuner.androidPermissionResult(false) // Explanation was canceled before an Android dialog was launched.
        }
    }
    LaunchedEffect(state.pageSerial, state.loading) {
        if (state.loading) { delay(30_000); tuner.markSlow(state.pageSerial) }
    }
    fun external(url: String) {
        if (!WebTabUrls.safeHttps(url)) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { notify("No external browser is installed.") }
        catch (_: SecurityException) { notify("Android could not open this address.") }
    }
    BackHandler { if (!tuner.goBack()) onClose() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClose) { Text("Back to tab") }
            Text("Guitar Tuner", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { explainMicrophone = false; tuner.retry() }) { Text("Reload tuner") }
        }
        Text(state.url, Modifier.padding(horizontal = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall)
        Text(when (state.microphone) {
            TunerMicrophoneStatus.WAITING -> "guitar-tuner.org is requesting microphone access."
            TunerMicrophoneStatus.ALLOWED -> "Microphone allowed for guitar-tuner.org while Tuner is open."
            TunerMicrophoneStatus.DENIED -> "Microphone denied. Enable it in Android Settings → Apps → OpenTabs → Permissions, then reload and tap the website's microphone switch."
            TunerMicrophoneStatus.BLOCKED -> "Microphone is limited to guitar-tuner.org. Other permission requests are blocked."
            TunerMicrophoneStatus.IDLE -> "Choose a guitar tuner, then tap its microphone switch. Audio access stops when you leave this page."
        }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        else HorizontalDivider()
        val notice = state.failure?.takeUnless { it.coversPage }?.message ?: state.notice
        if (notice != null) Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { external(state.externalLink ?: tuner.fallbackUrl()) }) { Text("Open in browser") }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (foreground) key(state.generation) {
                AndroidView(factory = { tuner.resume(); tuner.createHost(it) }, onRelease = tuner::releaseHost,
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Embedded guitar tuner" })
            }
            state.failure?.takeIf { it.coversPage }?.let { failure ->
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Tuner unavailable", style = MaterialTheme.typography.titleLarge)
                        Text(failure.message, Modifier.padding(vertical = 12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = tuner::retry) { Text("Retry tuner") }
                            OutlinedButton(onClick = { external(tuner.fallbackUrl()) }) { Text("Open in browser") }
                        }
                    }
                }
            }
        }
    }
    if (explainMicrophone && state.microphone == TunerMicrophoneStatus.WAITING) AlertDialog(
        onDismissRequest = { explainMicrophone = false; tuner.androidPermissionResult(false) },
        title = { Text("Allow tuner microphone?") },
        text = { Text("guitar-tuner.org needs your microphone to hear guitar strings. Android will ask permission. Access stops when you leave Tuner or background OpenTabs.") },
        confirmButton = { TextButton(onClick = {
            explainMicrophone = false
            runCatching { permission.launch(Manifest.permission.RECORD_AUDIO) }.onFailure {
                tuner.androidPermissionResult(false)
                notify("Android could not request microphone access. Check OpenTabs permissions in Android Settings.")
            }
        }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { explainMicrophone = false; tuner.androidPermissionResult(false) }) { Text("Not now") } })
}
