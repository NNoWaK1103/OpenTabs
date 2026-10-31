package org.opentabs.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.opentabs.core.Availability

@Composable
fun OpenTabsApp(vm: OpenTabsViewModel = viewModel()) = OpenTabsTheme {
    val library by vm.library.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val document by vm.document.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val guitarProImport by vm.guitarProImport.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current
    var microphoneDialog by remember { mutableStateOf(false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTab(uri)
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !words.isNullOrBlank()) vm.voice(words)
        else if (result.resultCode != Activity.RESULT_CANCELED) vm.notify("No speech recognized. Please try again or use the manual controls.")
    }
    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Try ‘open my favorites’ or ‘start scrolling’")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            speech.launch(intent)
        } catch (_: ActivityNotFoundException) {
            vm.notify("No Android speech recognition service is installed. All manual controls remain available.")
        } catch (_: SecurityException) {
            vm.notify("The speech recognition service could not access the microphone. Check its permissions in Android Settings.")
        }
    }
    LaunchedEffect(vm) { vm.messages.collect { snackbars.showSnackbar(it) } }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.scroll(false)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    val keepAwake = library.settings.keepAwake && screen.destination == Destination.NOW_PLAYING && document.tab != null
    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(screen.fullScreen, view) {
        val activity = context as? Activity
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        if (screen.fullScreen) {
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler(enabled = screen.fullScreen) { vm.fullScreen(false) }
    BackHandler(enabled = !screen.fullScreen && screen.destination != Destination.NOW_PLAYING) {
        vm.navigate(Destination.NOW_PLAYING)
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!screen.ready) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("OpenTabs", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(20.dp))
                if (screen.startupError == null) CircularProgressIndicator()
                else {
                    Text(screen.startupError.orEmpty())
                    Button(onClick = vm::initialize) { Text("Try again") }
                }
            }
        } else BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 840.dp
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbars) },
                bottomBar = {
                    if (!wide && !screen.fullScreen) NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(selected = screen.destination == destination,
                                onClick = { vm.navigate(destination) },
                                icon = { Icon(destination.icon(), contentDescription = null) },
                                label = { Text(destination.label, maxLines = 2) })
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (wide && !screen.fullScreen) NavigationRail(
                        modifier = Modifier.fillMaxHeight().width(112.dp).verticalScroll(rememberScrollState()),
                        containerColor = MaterialTheme.colorScheme.surface,
                        header = {
                            Text("OT", Modifier.padding(vertical = 22.dp), color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        },
                    ) {
                        Destination.entries.forEach { destination ->
                            NavigationRailItem(selected = screen.destination == destination,
                                onClick = { vm.navigate(destination) },
                                icon = { Icon(destination.icon(), contentDescription = null) },
                                label = { Text(destination.label) }, modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (!screen.fullScreen) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("OpenTabs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("A little practice, every day.", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { vm.navigate(Destination.SEARCH) }) {
                                    Icon(Icons.Default.Search, "Search tabs")
                                }
                                IconButton(onClick = { microphoneDialog = true }) { MicrophoneIcon() }
                                Button(enabled = !screen.importing, onClick = {
                                    import.launch(arrayOf("text/*", "application/octet-stream", "application/zip"))
                                }) {
                                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (screen.importing) "Importing…" else "Import")
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (screen.destination) {
                                Destination.NOW_PLAYING -> TabViewer(library, screen, document, vm)
                                Destination.SEARCH -> SearchScreen(search, screen, vm)
                                Destination.LIBRARY, Destination.PRACTICE -> LibraryScreen(library, screen, vm)
                                Destination.SETTINGS -> SettingsScreen(library.settings, vm)
                            }
                        }
                    }
                }
            }
        }
    }
    if (microphoneDialog) AlertDialog(onDismissRequest = { microphoneDialog = false },
        title = { Text("Microphone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Voice commands", fontWeight = FontWeight.Bold)
                Text("Find tabs for a song, open favorites, show your practice list, start or stop scrolling, and change tab size.")
                Text("Android’s speech service handles the microphone and may use the internet. OpenTabs does not record in the background.",
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Identify a song · unavailable", fontWeight = FontWeight.Bold)
                Text((vm.recognition.availability as Availability.Unavailable).reason,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { microphoneDialog = false; startVoice() }) { Text("Start voice command") } },
        dismissButton = { TextButton(onClick = { microphoneDialog = false }) { Text("Close") } })
    guitarProImport?.let { pending -> AlertDialog(onDismissRequest = vm::dismissGuitarProImport,
        title = { Text("Choose an arrangement") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pending.score.title, fontWeight = FontWeight.Bold)
            Text("GP7 preview preserves authored string/fret positions. Repeats, expressive techniques, and playback are not interpreted.")
            pending.tracks.forEach { track ->
                OutlinedButton(onClick = { vm.importGuitarTrack(track) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${track.name} · ${org.opentabs.core.tuningLabel(track.tuning)}")
                }
            }
        } }, confirmButton = { TextButton(onClick = vm::dismissGuitarProImport) { Text("Cancel") } }) }
}

private fun Destination.icon(): ImageVector = when (this) {
    Destination.NOW_PLAYING -> Icons.Default.PlayArrow
    Destination.SEARCH -> Icons.Default.Search
    Destination.LIBRARY -> Icons.Default.Menu
    Destination.PRACTICE -> Icons.Default.Star
    Destination.SETTINGS -> Icons.Default.Settings
}

@Composable
private fun MicrophoneIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp).semantics { contentDescription = "Microphone and voice commands" }) {
        val color = androidx.compose.ui.graphics.Color(0xFFEBEFE7)
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(size.width * .37f, size.height * .08f),
            size = androidx.compose.ui.geometry.Size(size.width * .26f, size.height * .50f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .13f), style = stroke)
        drawArc(color, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(size.width * .2f, size.height * .3f),
            size = androidx.compose.ui.geometry.Size(size.width * .6f, size.height * .45f), style = stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .75f),
            androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .92f), strokeWidth = 2.dp.toPx())
    }
}
