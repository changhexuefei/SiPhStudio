package org.jason.siph.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tesla / SpaceX inspired visual tokens.
 *
 * The palette intentionally avoids brand assets and logos. It focuses on the
 * shared industrial characteristics: black glass surfaces, precise borders,
 * restrained typography and high-contrast telemetry accents.
 */
object AerospacePalette {
    val Void = Color(0xFF05070A)
    val Background = Color(0xFF080B10)
    val Panel = Color(0xFF0D1117)
    val PanelRaised = Color(0xFF121821)
    val PanelHover = Color(0xFF17202B)
    val Border = Color(0xFF26313E)
    val BorderStrong = Color(0xFF3A4A5C)
    val Grid = Color(0x142A3948)

    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFF98A6B7)
    val TextMuted = Color(0xFF657386)

    val Accent = Color(0xFF3C9DFF)
    val AccentBright = Color(0xFF75BCFF)
    val AccentContainer = Color(0xFF102B45)

    val Success = Color(0xFF39D98A)
    val SuccessContainer = Color(0xFF0D2A20)
    val Warning = Color(0xFFFFB547)
    val WarningContainer = Color(0xFF30220E)
    val Danger = Color(0xFFFF5C67)
    val DangerContainer = Color(0xFF351218)
}

private val AerospaceColorScheme = darkColorScheme(
    primary = AerospacePalette.Accent,
    onPrimary = Color(0xFF001B2E),
    primaryContainer = AerospacePalette.AccentContainer,
    onPrimaryContainer = AerospacePalette.AccentBright,
    secondary = Color(0xFFAEBBCC),
    onSecondary = Color(0xFF111820),
    secondaryContainer = AerospacePalette.PanelHover,
    onSecondaryContainer = AerospacePalette.TextPrimary,
    tertiary = AerospacePalette.Warning,
    onTertiary = Color(0xFF241600),
    tertiaryContainer = AerospacePalette.WarningContainer,
    onTertiaryContainer = Color(0xFFFFD89B),
    background = AerospacePalette.Background,
    onBackground = AerospacePalette.TextPrimary,
    surface = AerospacePalette.Panel,
    onSurface = AerospacePalette.TextPrimary,
    surfaceVariant = AerospacePalette.PanelRaised,
    onSurfaceVariant = AerospacePalette.TextSecondary,
    outline = AerospacePalette.Border,
    outlineVariant = AerospacePalette.BorderStrong,
    error = AerospacePalette.Danger,
    onError = Color.White,
    errorContainer = AerospacePalette.DangerContainer,
    onErrorContainer = Color(0xFFFFB3B8),
    scrim = Color.Black
)

private val AerospaceTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 48.sp,
        letterSpacing = 1.2.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        letterSpacing = 0.6.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        letterSpacing = 0.4.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.8.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.4.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.15.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.9.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.7.sp
    )
)

private val AerospaceShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp)
)

@Composable
fun AerospaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AerospaceColorScheme,
        typography = AerospaceTypography,
        shapes = AerospaceShapes,
        content = content
    )
}
