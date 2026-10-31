package org.opentabs.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import org.opentabs.core.LicensedHttpCache
import org.opentabs.core.TabForgeProvider
import java.io.File
import java.net.URLEncoder

class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchesAndSearchesOriginalExercises() {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithContentDescription("Search tabs").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Search tabs").performClick()
        compose.onNodeWithContentDescription("Search online sources").performClick()
        compose.onNodeWithText("Title or keywords").performTextInput("Evening")
        compose.onNodeWithText("Title or keywords").performImeAction()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Evening arpeggios").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Open tab").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Open tab").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("NOW PLAYING · LOCAL TAB").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("NOW PLAYING · LOCAL TAB").assertIsDisplayed()
        compose.onNodeWithText("Auto-scroll").assertIsDisplayed()
    }

    @Test fun licensedOnlineArrangementSavesAndOpensInNativeViewer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cache = LicensedHttpCache(File(instrumentation.targetContext.cacheDir, "licensed-search"))
        // Deterministic licensed source and empty metadata fixtures; production providers and parser are unchanged.
        cache.write(TabForgeProvider.URL, instrumentation.context.assets.open("tabforge-ashen-meridian.gp").use { it.readBytes() })
        val metadataUrl = "https://musicbrainz.org/ws/2/recording/?query=${URLEncoder.encode("(ashen* OR ashen~1) AND (meridian* OR meridian~1)", "UTF-8")}&fmt=json&limit=25"
        cache.write(metadataUrl, """{"recordings":[]}""".toByteArray())
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodesWithContentDescription("Search tabs").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search tabs").performClick()
        compose.onNodeWithText("Title or keywords").performTextInput("Ashen Meridian")
        compose.onNodeWithText("Title or keywords").performImeAction()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithContentDescription("Details for Ashen Meridian, Lead Gtr").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Details for Ashen Meridian, Lead Gtr").performClick()
        compose.onNodeWithText("Save & open").performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithContentDescription("Six-string guitar tablature. Swipe vertically or horizontally to scroll.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Source & license").assertIsDisplayed()
        compose.onAllNodesWithText("Ashen Meridian").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Auto-scroll").assertIsDisplayed()
    }
}
