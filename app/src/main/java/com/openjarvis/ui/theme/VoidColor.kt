
package com.openjarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object VoidColor {
    val Void600 = Color(0xFF343044)
    val Void700 = Color(0xFF282435)
    val Void800 = Color(0xFF201D2B)
    val Void900 = Color(0xFF181622)
    val Void950 = Color(0xFF100E18)

    val Violet = Color(0xFF9B7BFF)
    val VioletDim = Color(0xFF6045A8)

    val TextPrimary = Color(0xFFF5F2FF)
    val TextSecondary = Color(0xFFB8B2C8)
    val TextDisabled = Color(0xFF777184)

    val BorderSubtle = Color(0xFF353044)
    val BorderGlow = Color(0xFF8062D9)

    val Green = Color(0xFF45D6A0)
    val Red = Color(0xFFFF5D75)
    val Amber = Color(0xFFFFC266)
    val Cyan = Color(0xFF54D9E8)
}

private val VoidDarkColors = darkColorScheme(
    primary = VoidColor.Violet,
    secondary = VoidColor.Cyan,
    background = VoidColor.Void950,
    surface = VoidColor.Void900,
    onPrimary = Color.White,
    onBackground = VoidColor.TextPrimary,
    onSurface = VoidColor.TextPrimary
)

@Composable
fun OpenJarvisTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VoidDarkColors,
        content = content
    )
}
