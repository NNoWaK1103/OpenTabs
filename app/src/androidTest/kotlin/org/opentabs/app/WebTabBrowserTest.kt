package org.opentabs.app

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.opentabs.core.*
import java.io.File
import kotlinx.coroutines.runBlocking

class WebTabBrowserTest {
    @get:Rule val compose = createComposeRule()

    private fun controller(memory: WebTabMemory = WebTabMemory(), status: Int = 200,
        factory: ((android.content.Context) -> WebView)? = null) = WebTabController(memory,
        webViewFactory = factory ?: { WebView(it) }, interceptForTests = { request ->
            val page = if (request.url.path.orEmpty().contains("open-tabs-test-tab")) "Fixture tab page" else "Fixture search results"
            val html = """<!doctype html><html><head><title>$page</title><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body><h1>OpenTabs browser TEST FIXTURE</h1><p>$page</p><a id="tab" style="position:absolute;top:0;left:0;width:100%;height:60px;background:white;z-index:2" href="/a/wsa/open-tabs-test-tab-s1">Open fixture exercise</a>
                <a id="external" href="https://example.org/sign-in">External fixture link</a></body></html>"""
            WebResourceResponse("text/html", "UTF-8", status, if (status == 200) "OK" else "Forbidden",
                emptyMap(), html.byteInputStream())
        })

    private fun waitLoaded(browser: WebTabController, expected: (String) -> Boolean = { true }) {
        compose.waitUntil(20_000) { browser.webView != null && expected(browser.state.value.url) && !browser.state.value.loading && browser.state.value.progress == 100 }
    }

