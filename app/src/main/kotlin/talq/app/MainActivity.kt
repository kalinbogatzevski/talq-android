package talq.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import talq.app.nav.DeepLink
import talq.app.net.NetLink
import talq.app.nav.TalqRoot
import talq.app.theme.ThemedSystemBars
import talq.app.theme.TalqTheme
import talq.app.theme.TalqAppTheme

class MainActivity : ComponentActivity() {
    /**
     * What an Esc does that nothing on screen took, while it must not become Back (a conversation is
     * open beside the list, see TalqNavHost). Null: Android makes Back of it, as ever.
     */
    var onEscape: (() -> Unit)? = null
    private var escapeTaken = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            // Held down: the first press decided.
            if (escapeTaken && event.repeatCount > 0) return true
            val escape = onEscape
            if (escape != null && event.hasNoModifiers()) {
                escapeTaken = true
                if (event.repeatCount == 0) escape()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && escapeTaken) {
            escapeTaken = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh start carries a new deep link; a rotation would replay the old one.
        if (savedInstanceState == null) handleDeepLink(intent)
        appContainer.showSaved()
        setContent {
            TalqAppTheme(appContainer) {
                ThemedSystemBars(this, TalqTheme.colors.isLight)
                TalqRoot(appContainer)
            }
        }
        holdFirstFrame()
    }

    /**
     * The first frame is the list, not a screen that gives way to it: it waits for what the phone itself keeps
     * (the account, the saved list, its order), which is read while the screen is made, so there is hardly
     * anything left to wait for. Never for the network, and never long; Android's start picture stays meanwhile.
     */
    private fun holdFirstFrame() {
        if (appContainer.startKnown) return
        val content = findViewById<View>(android.R.id.content)
        val from = SystemClock.uptimeMillis()
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!appContainer.startKnown && SystemClock.uptimeMillis() - from < FIRST_FRAME_MILLIS) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // A start in the background (a push) may have found no network. Now that the app is in
        // front, try again instead of leaving the user on the "can't reach the server" screen.
        appContainer.restoreIfUnreachable()
        // Organisation mode: the brand file again when it is due (every few days, in front, online).
        appContainer.scope.launch {
            appContainer.organisation.refreshIfDue(online = appContainer.network.link.value == NetLink.Usable)
        }
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
        // Organisation mode's link (public app only): it only offers a code, for the screen in front
        // to look up and show as a card. Kept nowhere else; never logged.
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data?.scheme == "talq" && data.host == "organisation") {
            if (!Brand.isBranded) {
                val code = intent.getStringExtra(EXTRA_ORG_CODE) ?: data.getQueryParameter("code")
                if (!code.isNullOrBlank()) appContainer.organisation.pendingCode.value = code.take(40)
            }
            return
        }
        DeepLink.fromIntent(this, intent)?.let { appContainer.pendingDeepLink.value = it }
    }

    companion object {
        /**
         * The invite code in a `talq://organisation` intent from another app (the transfer version):
         * an extra, not the address, so it stays out of anything that prints intents' addresses.
         */
        const val EXTRA_ORG_CODE = "talq.organisation.code"

        private const val FIRST_FRAME_MILLIS = 300L
    }
}
