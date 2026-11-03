package cn.aclin.typebridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6BE6),
    onPrimary = Color.White,
    secondary = Color(0xFF4F7A5B),
    error = Color(0xFFC62828)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB8FF),
    onPrimary = Color(0xFF0A2A66),
    secondary = Color(0xFFA7D3AF),
    error = Color(0xFFFF9A9A)
)

@Composable
fun TypeBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
