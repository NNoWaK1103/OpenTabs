package org.opentabs.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OpenTabsColors = darkColorScheme(
    primary = Color(0xFFB9F176), onPrimary = Color(0xFF192B0C),
    secondary = Color(0xFFBFCDB0), background = Color(0xFF101317),
    surface = Color(0xFF181D23), surfaceVariant = Color(0xFF282F36),
    onBackground = Color(0xFFEBEFE7), onSurface = Color(0xFFEBEFE7),
    onSurfaceVariant = Color(0xFFB4BDB3), outline = Color(0xFF566151),
)

@Composable
fun OpenTabsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OpenTabsColors, content = content)
}
