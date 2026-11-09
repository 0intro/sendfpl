package app.sendfpl.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A blue in the spirit of a cockpit, used only when the platform cannot supply a dynamic scheme.
private val Navy = Color(0xFF0B3D5C)
private val Sky = Color(0xFF7FD1FF)

private val DarkScheme = darkColorScheme(primary = Sky, secondary = Sky)
private val LightScheme = lightColorScheme(primary = Navy, secondary = Navy)

@Composable
fun SendFplTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Material You where available, since the app has no brand of its own worth insisting on.
    val colors = platformColorScheme(darkTheme) ?: if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(colorScheme = colors, content = content)
}
