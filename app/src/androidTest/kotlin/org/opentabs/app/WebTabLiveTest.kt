package org.opentabs.app

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.opentabs.core.*

/** Real website probes. Restrictions/offline errors must remain visible and offer browser fallback. */
@SdkSuppress(minSdkVersion = 35)
class WebTabLiveTest {
    @get:Rule val compose = createComposeRule()

    @Test fun songsterrRealWebsiteOrExplicitFallback() = probe(WebTabSource.SONGSTERR)
    @Test fun ultimateGuitarRealWebsiteOrExplicitFallback() = probe(WebTabSource.ULTIMATE_GUITAR)

    private fun probe(source: WebTabSource) {
        val memory = WebTabMemory()
        val browser = WebTabController(memory) // No fixture interception, cookies, or User-Agent override.
        val request = WebTabRequest(source, "Nutshell", "Alice in Chains", 1)
        compose.setContent { OpenTabsTheme { WebTabScreen(request, memory, false, false, {}, {}, {}, {}, {}, browser) } }
        try {
            compose.waitUntil(35_000) { browser.state.value.failure != null ||
                (browser.state.value.url.isNotBlank() && browser.state.value.progress == 100 && !browser.state.value.loading) }
        } catch (_: ComposeTimeoutException) {
            compose.runOnIdle { browser.markSlow(browser.state.value.pageSerial) }
        }
        val state = browser.state.value
        Log.i("OpenTabsLiveWeb", "${source.label}: title=${state.title}; url=${state.url}; failure=${state.failure?.kind}; slow=${state.slow}")
        assertEquals(source, WebTabUrls.source(browser.fallbackUrl()))
        compose.onNodeWithContentDescription("Embedded tab website").assertExists()
        assertTrue(compose.onAllNodesWithText("Open in browser").fetchSemanticsNodes().isNotEmpty())
        if (state.failure?.coversPage == true) compose.onNodeWithText("Website unavailable").assertIsDisplayed()
        // Optional visual evidence of the real page/restriction; capture failure does not disguise page status.
        runCatching {
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: error("System screenshot unavailable")
            val folder = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            File(folder, "webview-${source.name.lowercase()}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }.onFailure { Log.i("OpenTabsLiveWeb", "Screenshot unavailable: ${it.javaClass.simpleName}") }
        compose.runOnIdle { browser.stop() }
    }
}
