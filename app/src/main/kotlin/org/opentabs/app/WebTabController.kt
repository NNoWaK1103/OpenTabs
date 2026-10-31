package org.opentabs.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Parcel
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opentabs.core.*

/** Holds bounded navigation data across rotation, never an Activity or WebView. */
class WebTabMemory {
    var revision: Long = -1
    var lastUrl: String = ""
    var history: Bundle? = null
    fun clear() { revision = -1; lastUrl = ""; history = null }
}

data class WebTabUi(
    val url: String = "", val title: String = "", val loading: Boolean = false, val progress: Int = 0,
    val canBack: Boolean = false, val canForward: Boolean = false, val failure: WebFailure? = null,
    val externalLink: String? = null, val notice: String? = null, val slow: Boolean = false,
    val generation: Int = 0, val pageSerial: Int = 0,
)

/** Owns exactly one displayed WebView; created by AndroidView and released with its host. */
class WebTabController(
    private val memory: WebTabMemory,
    private val webViewFactory: (Context) -> WebView = { WebView(it) },
    private val interceptForTests: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) {
    private val mutableState = MutableStateFlow(WebTabUi())
    val state = mutableState.asStateFlow()
    var webView: WebView? = null
        private set
    private var request: WebTabRequest? = null
    private var handledRevision = -1L
    private var resumed = true

    @SuppressLint("SetJavaScriptEnabled")
    fun createHost(context: Context, initial: WebTabRequest): FrameLayout = FrameLayout(context).also { host ->
        request = initial
        handledRevision = initial.revision
        try {
            val view = webViewFactory(context)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_DEFAULT
                // Retain the real WebView User-Agent. Do not impersonate a browser to evade restrictions.
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }
            view.webViewClient = client()
            view.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, progress: Int) {
                    if (webView === view) update { it.copy(progress = progress.coerceIn(0, 100)) }
                }
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (webView === view) update { it.copy(title = title.orEmpty().take(180)) }
                }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, false, false)
                }
            }
            view.setDownloadListener { _, _, _, _, _ ->
                update { it.copy(notice = "Use Open in browser for website downloads. OpenTabs does not extract or save this website’s tabs.") }
            }
            host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            request = initial
            val restored = if (memory.revision == initial.revision && WebTabUrls.source(memory.lastUrl) != null) {
                memory.history?.let { runCatching { view.restoreState(it) }.getOrNull() }
            } else null
            handledRevision = initial.revision
            if (restored == null) {
                val target = if (memory.revision == initial.revision) WebTabUrls.fallback(memory.lastUrl, initial) else initial.url
                load(target)
            } else {
                update { it.copy(url = memory.lastUrl, failure = null) }
                navigationState(view)
            }
            if (resumed) { view.onResume(); view.resumeTimers() } else { view.onPause(); view.pauseTimers() }
        } catch (_: RuntimeException) {
            releaseHost(host, save = false)
            handledRevision = initial.revision
            update { it.copy(loading = false, failure = WebFailure.unavailable()) }
        } catch (_: LinkageError) {
            releaseHost(host, save = false)
            handledRevision = initial.revision
            update { it.copy(loading = false, failure = WebFailure.unavailable()) }
        }
    }

    fun show(next: WebTabRequest) {
        request = next
        if (handledRevision == next.revision) return
        handledRevision = next.revision
        memory.clear()
        if (webView == null) {
            update { it.copy(url = next.url) }
            retry()
        } else load(next.url)
    }

    private fun load(url: String) {
        if (WebTabUrls.navigation(url) != WebNavigation.EMBEDDED) {
            update { it.copy(failure = WebFailure(WebFailureKind.UNSAFE, "This address cannot be opened in the embedded viewer.", true), loading = false) }
            return
        }
        update { it.copy(url = url, title = "", loading = true, progress = 0, failure = null, externalLink = null, notice = null, slow = false) }
        webView?.loadUrl(url)
    }

    fun goBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        update { it.copy(failure = null, externalLink = null, notice = null) }
        view.goBack()
        return true
    }
    fun goForward() {
        webView?.takeIf { it.canGoForward() }?.let { view ->
            update { it.copy(failure = null, externalLink = null, notice = null) }
            view.goForward()
        }
    }
    fun refresh() {
        val view = webView
        if (view == null) retry()
        else if (WebTabUrls.source(view.url.orEmpty()) == null) load(fallbackUrl())
        else {
            update { it.copy(loading = true, progress = 0, failure = null, notice = null, externalLink = null, slow = false) }
            view.reload()
        }
    }
    fun stop() { webView?.stopLoading(); update { it.copy(loading = false, slow = false) } }
    fun zoom(increase: Boolean) {
        val changed = if (increase) webView?.zoomIn() else webView?.zoomOut()
        if (changed == false) update { it.copy(notice = "This page limits further zoom. Pinch to zoom or use the external browser.") }
    }
    fun markSlow(serial: Int) {
        if (state.value.pageSerial == serial && state.value.loading) update { it.copy(slow = true) }
    }
    fun dismissNotice() { update { it.copy(notice = null, externalLink = null, slow = false, failure = it.failure?.takeIf { failure -> failure.coversPage }) } }
    fun fallbackUrl(): String = request?.let { WebTabUrls.fallback(state.value.url, it) }.orEmpty()
    fun retry() {
        val current = state.value.url
        memory.clear()
        request?.let { memory.revision = it.revision; memory.lastUrl = WebTabUrls.fallback(current, it) }
        update { it.copy(failure = null, loading = false, generation = it.generation + 1) }
    }
    fun resume() { resumed = true; webView?.let { it.onResume(); it.resumeTimers() } }
    fun pause() { resumed = false; webView?.let { it.onPause(); it.pauseTimers() } }

    fun releaseHost(host: FrameLayout, save: Boolean = true) {
        val view = webView ?: return
        if (view.parent !== host && view.parent != null) return
        release(view, save)
    }
    private fun release(view: WebView, save: Boolean) {
        if (webView !== view) return
        if (save) request?.let { current ->
            memory.revision = current.revision
            memory.lastUrl = WebTabUrls.fallback(state.value.url, current)
            memory.history = runCatching {
                val bundle = Bundle()
                if (view.copyBackForwardList().size > 30) return@runCatching null
                view.saveState(bundle)
                val parcel = Parcel.obtain()
                try { parcel.writeBundle(bundle); bundle.takeIf { parcel.dataSize() <= 200 * 1024 } }
                finally { parcel.recycle() }
            }.getOrNull()
        }
        webView = null
        handledRevision = -1
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.stopLoading() }
        runCatching { view.onPause() }
        runCatching { view.resumeTimers() } // No global timer pause is left behind for a future WebView.
        runCatching { view.webChromeClient = null; view.webViewClient = WebViewClient() }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() }
    }

    private fun client() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return interceptNavigation(request.url.toString())
        }
        @Deprecated("Legacy callback for WebView implementations")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = interceptNavigation(url)
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            interceptForTests?.invoke(request) // Null in production: all page resources and ads are delivered normally.

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (webView !== view) return
            if (url == null || WebTabUrls.navigation(url) != WebNavigation.EMBEDDED) {
                view.stopLoading()
                update { it.copy(loading = false, failure = WebFailure(WebFailureKind.UNSAFE,
                    "The page left the supported website. Use Open in browser for external navigation.", true)) }
                return
            }
            update { it.copy(url = url, loading = true, progress = 0, failure = null, slow = false,
                externalLink = null, notice = null, pageSerial = it.pageSerial + 1) }
            navigationState(view)
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (webView !== view) return
            if (url != null && WebTabUrls.source(url) != null) update { it.copy(url = url) }
            navigationState(view)
        }
        override fun onPageFinished(view: WebView, url: String?) {
            if (webView !== view) return
            update { it.copy(loading = false, progress = 100, slow = false) }
            navigationState(view)
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (webView === view && request.isForMainFrame) update { it.copy(loading = false, failure = WebFailure.network()) }
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (webView === view && request.isForMainFrame) update { it.copy(failure = WebFailure.http(response.statusCode)) }
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (webView === view) update { it.copy(loading = false, failure = WebFailure.tls()) }
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (webView === view) {
                release(view, save = false)
                handledRevision = request?.revision ?: -1
                memory.clear()
                update { it.copy(loading = false, canBack = false, canForward = false, failure = WebFailure.renderer()) }
            }
            return true
        }
    }

    private fun interceptNavigation(url: String): Boolean = when (WebTabUrls.navigation(url)) {
        WebNavigation.EMBEDDED -> false
        WebNavigation.EXTERNAL -> { update { it.copy(externalLink = url, notice = "This link goes to ${android.net.Uri.parse(url).host}. Open it in your browser to continue.") }; true }
        WebNavigation.BLOCKED -> { update { it.copy(notice = "This link type cannot open here. Use the website’s HTTPS navigation or Open in browser.") }; true }
    }
    private fun navigationState(view: WebView) {
        if (webView === view) update { it.copy(canBack = view.canGoBack(), canForward = view.canGoForward()) }
    }
    private fun update(transform: (WebTabUi) -> WebTabUi) { mutableState.value = transform(state.value) }
}
