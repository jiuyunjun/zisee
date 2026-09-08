package com.zisee.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD4D6), onPrimary = Color(0xFF071518),
    background = Color(0xFF0B0F12), onBackground = Color(0xFFE8EDF0),
    surface = Color(0xFF12181D), onSurface = Color(0xFFE8EDF0),
    surfaceVariant = Color(0xFF1E2A31), onSurfaceVariant = Color(0xFF98A6AE),
    error = Color(0xFFFF8F93),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696C), onPrimary = Color.White,
    background = Color(0xFFF5F8F9), onBackground = Color(0xFF18252B),
    surface = Color.White, onSurface = Color(0xFF18252B),
    surfaceVariant = Color(0xFFE5ECEF), onSurfaceVariant = Color(0xFF465A64),
)

@Composable
fun ZiseeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(26.dp),
        ),
        content = content,
    )
}
