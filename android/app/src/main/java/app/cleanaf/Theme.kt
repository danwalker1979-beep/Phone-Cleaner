package app.cleanaf

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF12897B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEFEB),
    onPrimaryContainer = Color(0xFF0B3D3A),
    secondary = Color(0xFF0B685D),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF12292E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12292E),
    surfaceVariant = Color(0xFFEAF0EE),
    onSurfaceVariant = Color(0xFF5C726F),
    error = Color(0xFFCB553F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF37C1AC),
    onPrimary = Color(0xFF04211D),
    primaryContainer = Color(0xFF123531),
    onPrimaryContainer = Color(0xFFB7EDE4),
    secondary = Color(0xFF57D3BF),
    background = Color(0xFF0B1618),
    onBackground = Color(0xFFE9F1EF),
    surface = Color(0xFF122529),
    onSurface = Color(0xFFE9F1EF),
    surfaceVariant = Color(0xFF183337),
    onSurfaceVariant = Color(0xFF9AAEAA),
    error = Color(0xFFE67A63),
)

@Composable
fun CleanAfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
