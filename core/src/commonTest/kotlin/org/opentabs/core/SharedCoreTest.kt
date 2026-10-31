package org.opentabs.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SharedCoreTest {
    @Test fun catalogPreservesAndroidExerciseIdentifiersAndText() {
        val exercises = ExerciseCatalog().exercises()
        assertEquals(listOf("example-0", "example-1", "example-2"), exercises.map { it.id })
        assertEquals(Examples.exercises.map { it.second }, exercises.map { it.text })
        assertTrue(exercises.all { it.text.contains("e|") && it.text.contains("E|") })
    }

    @Test fun settingsRetainStoredKeysAndClampUnsafeValues() {
        val settings = Json.decodeFromString<ViewerSettings>(
            """{"fontSize":100,"lineSpacing":-1,"scrollSpeed":0,"keepAwake":false}"""
        ).sanitized()
        assertEquals(ViewerSettings(32, 0, 5, false), settings)
        assertEquals(settings, Json.decodeFromString<ViewerSettings>(Json.encodeToString(settings)))
    }

    @Test fun voiceParsingIsLocaleIndependentAndRejectsEmptySearches() {
        assertEquals(VoiceCommand.IncreaseSize, VoiceCommandParser.parse("INCREASE TAB SIZE!"))
        assertEquals(VoiceCommand.Find("evening ada"), VoiceCommandParser.parse("Find tabs for Evening by Ada."))
        assertEquals(VoiceCommand.Unknown, VoiceCommandParser.parse("find tabs for"))
    }
}
