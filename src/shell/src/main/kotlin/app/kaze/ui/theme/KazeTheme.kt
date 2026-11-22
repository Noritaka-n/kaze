package app.kaze.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF3B6559),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EEE5),
    onPrimaryContainer = Color(0xFF173A30),
    secondary = Color(0xFF6B8E80),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D2420),
    surfaceVariant = Color(0xFFEAF0EB),
    onSurfaceVariant = Color(0xFF5E6A62),
    outline = Color(0xFFB8C4BC),
    outlineVariant = Color(0xFFDCE4DD),
    background = Color(0xFFF4F6F2),
    onBackground = Color(0xFF1D2420),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9D5C3),
    onPrimary = Color(0xFF10261E),
    primaryContainer = Color(0xFF25453B),
    onPrimaryContainer = Color(0xFFD9F0E6),
    secondary = Color(0xFF91B7A7),
    onSecondary = Color(0xFF10251E),
    secondaryContainer = Color(0xFF283C33),
    onSecondaryContainer = Color(0xFFD9E9E1),
    surface = Color(0xFF242A2E),
    onSurface = Color(0xFFE7EEF0),
    surfaceVariant = Color(0xFF2F373B),
    onSurfaceVariant = Color(0xFFADBBC0),
    outline = Color(0xFF4D5960),
    outlineVariant = Color(0xFF3D484E),
    background = Color(0xFF1A1E21),
    onBackground = Color(0xFFE7EEF0),
    error = Color(0xFFFFB4AB),
)

private val KazeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val KazeType = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
)

@Composable
fun KazeTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = KazeShapes,
        typography = KazeType,
        content = content,
    )
}
