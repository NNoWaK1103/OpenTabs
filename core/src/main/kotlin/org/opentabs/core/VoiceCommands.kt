package org.opentabs.core

sealed interface VoiceCommand {
    data class Find(val query: String) : VoiceCommand
    data object Favorites : VoiceCommand
    data object Practice : VoiceCommand
    data object StartScroll : VoiceCommand
    data object StopScroll : VoiceCommand
    data object IncreaseSize : VoiceCommand
    data object DecreaseSize : VoiceCommand
    data object Unknown : VoiceCommand
}

object VoiceCommandParser {
    fun parse(spoken: String): VoiceCommand {
        val text = spoken.trim().trimEnd('.', '!', '?').lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\s+"), " ")
        if (text in setOf("find", "find tabs for", "search for")) return VoiceCommand.Unknown
        return when (text) {
            "open my favorites", "open favorites", "show my favorites" -> VoiceCommand.Favorites
            "show my practice list", "open my practice list", "show practice list" -> VoiceCommand.Practice
            "start scrolling", "resume scrolling" -> VoiceCommand.StartScroll
            "stop scrolling", "pause scrolling" -> VoiceCommand.StopScroll
            "increase tab size", "make tabs bigger" -> VoiceCommand.IncreaseSize
            "decrease tab size", "make tabs smaller" -> VoiceCommand.DecreaseSize
            else -> {
                val match = Regex("^(?:find tabs for|search for|find) (.+)$").matchEntire(text)
                val query = match?.groupValues?.get(1)?.replace(Regex("\\s+by\\s+"), " ")?.trim()
                if (query.isNullOrBlank()) VoiceCommand.Unknown else VoiceCommand.Find(query)
            }
        }
    }
}
