package talq.app.theme

import talq.app.data.ThemeChoice

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Maps TalQ's tokens onto Material 3 roles, so stock components come out in palette. */
private fun TalqColors.toScheme(): ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accentSoftInk,
        inversePrimary = accent,
        secondary = textSecondary,
        onSecondary = bgPrimary,
        secondaryContainer = bgSelected,
        onSecondaryContainer = textPrimary,
        tertiary = amber,
        onTertiary = if (isLight) bgSurface else controlInk,
        tertiaryContainer = bgSelected,
        onTertiaryContainer = textPrimary,
        background = bgPrimary,
        onBackground = textPrimary,
        surface = bgPrimary,
        onSurface = textPrimary,
        surfaceVariant = bgSecondary,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = textPrimary,
        inverseOnSurface = bgPrimary,
        error = danger,
        onError = if (isLight) bgSurface else controlInk,
        errorContainer = bgSecondary,
        onErrorContainer = danger,
        outline = textMuted,
        outlineVariant = divider,
        scrim = Color.Black,
        surfaceBright = bgSurface,
        surfaceDim = bgSidebar,
        surfaceContainerLowest = bgSidebar,
        surfaceContainerLow = if (isLight) bgSurface else bgSecondary,
        surfaceContainer = bgSecondary,
        surfaceContainerHigh = if (isLight) bgSurface else bgSurface,
        surfaceContainerHighest = bgSelected,
    )
}

/**
 * TalQ desktop's five tiers (Display 25, Title 15, Body 14, Label 12, Caption 11 px at desktop
 * density) lifted one or two steps for a phone held at arm's length. The system sans stands
 * in for Inter. No faked headlines: a bigger tier is a real tier.
 */
private fun talqTypography(): Typography {
    val sans = FontFamily.Default
    fun style(size: Int, weight: FontWeight, line: Int, spacing: Double = 0.0) = TextStyle(
        fontFamily = sans,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = line.sp,
        letterSpacing = spacing.sp,
    )
    val display = style(28, FontWeight.SemiBold, 34, -0.3)
    val title = style(17, FontWeight.SemiBold, 22)
    val body = style(15, FontWeight.Normal, 21)
    val label = style(13, FontWeight.SemiBold, 18)
    val caption = style(12, FontWeight.Normal, 16, 0.2)
    return Typography(
        displayLarge = display, displayMedium = display, displaySmall = display,
        headlineLarge = display, headlineMedium = display,
        headlineSmall = style(22, FontWeight.SemiBold, 28, -0.2),
        titleLarge = style(20, FontWeight.SemiBold, 26, -0.1),
        titleMedium = title,
        titleSmall = style(15, FontWeight.SemiBold, 20),
        bodyLarge = style(16, FontWeight.Normal, 23),
        bodyMedium = body,
        bodySmall = style(13, FontWeight.Normal, 18),
        labelLarge = style(14, FontWeight.SemiBold, 20),
        labelMedium = label,
        labelSmall = caption,
    )
}

/** TalQ desktop's radii: sm 6, control 8, md 10, card 13. */
private val TalqShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(13.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** The colours for [choice]; following the phone means Vivid when it is dark, Paper when it is light. */
@Composable
fun colorsFor(choice: ThemeChoice): TalqColors = when (choice) {
    ThemeChoice.System -> if (isSystemInDarkTheme()) VividColors else PaperColors
    ThemeChoice.Ember -> EmberColors
    ThemeChoice.Warm -> WarmColors
    ThemeChoice.Vivid -> VividColors
    ThemeChoice.Paper -> PaperColors
}

@Composable
fun TalqTheme(choice: ThemeChoice = ThemeChoice.System, brand: Int? = null, content: @Composable () -> Unit) {
    // An organisation's colour (organisation mode), fitted to this theme; none = the palette as it is.
    val colors = colorsFor(choice).withBrand(brand)
    CompositionLocalProvider(LocalTalqColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toScheme(),
            typography = talqTypography(),
            shapes = TalqShapes,
            content = content,
        )
    }
}

/** `TalqTheme.colors.accentText` etc. for tokens Material has no role for. */
object TalqTheme {
    val colors: TalqColors
        @Composable @ReadOnlyComposable get() = LocalTalqColors.current
}
