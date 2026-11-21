package talq.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * TalQ's palette, ported from TalQ for desktop's design system (same author, Apache-2.0:
 * github.com/kalinbogatzevski/talq-desktop, src/painter/PainterTheme.cpp).
 * Dark = "Vivid", light = "Paper". Warm grounds, one teal signal, no cool grays.
 * Every pair that carries text was tuned for WCAG AA on desktop; keep the values together.
 */
@Immutable
data class TalqColors(
    val isLight: Boolean,
    // Grounds: each step up the ladder is a lighter, warmer tint.
    val bgPrimary: Color,
    val bgSidebar: Color,
    val bgSecondary: Color,
    val bgSurface: Color,
    val bgSelected: Color,
    val bgHover: Color,
    val bgMessageOwn: Color,
    val divider: Color,
    // Ink
    val textPrimary: Color,
    val textSecondary: Color,
    /** Timestamps, system messages, muted labels. */
    val textMuted: Color,
    // The one signal
    val accent: Color,
    /** Ink on an accent fill. */
    val controlInk: Color,
    /** The accent when it is painted as text (corrected for contrast on the light theme). */
    val accentText: Color,
    /** Calm accent tint behind a selected chip, and the ink that reads on it. */
    val accentSoft: Color,
    val accentSoftInk: Color,
    // Semantic
    val amber: Color,
    val online: Color,
    val danger: Color,
    // Rich text
    val mention: Color,
    val link: Color,
    val codeBg: Color,
    /** Author identity hues, as text on [bgPrimary]. */
    val authorInks: List<Color>,
) {
    /** Stable identity colour for a name or id. */
    fun authorInk(key: String): Color = authorInks[(key.hashCode() and 0x7fffffff) % authorInks.size]
}

/** Avatar disc fills (white initials go on top). Shared by both themes. */
val AvatarFills = listOf(
    Color(0xFF0D9488), Color(0xFFC25A4B), Color(0xFFB8741F), Color(0xFF3E9A4B),
    Color(0xFF7C5FC0), Color(0xFFC75A90), Color(0xFF2F8FA1), Color(0xFF3F7FB3),
)

val VividColors = TalqColors(
    isLight = false,
    bgPrimary = Color(0xFF271D12),
    bgSidebar = Color(0xFF211810),
    bgSecondary = Color(0xFF312517),
    bgSurface = Color(0xFF3D2E1B),
    bgSelected = Color(0xFF46341D),
    bgHover = Color(0xFF342711),
    bgMessageOwn = Color(0xFF133630),
    divider = Color(0xFF4B3A23),
    textPrimary = Color(0xFFFCF5E7),
    textSecondary = Color(0xFFC8B89A),
    textMuted = Color(0xFFAFA38A),
    accent = Color(0xFF21E3C8),
    controlInk = Color(0xFF06201C),
    accentText = Color(0xFF21E3C8),
    accentSoft = Color(0xFF2E473A),
    accentSoftInk = Color(0xFF21E3C8),
    amber = Color(0xFFFFB84A),
    online = Color(0xFF66DD76),
    danger = Color(0xFFFF9163),
    mention = Color(0xFF14B8A6),
    link = Color(0xFF62A3D1),
    codeBg = Color(0xFF19120A),
    authorInks = listOf(
        Color(0xFF21E3C8), Color(0xFFE07060), Color(0xFFF0A050), Color(0xFF5EC76A),
        Color(0xFFA98CDD), Color(0xFFE87AAE), Color(0xFF50B8C8), Color(0xFF5A9ECF),
    ),
)

val PaperColors = TalqColors(
    isLight = true,
    bgPrimary = Color(0xFFFBF6ED),
    bgSidebar = Color(0xFFF3EDDF),
    bgSecondary = Color(0xFFEFE8D8),
    bgSurface = Color(0xFFFFFDF5),
    bgSelected = Color(0xFFE9E0CD),
    bgHover = Color(0xFFEFE8D8),
    bgMessageOwn = Color(0xFFD7ECE6),
    divider = Color(0xFFE1D8C4),
    textPrimary = Color(0xFF1A1613),
    textSecondary = Color(0xFF5F5A52),
    textMuted = Color(0xFF666055),
    accent = Color(0xFF0D9488),
    controlInk = Color(0xFFFFFDF5),
    accentText = Color(0xFF0C6A5F),
    accentSoft = Color(0xFFC6D9CA),
    accentSoftInk = Color(0xFF0A574E),
    amber = Color(0xFF855615),
    online = Color(0xFF296D33),
    danger = Color(0xFF9E462D),
    mention = Color(0xFF0C6A5F),
    link = Color(0xFF1D5F8A),
    codeBg = Color(0xFFECE2CD),
    authorInks = listOf(
        Color(0xFF0C6A5F), Color(0xFFA84436), Color(0xFF8A5510), Color(0xFF2B6F35),
        Color(0xFF6B4BB0), Color(0xFFAD3F78), Color(0xFF1F6F7E), Color(0xFF2B6594),
    ),
)

val LocalTalqColors = staticCompositionLocalOf { VividColors }
