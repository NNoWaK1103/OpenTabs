package org.opentabs.core

import kotlinx.serialization.Serializable

@Serializable
data class ViewerSettings(
    val fontSize: Int = 18,
    val lineSpacing: Int = 6,
    val scrollSpeed: Int = 28,
    val keepAwake: Boolean = true,
) {
    fun sanitized() = copy(
        fontSize = fontSize.coerceIn(12, 32),
        lineSpacing = lineSpacing.coerceIn(0, 20),
        scrollSpeed = scrollSpeed.coerceIn(5, 120),
    )
}

