package org.opentabs.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.view.inputmethod.InputMethodManager
import org.junit.Rule
import org.junit.Test

class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun simplifiedSearchKeepsSavedExercisesAvailableInTheNativeViewer() {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithContentDescription("Search tabs").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Search tabs").performClick()
        compose.onNodeWithText("Find guitar tabs").assertIsDisplayed()
        compose.onNodeWithText("My Tabs · saved six-line tablature").performScrollTo().performClick()
        compose.onNodeWithText("Search local tabs").performTextInput("Evening")
        compose.runOnUiThread {
            compose.activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Evening arpeggios").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Evening arpeggios").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("NOW PLAYING · LOCAL TAB").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("NOW PLAYING · LOCAL TAB").assertIsDisplayed()
        compose.onNodeWithText("Auto-scroll").assertIsDisplayed()
    }
}
