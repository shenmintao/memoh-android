package icu.minq.memoh.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(primary = Color(0xFF4F5BA6), secondary = Color(0xFF5A5D72), tertiary = Color(0xFF76546D))
private val Dark = darkColorScheme(primary = Color(0xFFBCC2FF), secondary = Color(0xFFC3C5DC), tertiary = Color(0xFFE5BAD7))

@Composable fun MemohTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
