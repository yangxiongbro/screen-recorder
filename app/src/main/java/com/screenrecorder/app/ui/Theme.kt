package com.screenrecorder.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RecordRed = Color(0xFFE5484D)

private val DarkColors = darkColorScheme(
    primary = RecordRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1417),
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = Color(0xFF8FB4FF),
    onSecondary = Color(0xFF0F2038),
    secondaryContainer = Color(0xFF1B2E4A),
    onSecondaryContainer = Color(0xFFD7E4FF),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE7ECF3),
    surface = Color(0xFF10161E),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF19212C),
    onSurfaceVariant = Color(0xFFA6B3C5),
    surfaceTint = Color(0xFF2C3947),
    surfaceContainerLowest = Color(0xFF0A0F14),
    surfaceContainerLow = Color(0xFF0E141B),
    surfaceContainer = Color(0xFF121A23),
    surfaceContainerHigh = Color(0xFF18222D),
    surfaceContainerHighest = Color(0xFF1E2937),
    outline = Color(0xFF39434F),
    outlineVariant = Color(0xFF232D3A),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = RecordRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD8),
    onPrimaryContainer = Color(0xFF410004),
    secondary = Color(0xFF2C5D9E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E9F4),
    onSecondaryContainer = Color(0xFF16304F),
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF161B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161B22),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF4A5563),
    surfaceTint = Color(0xFFDDE3EB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFEFF2F6),
    surfaceContainerHigh = Color(0xFFE8ECF1),
    surfaceContainerHighest = Color(0xFFE1E6EC),
    outline = Color(0xFFC2C9D2),
    outlineVariant = Color(0xFFDDE2E8),
)

@Composable
fun ScreenRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
