package org.opentabs.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.opentabs.core.*

class TunerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private lateinit var tuner: TunerController
    private fun fixture(request: WebResourceRequest) = WebResourceResponse("text/html", "UTF-8",
        """<!doctype html><html><head><title>OpenTabs TUNER TEST FIXTURE</title></head><body><h1>TEST FIXTURE</h1><p>${request.url.path}</p></body></html>""".byteInputStream())

    @After fun cleanUp() { compose.runOnUiThread { store.clear() } }

    private fun viewModel(): OpenTabsViewModel {
        lateinit var vm: OpenTabsViewModel
        compose.runOnUiThread {
            val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[OpenTabsViewModel::class.java]
        }
        return vm
    }
    private fun launch(permitted: () -> Boolean = { true }, ask: () -> Unit = {}) {
        compose.setContent { OpenTabsTheme { TunerScreen(TunerMemory(), {}, {}, { memory, _, _ ->
            TunerController(memory, permitted, ask, interceptForTests = ::fixture).also { tuner = it }
        }) } }
        loaded()
    }
    private fun loaded() { compose.waitUntil(20_000) {
        ::tuner.isInitialized && tuner.webView != null && !tuner.state.value.loading && tuner.state.value.progress == 100
    } }
    private class Request(origin: String = TunerUrls.HOME,
        private val wanted: Array<String> = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) : PermissionRequest() {
        private val requester = Uri.parse(origin)
        var granted: Array<String>? = null
        var denied = false
        override fun getOrigin() = requester
        override fun getResources() = wanted
        override fun grant(resources: Array<String>) { granted = resources }
        override fun deny() { denied = true }
    }

    @Test fun onlyTrustedAudioIsGrantedAndForeignOrCameraRequestsAreDenied() {
        launch()
        compose.runOnIdle {
            val view = tuner.webView!!
            assertTrue(view.settings.javaScriptEnabled)
            assertTrue(view.settings.domStorageEnabled)
            assertFalse(view.settings.allowFileAccess)
            val audio = Request(wanted = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            view.webChromeClient!!.onPermissionRequest(audio)
            assertArrayEquals(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), audio.granted)
            listOf(Request("https://ads.example/"), Request("https://guitar-tuner.org.evil.example/"),
                Request(wanted = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))).forEach {
                view.webChromeClient!!.onPermissionRequest(it)
                assertTrue(it.denied)
                assertNull(it.granted)
            }
        }
    }

    @Test fun trustedSiteRequestUsesActualAndroidRuntimePermissionBeforeGranting() {
        compose.setContent { OpenTabsTheme { TunerScreen(TunerMemory(), {}, {}, { memory, permitted, ask ->
            TunerController(memory, permitted, ask, interceptForTests = ::fixture).also { tuner = it }
        }) } }
        loaded()
        val request = Request()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alreadyAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        compose.runOnIdle { tuner.webView!!.webChromeClient!!.onPermissionRequest(request) }
        if (!alreadyAllowed) {
            compose.runOnIdle { assertNull(request.granted) }
            compose.onNodeWithText("Allow tuner microphone?").assertIsDisplayed()
            compose.onNodeWithText("Continue").performClick()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val serviceInfo = automation.serviceInfo
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = serviceInfo
            val deadline = SystemClock.uptimeMillis() + 10_000
            var clicked = false
            val ids = listOf("com.android.permissioncontroller:id/permission_allow_foreground_only_button",
                "com.google.android.permissioncontroller:id/permission_allow_foreground_only_button",
                "com.android.permissioncontroller:id/permission_allow_button", "com.google.android.permissioncontroller:id/permission_allow_button",
                "com.android.packageinstaller:id/permission_allow_button", "com.google.android.packageinstaller:id/permission_allow_button")
            while (!clicked && SystemClock.uptimeMillis() < deadline) {
                val root = automation.rootInActiveWindow
                val button = ids.firstNotNullOfOrNull { id -> root?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() }
                clicked = button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!clicked) SystemClock.sleep(100)
            }
            assertTrue("Android microphone consent dialog should be actionable", clicked)
        }
        compose.waitUntil(10_000) { request.granted != null }
        compose.runOnIdle {
            assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
            assertArrayEquals(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE), request.granted)
        }
    }

    @Test fun backgroundingDestroysTheWebViewAndLatePermissionCannotGrantOldRequests() {
        var permitted = false
        var prompts = 0
        launch({ permitted }, { prompts++ })
        val request = Request()
        compose.runOnIdle {
            tuner.webView!!.webChromeClient!!.onPermissionRequest(request)
            assertEquals(1, prompts)
        }
        val previousView = tuner.webView
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertNull(tuner.webView)
        assertTrue(request.denied)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        loaded()
        compose.runOnIdle {
            assertNotSame(previousView, tuner.webView)
            permitted = true
            tuner.androidPermissionResult(true)
            assertNull(request.granted)
        }
    }

    @Test fun rendererErrorsStopTheViewAndRetryCreatesANewTrustedPage() {
        launch()
        compose.runOnIdle {
            val view = tuner.webView!!
            view.webViewClient.onRenderProcessGone(view, object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_BOUND
            })
            assertNull(tuner.webView)
        }
        compose.onNodeWithText("Tuner unavailable").assertIsDisplayed()
        compose.onNodeWithText("Open in browser").assertIsDisplayed()
        compose.onNodeWithText("Retry tuner").performClick()
        loaded()
        compose.runOnIdle { assertTrue(TunerUrls.trustedPage(tuner.state.value.url)) }
    }

    @Test fun sidebarOpensTunerAndReturningPreservesNativeTabAndScrollPosition() {
        val vm = viewModel()
        compose.setContent { OpenTabsApp(vm, tunerFactory = { memory, permitted, ask ->
            TunerController(memory, permitted, ask, interceptForTests = ::fixture).also { tuner = it }
        }) }
        val tabDescription = "Six-string guitar tablature. Swipe vertically or horizontally to scroll."
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(tabDescription).fetchSemanticsNodes().isNotEmpty() }
        val savedId = vm.library.value.currentSongId
        compose.onNodeWithContentDescription(tabDescription).performScrollToIndex(3)
        val position = compose.onNodeWithContentDescription(tabDescription).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithText("Tuner", useUnmergedTree = false).performClick()
        loaded()
        compose.onNodeWithContentDescription("Embedded guitar tuner").assertIsDisplayed()
        compose.onNodeWithText("Back to tab").performClick()
        compose.onNodeWithContentDescription(tabDescription).assertIsDisplayed()
        compose.runOnIdle { assertEquals(savedId, vm.library.value.currentSongId); assertNull(tuner.webView) }
        val restored = compose.onNodeWithContentDescription(tabDescription).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(position, restored, .1f)
    }

    @Test fun onlineTabRequestSurvivesTunerAndExplicitVoiceSearchDoesNotRestoreAStaleTab() {
        val vm = viewModel()
        compose.setContent { Text("Navigation state test") }
        compose.runOnIdle {
            vm.setQuery("Nutshell"); vm.setArtistQuery("Alice in Chains"); vm.searchTabs()
            val original = vm.screen.value.webTab
            vm.navigate(Destination.TUNER)
            assertNull(vm.screen.value.webTab)
            vm.navigate(Destination.TUNER) // Re-selecting the rail item must not lose the suspended tab.
            vm.closeTuner()
            assertEquals(original, vm.screen.value.webTab)
            vm.navigate(Destination.TUNER)
            vm.voice("Find tabs for Etude by Bach")
            assertEquals("Etude", vm.screen.value.query)
            assertNull(vm.screen.value.webTab)
            assertEquals(Destination.SEARCH, vm.screen.value.destination)
        }
    }
}
