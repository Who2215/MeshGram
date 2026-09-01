package com.meshchat.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.meshchat.app.R

private val MeshColorScheme = darkColorScheme(
    primary = Color(0xFF22F7EA),
    onPrimary = Color(0xFF061014),
    secondary = Color(0xFFFF4FF0),
    onSecondary = Color(0xFF130716),
    tertiary = Color(0xFF8E5CFF),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFF05070D),
    onBackground = Color(0xFFEAFDFF),
    surface = Color(0xFF151A24),
    onSurface = Color(0xFFEAFDFF),
    surfaceVariant = Color(0xFF202637),
    onSurfaceVariant = Color(0xFF9BA9BB)
)

private val MeshFontFamily = FontFamily(Font(R.font.manrope_variable))

private val MeshTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = MeshFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = MeshFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = MeshFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = MeshFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = MeshFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = MeshFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = MeshFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp
    )
)

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshColorScheme,
        typography = MeshTypography,
        content = content
    )
}
