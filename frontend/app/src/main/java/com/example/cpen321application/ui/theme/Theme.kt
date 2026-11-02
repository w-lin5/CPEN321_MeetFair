package com.example.cpen321application.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    // Brand primaries
    primary          = TealPrimary,
    onPrimary        = Color.White,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealPrimary,

    // Rose as the secondary / destructive accent
    secondary        = RosePrimary,
    onSecondary      = Color.White,

    // Backgrounds & surfaces use the warm cream palette
    background       = MeetFairCream,
    onBackground     = Color(0xFF1A1C18),
    surface          = MeetFairCream,
    onSurface        = Color(0xFF1A1C18),
    surfaceVariant   = TealContainer,
    onSurfaceVariant = Color(0xFF3D4F52),

    // Standard Material 3 error red
    error            = Color(0xFFBA1A1A),
    onError          = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    // Lighter teal so it reads on dark backgrounds
    primary          = TealLight,
    onPrimary        = Color(0xFF00363A),
    primaryContainer = TealPrimary,
    onPrimaryContainer = TealContainer,

    secondary        = RoseLight,
    onSecondary      = Color(0xFF5C1414),

    background       = Color(0xFF1A1C18),
    onBackground     = Color(0xFFE2E3DC),
    surface          = Color(0xFF1A1C18),
    onSurface        = Color(0xFFE2E3DC),
    surfaceVariant   = Color(0xFF3D4F52),
    onSurfaceVariant = Color(0xFFBBCBCE),

    error            = Color(0xFFFFB4AB),
    onError          = Color(0xFF690005),
)

@Composable
fun CPEN321ApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic colour (Android 12+) pulls from the user's wallpaper.
    // On older devices the MeetFair brand schemes above are used instead.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
