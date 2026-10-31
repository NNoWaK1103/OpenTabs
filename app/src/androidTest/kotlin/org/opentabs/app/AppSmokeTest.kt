package org.opentabs.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchesAndSearchesOriginalExercises() {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithContentDescription("Search local tabs").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Search local tabs").performClick()
        compose.onNodeWithText("Search local tabs").performTextInput("Evening")
        compose.onNodeWithText("Evening arpeggios").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("NOW PLAYING · LOCAL TAB").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("NOW PLAYING · LOCAL TAB").assertIsDisplayed()
        compose.onNodeWithText("Auto-scroll").assertIsDisplayed()
    }
}
