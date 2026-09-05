package icu.minq.memoh.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Memoh's violet brand, neutral reading plane and lightly tinted navigation plane.
private val Light = lightColorScheme(
    primary = Color(0xFF764BE5), onPrimary = Color.White,
    primaryContainer = Color(0xFFEEE5FE), onPrimaryContainer = Color(0xFF281B3C),
    secondary = Color(0xFF706A7B), secondaryContainer = Color(0xFFF0ECF6), onSecondaryContainer = Color(0xFF39343F),
    tertiary = Color(0xFF3B8066), background = Color.White, onBackground = Color(0xFF191816),
    surface = Color.White, surfaceBright = Color.White, onSurface = Color(0xFF191816), surfaceVariant = Color(0xFFF6F5F7),
    onSurfaceVariant = Color(0xFF6A6965), surfaceContainer = Color(0xFFF8F7F9),
    surfaceContainerLow = Color(0xFFFAF8F7), surfaceContainerHigh = Color(0xFFF0EEF3),
    outline = Color(0xFFABA6B1), outlineVariant = Color(0xFFE5E2E0),
    error = Color(0xFFB83E4B), errorContainer = Color(0xFFFFEEF0), onErrorContainer = Color(0xFF862C39),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA490FF), onPrimary = Color(0xFF241446),
    primaryContainer = Color(0xFF392B51), onPrimaryContainer = Color(0xFFF1E8FF),
    secondary = Color(0xFFB8B0C2), secondaryContainer = Color(0xFF302B37), onSecondaryContainer = Color(0xFFE7DFEF),
    tertiary = Color(0xFF8DCDB0), background = Color(0xFF0C0C0C), onBackground = Color(0xFFDEDEDE),
    surface = Color(0xFF0C0C0C), surfaceBright = Color(0xFF181818), onSurface = Color(0xFFDEDEDE), surfaceVariant = Color(0xFF252329),
    onSurfaceVariant = Color(0xFF9E9E9E), surfaceContainer = Color(0xFF201E24),
    surfaceContainerLow = Color(0xFF131313), surfaceContainerHigh = Color(0xFF2C2931),
    outline = Color(0xFF77717F), outlineVariant = Color(0xFF36323D),
    error = Color(0xFFFFA6AF), errorContainer = Color(0xFF41252C), onErrorContainer = Color(0xFFFFDADF),
)
private val Type = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
)
@Composable fun MemohTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Type,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(24.dp)), content = content)
}
