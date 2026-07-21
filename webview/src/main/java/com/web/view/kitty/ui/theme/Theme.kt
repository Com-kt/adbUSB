package com.web.view.kitty.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.Keep

@Keep
private val DarkColorScheme = darkColorScheme(
    primary = PinkPrimary80,
    onPrimary = OnPinkPrimary80,
    primaryContainer = PinkContainer80,
    onPrimaryContainer = OnPinkContainer80,
    secondary = PinkSecondary80,
    tertiary = PinkTertiary80,
    background = PinkBackground80,
    surface = PinkSurface80,
    onBackground = OnPinkBackground80,
    onSurface = OnPinkSurface80,
    surfaceVariant = PinkSurfaceVariant80,
    onSurfaceVariant = OnPinkSurfaceVariant80,
    surfaceContainer = PinkSurfaceContainer80, 
    surfaceContainerHigh = PinkSurfaceContainerHigh80,
    surfaceContainerLow = PinkSurfaceContainerLow80
)

@Keep
private val LightColorScheme = lightColorScheme(
    primary = PinkPrimary40,
    onPrimary = OnPinkPrimary40,
    primaryContainer = PinkContainer40,
    onPrimaryContainer = OnPinkContainer40,
    secondary = PinkSecondary40,
    tertiary = PinkTertiary40,
    background = PinkBackground40,
    surface = PinkSurface40,
    onBackground = OnPinkBackground40,
    onSurface = OnPinkSurface40,
    surfaceVariant = PinkSurfaceVariant40,
    onSurfaceVariant = OnPinkSurfaceVariant40,
    surfaceContainer = PinkSurfaceContainer40, 
    surfaceContainerHigh = PinkSurfaceContainerHigh40,
    surfaceContainerLow = PinkSurfaceContainerLow40
)

@Keep
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NekoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}