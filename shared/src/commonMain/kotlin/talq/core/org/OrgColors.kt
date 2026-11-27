package talq.core.org

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * An organisation's colour, fitted to each theme (the spec's "The look"). Pure maths on `0xRRGGBB`
 * Ints, shared by the phone (which maps the result onto its theme tokens) and the owner's issuing
 * tool (which prints what each theme will show before a brand is published).
 *
 * The helpers are TalQ for desktop's (`src/painter/PainterTheme.cpp`): WCAG contrast, `mixColor`, and
 * `readableOn`, which moves only the lightness, in whichever direction reaches the bar with the
 * smaller move, so the hue keeps its identity.
 */

/** WCAG contrast ratio of two opaque colours. */
fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private fun luminance(c: Int): Double {
    fun channel(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel((c shr 16) and 0xFF) + 0.7152 * channel((c shr 8) and 0xFF) + 0.0722 * channel(c and 0xFF)
}

/** Linear blend from [a] to [b] by [t] (desktop's `mixColor`). */
fun mix(a: Int, b: Int, t: Double): Int {
    fun ch(shift: Int): Int {
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        return (x + (y - x) * t).toInt().coerceIn(0, 255)
    }
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * [c] when it already reaches [minRatio] on [ground], else [c] with only its lightness moved, by the
 * smallest step in either direction that reaches it; the most readable version found when none does
 * (desktop's `PainterTheme::readableOn`).
 */
fun readableOn(c: Int, ground: Int, minRatio: Double): Int =
    moveLightness(c) { contrastRatio(it, ground) >= minRatio } ?: run {
        var best = c
        var bestRatio = contrastRatio(c, ground)
        for (i in 1..200) {
            for (l in listOf(hsl(c)[2] - i / 200.0, hsl(c)[2] + i / 200.0)) {
                if (l < 0.0 || l > 1.0) continue
                val cand = withLightness(c, l)
                val r = contrastRatio(cand, ground)
                if (r > bestRatio) {
                    bestRatio = r
                    best = cand
                }
            }
        }
        best
    }

/** The colour nearest to [c] in lightness (same hue and saturation) that [passes], or null. */
private fun moveLightness(c: Int, passes: (Int) -> Boolean): Int? {
    if (passes(c)) return c
    val l = hsl(c)[2]
    for (i in 1..200) {
        val step = i / 200.0
        for (nl in doubleArrayOf(l - step, l + step)) {
            if (nl < 0.0 || nl > 1.0) continue
            val cand = withLightness(c, nl)
            if (passes(cand)) return cand
        }
    }
    return null
}

private fun hsl(c: Int): DoubleArray {
    val r = ((c shr 16) and 0xFF) / 255.0
    val g = ((c shr 8) and 0xFF) / 255.0
    val b = (c and 0xFF) / 255.0
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2
    if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
    val d = mx - mn
    val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + (if (g < b) 6 else 0)) / 6
        g -> ((b - r) / d + 2) / 6
        else -> ((r - g) / d + 4) / 6
    }
    return doubleArrayOf(h, s, l)
}

private fun withLightness(c: Int, l: Double): Int {
    val (h, s) = hsl(c).let { it[0] to it[1] }
    if (s == 0.0) {
        val v = (l * 255 + 0.5).toInt().coerceIn(0, 255)
        return (v shl 16) or (v shl 8) or v
    }
    val q = if (l < 0.5) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    fun hue(tIn: Double): Int {
        var t = tIn
        if (t < 0) t += 1.0
        if (t > 1) t -= 1.0
        val v = when {
            t < 1.0 / 6 -> p + (q - p) * 6 * t
            t < 1.0 / 2 -> q
            t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
            else -> p
        }
        return (v * 255 + 0.5).toInt().coerceIn(0, 255)
    }
    return (hue(h + 1.0 / 3) shl 16) or (hue(h) shl 8) or hue(h - 1.0 / 3)
}

/** Hue distance in degrees (0..180). */
private fun hueDistance(a: Int, b: Int): Double {
    val d = abs(hsl(a)[0] - hsl(b)[0]) * 360
    return min(d, 360 - d)
}

/**
 * The tokens of one theme that an organisation's colour is fitted against.
 * KEEP IN STEP with the four palettes in `app/src/main/kotlin/talq/app/theme/Color.kt`: the tool
 * reports from this copy what the phone will draw from those.
 */
data class ThemeGround(
    val name: String,
    val isLight: Boolean,
    val bgPrimary: Int,
    val bgSecondary: Int,
    val bgSelected: Int,
    val bgMessageOwn: Int,
    val controlInk: Int,
    val textPrimary: Int,
    val accent: Int,
    val accentSoft: Int,
    val mention: Int,
    val danger: Int,
    val amber: Int,
)

val THEME_GROUNDS: List<ThemeGround> = listOf(
    ThemeGround("ember", false, 0x221C16, 0x281F18, 0x382C1F, 0x18312D, 0x0E1817, 0xF6F1E8, 0x1AC2AF, 0x253C33, 0x14B8A6, 0xEC8A64, 0xF3A948),
    ThemeGround("warm", false, 0x241D15, 0x2C2318, 0x3F311E, 0x17332D, 0x0E1817, 0xF7F1E6, 0x1ECDB6, 0x294134, 0x14B8A6, 0xF08A62, 0xF6AD3E),
    ThemeGround("vivid", false, 0x271D12, 0x312517, 0x46341D, 0x133630, 0x06201C, 0xFCF5E7, 0x21E3C8, 0x2E473A, 0x14B8A6, 0xFF9163, 0xFFB84A),
    ThemeGround("paper", true, 0xFBF6ED, 0xEFE8D8, 0xE9E0CD, 0xD7ECE6, 0xFFFDF5, 0x1A1613, 0x0D9488, 0xC6D9CA, 0x0C6A5F, 0x9E462D, 0x855615),
)

/** The accent-derived tokens of one theme, for an organisation's colour. */
data class AccentSet(
    val accent: Int,
    val accentText: Int,
    val accentSoft: Int,
    val accentSoftInk: Int,
    val mention: Int,
    val bgMessageOwn: Int,
)

/** How far along `base -> accent` the theme's own [own] colour sits (least squares over the channels). */
private fun fitT(base: Int, accent: Int, own: Int): Double {
    var num = 0.0
    var den = 0.0
    for (shift in intArrayOf(16, 8, 0)) {
        val b = ((base shr shift) and 0xFF).toDouble()
        val a = ((accent shr shift) and 0xFF).toDouble()
        val o = ((own shr shift) and 0xFF).toDouble()
        num += (o - b) * (a - b)
        den += (a - b) * (a - b)
    }
    return if (den == 0.0) 0.0 else (num / den).coerceIn(0.0, 1.0)
}

/**
 * [brand] fitted to theme [g], or null when no version of it can carry the accent's jobs there (the
 * theme then keeps its own teal).
 * - The fill: the brand colour moved in lightness until it is 3:1 on the ground and its ink (the
 *   theme's control ink or text, whichever contrasts more) is 4.5:1 on it.
 * - As text: 4.5:1 on `bgSelected`, the worst ground it is painted on (as desktop corrects it).
 * - The soft tint, the mention colour and the own-message bubble: mixed from the new accent the way
 *   the theme mixes them from its own, the bubble kept readable under the theme's text.
 */
fun accentFor(brand: Int, g: ThemeGround): AccentSet? {
    val rgb = brand and 0xFFFFFF
    fun inkContrast(c: Int) = max(contrastRatio(c, g.controlInk), contrastRatio(c, g.textPrimary))
    val accent = moveLightness(rgb) { contrastRatio(it, g.bgPrimary) >= 3.0 && inkContrast(it) >= 4.5 } ?: return null
    val accentText = readableOn(accent, g.bgSelected, 4.5).takeIf { contrastRatio(it, g.bgSelected) >= 4.5 } ?: return null
    val accentSoft = mix(g.bgSecondary, accent, fitT(g.bgSecondary, g.accent, g.accentSoft))
    val accentSoftInk = readableOn(accent, accentSoft, 4.5).takeIf { contrastRatio(it, accentSoft) >= 4.5 } ?: return null
    val mention = mix(g.bgPrimary, accent, fitT(g.bgPrimary, g.accent, g.mention))
    var t = fitT(g.bgPrimary, g.accent, g.bgMessageOwn)
    var own = mix(g.bgPrimary, accent, t)
    while (contrastRatio(g.textPrimary, own) < 4.5 && t > 0.0) {
        t = max(0.0, t - 0.01)
        own = mix(g.bgPrimary, accent, t)
    }
    return AccentSet(accent, accentText, accentSoft, accentSoftInk, mention, own)
}

/**
 * For the issuing tool: one line per theme where the fitted colour keeps teal, or comes so close to
 * the danger or amber counts (under 1.5:1 and within 30 degrees of hue) that they would differ by
 * hue alone or not at all.
 */
fun accentWarnings(brand: Int): List<String> = THEME_GROUNDS.mapNotNull { g ->
    val s = accentFor(brand, g) ?: return@mapNotNull "${g.name}: keeps teal (no version of this colour passes there)"
    fun close(other: Int) = contrastRatio(s.accent, other) < 1.5 && hueDistance(s.accent, other) < 30
    when {
        close(g.danger) -> "${g.name}: too close to the red of a direct mention"
        close(g.amber) -> "${g.name}: too close to the amber of a mention"
        else -> null
    }
}
