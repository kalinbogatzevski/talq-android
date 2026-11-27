package talq.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import talq.app.AppContainer
import talq.app.Brand
import talq.app.data.ThemeChoice
import talq.core.org.THEME_GROUNDS
import talq.core.org.accentFor

/**
 * These colours with an organisation's [brand] colour (`0xRRGGBB`, organisation mode) in place of
 * the accent, fitted to this theme by the shared rules (talq.core.org.accentFor: readable fill and
 * ink, accent text, soft tint, mention, own-message bubble). Unchanged, the very same object, for no
 * brand, for a theme the rules do not know, for a colour no version of which passes here (the theme
 * keeps its teal), and for the theme's own teal.
 */
fun TalqColors.withBrand(brand: Int?): TalqColors {
    if (brand == null) return this
    val ground = THEME_GROUNDS.firstOrNull { it.bgPrimary == bgPrimary.rgb() } ?: return this
    val fitted = accentFor(brand, ground) ?: return this
    if (fitted.accent == ground.accent) return this
    val branded = copy(
        accent = fitted.accent.color(),
        accentText = fitted.accentText.color(),
        accentSoft = fitted.accentSoft.color(),
        accentSoftInk = fitted.accentSoftInk.color(),
        mention = fitted.mention.color(),
        bgMessageOwn = fitted.bgMessageOwn.color(),
    )
    return branded.copy(accentInk = branded.inkOn(branded.accent))
}

private fun Color.rgb(): Int = toArgb() and 0xFFFFFF

private fun Int.color(): Color = Color(0xFF000000.toInt() or (this and 0xFFFFFF))

/**
 * The app's theme as the person and their organisation set it: the theme they picked, else the
 * organisation's default, else following the phone; and the organisation's colour. [fixed] forces a
 * theme (the call screen is always Vivid) but keeps the colour.
 */
@Composable
fun TalqAppTheme(container: AppContainer, fixed: ThemeChoice? = null, content: @Composable () -> Unit) {
    val picked by container.appearance.picked.collectAsStateWithLifecycle()
    val organisation by container.organisation.state.collectAsStateWithLifecycle()
    val org = organisation.takeIf { !Brand.isBranded }
    val choice = fixed ?: picked ?: ThemeChoice.fromId(org?.theme)
    TalqTheme(choice, org?.accent, content)
}
