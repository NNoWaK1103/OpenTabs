package org.opentabs.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opentabs.core.*

/** Stores a URL only. Neither microphone grants nor Android objects survive the screen. */
class TunerMemory { var lastUrl: String = TunerUrls.HOME }

data class TunerUi(
    val url: String = TunerUrls.HOME, val loading: Boolean = false, val progress: Int = 0,
    val failure: WebFailure? = null, val notice: String? = null, val externalLink: String? = null,
    val microphone: TunerMicrophoneStatus = TunerMicrophoneStatus.IDLE,
    val generation: Int = 0, val pageSerial: Int = 0,
)

/** A separate permission boundary: tab-provider WebViews continue to deny all capture requests. */
class TunerController(
    private val memory: TunerMemory,
    hasAndroidPermission: () -> Boolean,
    requestAndroidPermission: () -> Unit,
    private val webViewFactory: (Context) -> WebView = { WebView(it) },
    private val interceptForTests: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) {
    private val mutableState = MutableStateFlow(TunerUi())
    val state = mutableState.asStateFlow()
    var webView: WebView? = null
        private set
    private var foreground = false
    private val requests = IdentityHashMap<PermissionRequest, TunerMicrophoneRequest>()
    private val microphone = TunerMicrophoneGate(hasAndroidPermission,
        { foreground && webView != null && state.value.failure == null &&
            TunerUrls.trustedPage(state.value.url) && TunerUrls.trustedPage(webView?.url.orEmpty()) },
        requestAndroidPermission, { status -> update { it.copy(microphone = status) } })

    @SuppressLint("SetJavaScriptEnabled")
    fun createHost(context: Context): FrameLayout = FrameLayout(context).also { host ->
        if (!foreground) return@also
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
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(view, true) }
            view.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(current: WebView, progress: Int) {
                    if (webView === current) update { it.copy(progress = progress.coerceIn(0, 100)) }
                }
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (webView !== view) { request.deny(); return }
                    val adapter = object : TunerMicrophoneRequest {
                        override val origin = request.origin.toString()
                        override val resources = request.resources.toSet()
                        override fun grantAudio() {
                            requests.remove(request)
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        }
                        override fun deny() { requests.remove(request); request.deny() }
                    }
                    requests[request] = adapter
                    microphone.request(adapter)
                }
                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    requests.remove(request)?.let(microphone::canceled)
                }
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                    callback.invoke(origin, false, false)
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.isForMainFrame && interceptNavigation(request.url.toString())
                @Deprecated("Legacy WebView callback")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = interceptNavigation(url)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    interceptForTests?.invoke(request) // No interception, script injection, or ad filtering in production.
                override fun onPageStarted(current: WebView, url: String?, favicon: Bitmap?) {
                    if (webView !== current) return
                    microphone.pause()
                    if (url == null || !TunerUrls.trustedPage(url)) {
                        fail(WebFailure(WebFailureKind.UNSAFE, "This address is outside the tuner website.", true)); return
                    }
                    memory.lastUrl = url
                    update { it.copy(url = url, loading = true, progress = 0, failure = null, notice = null,
                        externalLink = null, pageSerial = it.pageSerial + 1) }
                }
                override fun onPageFinished(current: WebView, url: String?) {
                    if (webView === current && currentPage(url)) update { it.copy(loading = false, progress = 100) }
                }
                override fun doUpdateVisitedHistory(current: WebView, url: String?, isReload: Boolean) {
                    if (webView === current && url != null && TunerUrls.trustedPage(url)) {
                        memory.lastUrl = url
                        update { it.copy(url = url) }
                    }
                }
                override fun onReceivedError(current: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (webView === current && request.isForMainFrame && currentPage(request.url.toString()))
                        fail(if (error.errorCode == ERROR_FAILED_SSL_HANDSHAKE) WebFailure.tls() else WebFailure.network())
                }
                override fun onReceivedHttpError(current: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (webView === current && request.isForMainFrame && currentPage(request.url.toString())) {
                        microphone.pause()
                        update { it.copy(failure = WebFailure.http(response.statusCode)) }
                    }
                }
                override fun onReceivedSslError(current: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    if (webView === current) fail(WebFailure.tls())
                }
                override fun onRenderProcessGone(current: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (webView === current) fail(WebFailure.renderer())
                    return true
                }
            }
            view.setDownloadListener { _, _, _, _, _ -> update { it.copy(notice = "Open the website in your browser for downloads.") } }
            host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val target = memory.lastUrl.takeIf(TunerUrls::trustedPage) ?: TunerUrls.HOME
            update { it.copy(url = target, loading = true, progress = 0, failure = null, notice = null) }
            view.onResume()
            view.loadUrl(target)
        } catch (_: RuntimeException) { fail(WebFailure.unavailable()) }
        catch (_: LinkageError) { fail(WebFailure.unavailable()) }
    }

    fun androidPermissionResult(granted: Boolean) { microphone.androidResult(granted) }
    fun resume() { foreground = true; webView?.onResume() }
    fun pause() { foreground = false; release() }
    fun retry() {
        release()
        update { it.copy(failure = null, notice = null, externalLink = null, generation = it.generation + 1) }
    }
    fun goBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        microphone.pause()
        view.goBack()
        return true
    }
    fun markSlow(serial: Int) {
        if (state.value.loading && serial == state.value.pageSerial) update {
            it.copy(notice = "The tuner is taking longer to load. Check your connection or reload.")
        }
    }
    fun fallbackUrl() = state.value.url.takeIf(TunerUrls::trustedPage) ?: TunerUrls.HOME
    fun releaseHost(host: FrameLayout) {
        val view = webView ?: return
        if (view.parent === host || view.parent == null) release()
    }
    private fun release() {
        microphone.pause()
        requests.clear()
        val view = webView ?: return
        webView = null
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.stopLoading() }
        runCatching { view.onPause() }
        runCatching { view.webChromeClient = null; view.webViewClient = WebViewClient() }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() } // onPause alone does not guarantee that WebRTC microphone tracks stop.
        update { it.copy(loading = false) }
    }
    private fun fail(failure: WebFailure) {
        release()
        update { it.copy(loading = false, failure = failure) }
    }
    private fun interceptNavigation(url: String): Boolean {
        if (TunerUrls.trustedPage(url)) return false
        if (WebTabUrls.safeHttps(url)) update { it.copy(externalLink = url, notice = "This link leaves guitar-tuner.org. Open it externally to continue.") }
        else update { it.copy(notice = "This link cannot be opened safely.") }
        return true
    }
    private fun currentPage(url: String?) = url != null && url.substringBefore('#') == state.value.url.substringBefore('#')
    private fun update(transform: (TunerUi) -> TunerUi) { mutableState.value = transform(state.value) }
}
