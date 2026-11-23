package talq.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The themes, as on desktop TalQ, plus following the phone's own light/dark setting. */
enum class ThemeChoice(val id: String, val label: String, val description: String) {
    System("system", "Follow phone", "Vivid when the phone is dark, Paper when it is light"),
    Ember("ember", "Ember", "Dark, deep and quiet"),
    Warm("warm", "Warm", "Dark, soft and warm"),
    Vivid("vivid", "Vivid", "Dark, bright accent"),
    Paper("paper", "Paper", "Light"),
    ;

    companion object {
        fun fromId(id: String?): ThemeChoice = entries.firstOrNull { it.id == id } ?: System
    }
}

/**
 * How the app looks, on this device. Not tied to an account: signing out keeps it. Read
 * synchronously at start (a small preferences file) so the first frame already has the theme.
 */
class AppearanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(ThemeChoice.fromId(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    fun setTheme(choice: ThemeChoice) {
        _theme.value = choice
        prefs.edit().putString(KEY_THEME, choice.id).apply()
    }

    /** Width of the conversation list beside an open conversation (tablets, wide windows), in dp. */
    val listPaneWidthDp: Float
        get() = prefs.getFloat(KEY_LIST_WIDTH, DEFAULT_LIST_WIDTH_DP)

    fun setListPaneWidthDp(dp: Float) {
        prefs.edit().putFloat(KEY_LIST_WIDTH, dp).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_LIST_WIDTH = "list_pane_width_dp"
        const val DEFAULT_LIST_WIDTH_DP = 360f
    }
}
