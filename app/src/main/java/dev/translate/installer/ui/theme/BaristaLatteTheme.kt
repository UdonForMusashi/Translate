package dev.translate.installer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Espresso = Color(0xFF181411)
val RoastedCoffee = Color(0xFF1C1814)
val CafeBackground = Color(0xFF2A2320)
val CafeSurface = Color(0xFF241E1A)
val CafeSurfaceRaised = Color(0xFF302824)
val CafeLine = Color(0xFF463B34)
val LatteGold = Color(0xFFD4A373)
val LatteGoldBright = Color(0xFFE7BE91)
val LatteCream = Color(0xFFF0ECE5)
val CafeMuted = Color(0xFFA99B8F)
val CafeRose = Color(0xFFC98A86)
val CafeSage = Color(0xFF9CAA8B)

private val BaristaColors = darkColorScheme(
    primary = LatteGold,
    onPrimary = Color(0xFF321F10),
    primaryContainer = Color(0xFF4B3523),
    onPrimaryContainer = Color(0xFFFFDDBA),
    secondary = CafeRose,
    onSecondary = Color(0xFF3D2020),
    secondaryContainer = Color(0xFF563534),
    onSecondaryContainer = Color(0xFFFFDAD7),
    tertiary = CafeSage,
    onTertiary = Color(0xFF1E291B),
    background = Espresso,
    onBackground = LatteCream,
    surface = CafeSurface,
    onSurface = LatteCream,
    surfaceVariant = CafeSurfaceRaised,
    onSurfaceVariant = CafeMuted,
    outline = Color(0xFF76685E),
    outlineVariant = CafeLine,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val BaristaTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun BaristaLatteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BaristaColors,
        typography = BaristaTypography,
        content = content,
    )
}
