package org.opentabs.app

import android.app.Application
import android.webkit.WebResourceResponse
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.opentabs.core.*

/** Real form, ViewModel, WebView and navigation; website responses are clearly labeled fixtures. */
class TabSearchFlowTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var vm: OpenTabsViewModel
    private lateinit var browser: WebTabController

    @After fun clearViewModel() { compose.runOnUiThread { store.clear() } }

    private fun launch() {
        compose.runOnUiThread {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[OpenTabsViewModel::class.java]
            vm.navigate(Destination.SEARCH)
            browser = WebTabController(vm.webTabMemory, interceptForTests = { request ->
                val tab = if (WebTabUrls.source(request.url.toString()) == WebTabSource.SONGSTERR)
                    "https://www.songsterr.com/a/wsa/open-tabs-test-tab-s1"
                else "https://tabs.ultimate-guitar.com/tab/open-tabs-test/guitar-tabs-1"
                val html = """<!doctype html><html><head><title>OpenTabs TEST FIXTURE</title><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                    <body><a style="position:absolute;top:0;left:0;width:100%;height:60px" href="$tab">Open test tab</a>
                    <pre>TEST FIXTURE ONLY<br>e|---0---|<br>B|---0---|<br>G|---0---|<br>D|---2---|<br>A|---2---|<br>E|---0---|</pre></body></html>"""
                WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
            })
        }
        compose.setContent { OpenTabsTheme {
            val screen by vm.screen.collectAsState()
            val request = screen.webTab
            if (request == null) SearchScreen(screen, vm::setQuery, vm::setArtistQuery, vm::selectTabSource, vm::searchTabs, {})
            else WebTabScreen(request, vm.webTabMemory, false, false, vm::closeBrowser, vm::browse, {}, {}, {}, browser)
        } }
    }

    private fun waitForPage(url: String) {
        compose.waitUntil(20_000) { browser.state.value.url == url && !browser.state.value.loading && browser.state.value.progress == 100 }
    }

    @Test fun bothSourcesSearchAndOpenTabLinksRatherThanMetadataEntries() {
        launch()
        WebTabSource.entries.forEach { source ->
            compose.onNodeWithText("Song title").performTextClearance()
            compose.onNodeWithText("Song title").performTextInput("Nutshell")
            compose.onNodeWithText("Artist").performTextClearance()
            compose.onNodeWithText("Artist").performTextInput("Alice in Chains")
            compose.onNodeWithContentDescription("Tab source ${source.label}").performScrollTo().performClick()
            compose.runOnIdle { assertNull(vm.screen.value.webTab) }
            compose.onNodeWithText("Find tabs on ${source.label}").performScrollTo().performClick()
            val searchUrl = source.searchUrl("Nutshell", "Alice in Chains")
            waitForPage(searchUrl)
            compose.runOnIdle {
                assertEquals(searchUrl, vm.screen.value.webTab!!.url)
                assertEquals(source, WebTabUrls.source(browser.state.value.url))
            }
            compose.onNodeWithContentDescription("Embedded tab website").performTouchInput { click(Offset(24f, 24f)) }
            compose.waitUntil(20_000) { browser.state.value.url.contains(if (source == WebTabSource.SONGSTERR) "test-tab-s1" else "guitar-tabs-1") && !browser.state.value.loading }
            compose.runOnIdle { assertEquals(WebNavigation.EMBEDDED, WebTabUrls.navigation(browser.state.value.url)) }
            compose.onNodeWithText("Native tabs").performClick()
            compose.runOnIdle { assertNull(vm.screen.value.webTab); assertNull(browser.webView) }
        }
    }

    @Test fun blankSearchCannotOpenAnythingAndTheFormOnlyOffersTheTwoTabSources() {
        launch()
        compose.onNodeWithText("Find tabs on Songsterr").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Tab source Songsterr").assertIsSelected()
        compose.onNodeWithContentDescription("Tab source Ultimate Guitar").assertExists()
        compose.onNodeWithContentDescription("Search online sources").assertDoesNotExist()
        compose.onNodeWithText("Details").assertDoesNotExist()
        compose.runOnIdle { vm.searchTabs(); assertNull(vm.screen.value.webTab) }
    }

    @Test fun voiceSearchKeepsTitleArtistAndChosenSourceAndKeyboardSearchOpensTheWebsite() {
        launch()
        compose.runOnIdle {
            vm.selectTabSource(WebTabSource.ULTIMATE_GUITAR)
            vm.voice("Find tabs for Nutshell by Alice in Chains.")
        }
        compose.onNodeWithText("Song title").assertTextContains("Nutshell")
        compose.onNodeWithText("Artist").assertTextContains("Alice in Chains")
        compose.onNodeWithText("Artist").performImeAction()
        waitForPage(WebTabSource.ULTIMATE_GUITAR.searchUrl("Nutshell", "Alice in Chains"))
    }
}
