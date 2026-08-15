package com.apksherlock.roadwave.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RoadwaveLightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = AccentLightOn,
    background = LightBackground,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightMuted,
    outline = LightLine,
    outlineVariant = LightLine,
    error = LightDanger,
    onError = Color.White,
    inverseSurface = LightInk,
    inverseOnSurface = LightBackground
)

private val RoadwaveDarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = AccentDarkOn,
    background = DarkBackground,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkMuted,
    outline = DarkLine,
    outlineVariant = DarkLine,
    error = DarkDanger,
    onError = Color.White,
    inverseSurface = DarkInk,
    inverseOnSurface = DarkBackground
)

@Composable
fun RoadwaveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) RoadwaveDarkColorScheme else RoadwaveLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
