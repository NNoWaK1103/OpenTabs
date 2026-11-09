package org.opentabs.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The production launch path must expose only the two providers, never a native library. */
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchesWithOnlyTheTwoProvidersAndNoNativeSurfaces() {
        compose.onNodeWithText("OpenTabs").assertIsDisplayed()
        compose.onNodeWithText("Song or artist").assertIsDisplayed()
        compose.onNodeWithText("Songsterr", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Ultimate Guitar", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Saved links").assertIsDisplayed()
        listOf("My Tabs", "Practice", "Settings", "Now Playing", "Import", "Native tabs", "Search online",
            "MusicBrainz", "TabForge", "Spotify", "Shazam", "Evening arpeggios", "Play audio").forEach {
            compose.onNodeWithText(it, substring = false).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("Microphone and voice commands").assertDoesNotExist()
        compose.onNodeWithContentDescription("Embedded tab website").assertDoesNotExist()
    }

    @Test fun querySurvivesActivityRecreationAndCanBeCleared() {
        compose.onNodeWithText("Song or artist").performTextInput("Nutshell Alice in Chains")
        compose.onNodeWithText("Song or artist").performImeAction()
        compose.onNodeWithText("Search Songsterr").assertIsDisplayed()
        compose.onNodeWithText("Search Ultimate Guitar").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Song or artist").assertTextContains("Nutshell Alice in Chains")
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Browse Songsterr").assertIsDisplayed()
        compose.onNodeWithText("Browse Ultimate Guitar").assertIsDisplayed()
        lateinit var vm: ProviderTabsViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[ProviderTabsViewModel::class.java] }
        assertEquals("", vm.screen.value.query)
        assertNull(vm.screen.value.webTab)
    }

    @Test fun savedLinksAreReachableBeforeOpeningAnyWebsiteAndDoNotClaimOfflineTabs() {
        compose.onNodeWithText("Saved links").performClick()
        compose.onNodeWithText("Saved website links").assertIsDisplayed()
        compose.onNode(hasText("Internet required. These are links", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Song or artist").assertIsDisplayed()
        compose.onNodeWithContentDescription("Embedded tab website").assertDoesNotExist()
        compose.onNodeWithText("Saved links").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Saved website links").assertDoesNotExist()
    }
}
