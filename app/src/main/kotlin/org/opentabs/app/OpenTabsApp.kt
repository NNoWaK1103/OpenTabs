package org.opentabs.app

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun OpenTabsApp(vm: ProviderTabsViewModel = viewModel()) = OpenTabsTheme {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current
    val view = LocalView.current
    var savedLinksOpen by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.messages.collect { snackbars.showSnackbar(it) } }
    val keepAwake = screen.keepAwake && screen.webTab != null
    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(screen.fullScreen, view) {
        val controller = (context as? Activity)?.let { WindowCompat.getInsetsController(it.window, view) }
        if (screen.fullScreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbars) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val request = screen.webTab
                if (request == null) ProviderSearchScreen(screen.query, vm::setQuery, vm::browse,
                    onSavedLinks = { savedLinksOpen = true })
                else WebTabScreen(request, vm.webTabMemory, screen.fullScreen, screen.keepAwake,
                    vm::closeBrowser, vm::browse, vm::fullScreen, vm::keepAwake, vm::notify,
                    providedBookmarkRepository = vm.bookmarks)
            }
        }
    }
    if (savedLinksOpen) SavedWebsiteLinksDialog(vm.bookmarks,
        onOpen = { url -> savedLinksOpen = false; vm.openSavedLink(url) },
        onDismiss = { savedLinksOpen = false }, notify = vm::notify)
}