    @Test fun savedWebsiteLinksRemainExplicitlyOnlineAndReopenInsideTheApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("test-web-links-", ".json", context.cacheDir).also { it.delete() }
        val repository = WebsiteBookmarkRepository(file)
        val memory = WebTabMemory()
        val browser = controller(memory)
        val request = WebTabRequest(WebTabSource.SONGSTERR, "Original exercise", "", 1)
        val tabUrl = "https://www.songsterr.com/a/wsa/open-tabs-test-tab-s1"
        try {
            compose.setContent { OpenTabsTheme {
                WebTabScreen(request, memory, false, false, {}, {}, {}, {}, {}, browser, repository)
            } }
            waitLoaded(browser)
            compose.onNodeWithText("Save link").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { browser.openSavedLink(tabUrl) }
            waitLoaded(browser) { it == tabUrl }
            compose.onNodeWithText("Save link").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { runBlocking { repository.list().size == 1 } }
            compose.runOnIdle { browser.goBack() }
            waitLoaded(browser) { it == request.url }
            compose.onNodeWithText("Saved links").performScrollTo().performClick()
            compose.onNode(hasText("Internet required.", substring = true)).assertIsDisplayed()
            compose.onNodeWithText("Fixture tab page").performClick()
            waitLoaded(browser) { it == tabUrl }
            compose.onNodeWithText("Saved links").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Remove saved link Fixture tab page").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.list().isEmpty() } }
            compose.onNodeWithText("Done").performClick()
        } finally { file.delete() }
    }

    @Test fun searchLinksHistorySourceSwitchingAndRelease() {
        val memory = WebTabMemory()
        var browser by mutableStateOf(controller(memory))
        var request by mutableStateOf(WebTabRequest(WebTabSource.SONGSTERR, "Nutshell", "Alice in Chains", 1))
        var visible by mutableStateOf(true)
        var full by mutableStateOf(false)
        var awake by mutableStateOf(false)
        compose.setContent { OpenTabsTheme {
            if (visible) WebTabScreen(request, memory, full, awake, { visible = false }, { request = request.switchTo(it) },
                { full = it }, { awake = it }, {}, browser)
            else Text("Search resumed")
        } }
        waitLoaded(browser) { it == request.url }
        compose.runOnIdle {
            assertFalse(browser.webView!!.settings.allowFileAccess)
            assertFalse(browser.webView!!.settings.allowContentAccess)
        }
        // A genuine input event creates normal user-initiated history. Scripted clicks can be treated as redirects.
        compose.onNodeWithContentDescription("Embedded tab website").performTouchInput { click(Offset(24f, 24f)) }
        waitLoaded(browser) { it.contains("open-tabs-test-tab") }
        compose.waitUntil(10_000) { browser.state.value.canBack }
        compose.onNodeWithContentDescription("Back in website").assertIsEnabled().performClick()
        waitLoaded(browser) { it == request.url }
        compose.waitUntil(10_000) { browser.state.value.canForward }
        compose.onNodeWithContentDescription("Forward in website").assertIsEnabled().performClick()
        waitLoaded(browser) { it.contains("open-tabs-test-tab") }
        compose.onNodeWithContentDescription("Refresh website").performClick()
        waitLoaded(browser) { it.contains("open-tabs-test-tab") }
        compose.onNodeWithText("Full screen").performClick()
        compose.onNodeWithText("Exit full screen").assertIsDisplayed().performClick()
        compose.onNodeWithText("Screen awake").performClick()
        compose.runOnIdle { assertTrue(awake) }
        compose.onNodeWithContentDescription("Browse Ultimate Guitar").performClick()
        waitLoaded(browser) { it == WebTabSource.ULTIMATE_GUITAR.searchUrl("Nutshell", "Alice in Chains") }
        compose.runOnIdle { assertEquals("Alice in Chains", request.artist) }
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Search resumed").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(browser.webView)
            assertNotNull(memory.history)
            browser = controller(memory)
            visible = true
        }
        waitLoaded(browser) { it.contains("ultimate-guitar.com") }
        compose.runOnIdle { assertTrue(browser.state.value.canBack) }
    }

    @Test fun spaNavigationCancelsOfferedAndPendingDownloadsButAnchorsDoNot() {
        val memory = WebTabMemory()
        val browser = controller(memory)
        val request = WebTabRequest(WebTabSource.SONGSTERR, "Study", "", 1)
        compose.setContent { OpenTabsTheme { WebTabScreen(request, memory, false, false, {}, {}, {}, {}, {}, browser) } }
        waitLoaded(browser)
        compose.runOnIdle {
            val view = browser.webView!!
            val original = browser.state.value.url
            browser.downloads.offer(original, "https://www.songsterr.com/file.pdf", "Test", "attachment; filename=Study.pdf", "application/pdf", 128)
            assertEquals(ProviderDownloadUiState.Stage.OFFER, browser.downloads.state.value.stage)
            view.webViewClient.doUpdateVisitedHistory(view, "$original#measure-2", false)
            assertEquals(ProviderDownloadUiState.Stage.OFFER, browser.downloads.state.value.stage)
            browser.downloads.chooseDestination()
            view.webViewClient.doUpdateVisitedHistory(view, "https://www.songsterr.com/a/wsa/another-tab-s2", false)
            assertEquals(ProviderDownloadUiState.Stage.IDLE, browser.downloads.state.value.stage)
        }
    }

    @Test fun accessRestrictionKeepsTheDeliveredPageAndShowsExternalFallback() {
        val memory = WebTabMemory()
        val browser = controller(memory, status = 403)
        val request = WebTabRequest(WebTabSource.ULTIMATE_GUITAR, "Study", "Bach", 1)
        compose.setContent { OpenTabsTheme { WebTabScreen(request, memory, false, false, {}, {}, {}, {}, {}, browser) } }
        waitLoaded(browser)
        // Intercepted responses do not consistently emit Chromium's HTTP-error callback.
        // Exercise that platform callback explicitly while keeping the delivered fixture visible.
        compose.runOnIdle {
            val view = browser.webView!!
            val mainRequest = object : WebResourceRequest {
                override fun getUrl() = Uri.parse(browser.state.value.url)
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            }
            view.webViewClient.onReceivedHttpError(view, mainRequest,
                WebResourceResponse("text/html", "UTF-8", 403, "Forbidden", emptyMap(), "TEST FIXTURE".byteInputStream()))
        }
        compose.waitUntil(10_000) { browser.state.value.failure?.kind == WebFailureKind.HTTP }
        compose.runOnIdle { assertFalse(browser.state.value.failure!!.coversPage); assertNotNull(browser.webView) }
        compose.onNode(hasText("HTTP 403", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Open externally").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss website notice").performClick()
        compose.runOnIdle { assertNull(browser.state.value.failure) }
    }

    @Test fun missingWebViewIsRecoverableWithoutAnAutomaticRetryLoop() {
        val memory = WebTabMemory()
        var attempts = 0
        val browser = controller(memory, factory = { context ->
            attempts++
            if (attempts == 1) throw IllegalStateException("Test-only missing WebView")
            WebView(context)
        })
        val request = WebTabRequest(WebTabSource.SONGSTERR, "Study", "", 1)
        compose.setContent { OpenTabsTheme { WebTabScreen(request, memory, false, false, {}, {}, {}, {}, {}, browser) } }
        compose.onNodeWithText("Website unavailable").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, attempts); assertNull(browser.webView) }
        compose.onNodeWithText("Retry website").performClick()
        waitLoaded(browser)
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun externalLinksRequireAnExplicitActionAndRendererFailureCanBeRecreated() {
        val memory = WebTabMemory()
        val browser = controller(memory)
        val request = WebTabRequest(WebTabSource.SONGSTERR, "Study", "", 1)
        compose.setContent { OpenTabsTheme { WebTabScreen(request, memory, false, false, {}, {}, {}, {}, {}, browser) } }
        waitLoaded(browser)
        compose.runOnIdle { browser.webView!!.evaluateJavascript("document.getElementById('external').click()", null) }
        compose.waitUntil(10_000) { browser.state.value.externalLink != null }
        compose.runOnIdle { assertEquals(request.url, browser.state.value.url) }
        compose.onNodeWithText("Open externally").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss website notice").performClick()
        compose.runOnIdle {
            val view = browser.webView!!
            view.webViewClient.onRenderProcessGone(view, object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_BOUND
            })
        }
        compose.onNodeWithText("Website unavailable").assertIsDisplayed()
        compose.runOnIdle { assertNull(browser.webView) }
        compose.onNodeWithText("Retry website").performClick()
        waitLoaded(browser)
        compose.runOnIdle { assertNull(browser.state.value.failure) }
    }
}
