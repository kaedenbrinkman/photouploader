package com.photopipeline.app.ui.theme

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

private val LightFallback = lightColorScheme(
    primary = Color(0xFF006A62),
    onPrimary = Color.White,
    secondary = Color(0xFF4A635F),
    tertiary = Color(0xFF3D6373),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFF5DDAD0),
    onPrimary = Color(0xFF003733),
    secondary = Color(0xFFB2CCC7),
    tertiary = Color(0xFFA8CEDC),
)

@Composable
fun PhotoPipelineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
