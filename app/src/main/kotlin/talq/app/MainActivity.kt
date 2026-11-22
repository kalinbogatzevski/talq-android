package talq.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import talq.app.nav.DeepLink
import talq.app.nav.TalqRoot
import talq.app.theme.ThemedSystemBars
import talq.app.theme.TalqTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh start carries a new deep link; a rotation would replay the old one.
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent {
            val theme by appContainer.appearance.theme.collectAsStateWithLifecycle()
            TalqTheme(theme) {
                ThemedSystemBars(this, TalqTheme.colors.isLight)
                TalqRoot(appContainer)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A start in the background (a push) may have found no network. Now that the app is in
        // front, try again instead of leaving the user on the "can't reach the server" screen.
        appContainer.restoreIfUnreachable()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** Hands a notification tap to the nav host (see [DeepLink]); ordinary launches carry none. */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return
        // Reopened from the recents list: the intent is the old one, don't jump again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        DeepLink.fromIntent(this, intent)?.let { appContainer.pendingDeepLink.value = it }
    }
}
