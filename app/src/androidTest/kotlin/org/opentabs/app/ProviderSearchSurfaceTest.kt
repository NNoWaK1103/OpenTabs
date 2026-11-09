package org.opentabs.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.opentabs.core.WebTabSource

/** Source selection is tested without making any live network request. */
class ProviderSearchSurfaceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typingDoesNotNavigateAndEachSourceCardUsesTheCurrentQuery() {
        var state by mutableStateOf(ProviderTabsState())
        compose.setContent { OpenTabsTheme {
            ProviderSearchScreen(state.query, { state = state.withQuery(it) }, { state = state.browse(it) }, {})
        } }
        compose.onNodeWithText("Song or artist").performTextInput("Nutshell")
        compose.onNodeWithText("Song or artist").performImeAction()
        compose.runOnIdle { assertNull(state.webTab) }
        compose.onNodeWithText("Search Songsterr").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(WebTabSource.SONGSTERR.searchUrl("Nutshell", ""), state.webTab!!.url)
        }
        compose.onNodeWithText("Search Ultimate Guitar").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(WebTabSource.ULTIMATE_GUITAR.searchUrl("Nutshell", ""), state.webTab!!.url)
        }
    }

    @Test fun compactSurfaceKeepsBothSourcesAndSavingExplanationReachable() {
        var selected: WebTabSource? = null
        var savedLinks = false
        compose.setContent { OpenTabsTheme {
            Box(Modifier.requiredWidth(360.dp)) {
                ProviderSearchScreen("", {}, { selected = it }, { savedLinks = true })
            }
        } }
        compose.onNodeWithText("Browse Songsterr").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(WebTabSource.SONGSTERR, selected) }
        compose.onNodeWithText("Browse Ultimate Guitar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(WebTabSource.ULTIMATE_GUITAR, selected) }
        compose.onNodeWithText("How saving works").performScrollTo().performClick()
        compose.onNode(hasText("Internet required;", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Saved links").performClick()
        compose.runOnIdle { assertTrue(savedLinks) }
    }
}
