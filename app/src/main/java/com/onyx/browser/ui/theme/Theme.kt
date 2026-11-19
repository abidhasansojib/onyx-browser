package com.onyx.browser.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = OnyxPrimary,
    onPrimary = OnyxOnPrimary,
    primaryContainer = OnyxPrimaryVariant,
    onPrimaryContainer = OnyxOnPrimary,
    secondary = OnyxPrimary,
    onSecondary = OnyxOnPrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkOutline
)

private val AmoledColorScheme = darkColorScheme(
    primary = OnyxPrimary,
    onPrimary = OnyxOnPrimary,
    primaryContainer = OnyxPrimaryVariant,
    onPrimaryContainer = OnyxOnPrimary,
    secondary = OnyxPrimary,
    onSecondary = OnyxOnPrimary,
    background = AmoledBackground,
    onBackground = DarkTextPrimary,
    surface = AmoledSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = AmoledSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = AmoledOutline
)

private val LightColorScheme = lightColorScheme(
    primary = OnyxPrimary,
    onPrimary = OnyxOnPrimary,
    primaryContainer = OnyxPrimaryVariant,
    onPrimaryContainer = OnyxOnPrimary,
    secondary = OnyxPrimary,
    onSecondary = OnyxOnPrimary,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline
)

@Composable
fun OnyxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isAmoled: Boolean = false,
    dynamicColor: Boolean = false,
    accentColor: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isAmoled && darkTheme -> AmoledColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = if (accentColor != null) {
        baseScheme.copy(
            primary = accentColor,
            secondary = accentColor
        )
    } else {
        baseScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OnyxTypography,
        shapes = OnyxShapes,
        content = content
    )
}
