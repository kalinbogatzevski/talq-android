package talq.app.call

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import talq.app.data.ThemeChoice
import talq.app.theme.TalqTheme
import talq.app.theme.TalqAppTheme
import talq.app.appContainer
import talq.app.theme.ThemedSystemBars
import talq.app.ui.call.CallRoot

/**
 * The call screen's own activity (not MainActivity): it may show over the lock screen and turn
 * the screen on, which the chats must never do. What it shows comes from its intent: place a
 * call, answer one, show one that rings (the full-screen intent), or return to the running one.
 * Not exported; every intent to it is explicit.
 */
class CallActivity : ComponentActivity() {
    /**
     * What this screen is for. [launch] tells two taps apart that ask for the same thing (a
     * second call to the same person must start a second call).
     */
    sealed interface Request {
        /** [join]: join the call that runs there; never start one (it would ring the whole conversation). */
        data class Outgoing(val token: String, val video: Boolean, val launch: Long, val join: Boolean = false) : Request
        data class Answer(val token: String, val nid: Long, val launch: Long) : Request
        data class Ring(val token: String, val nid: Long, val launch: Long) : Request
        data object Show : Request

        /** An outgoing call asked for on a locked phone: waiting for the user to unlock. */
        data object Unlocking : Request
    }

    private val request = mutableStateOf<Request>(Request.Show)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        // A recreated screen (its old intent replayed) never places or answers a call again.
        accept(if (savedInstanceState == null) parse(intent) else Request.Show)
        setContent {
            TalqAppTheme(appContainer, fixed = ThemeChoice.Vivid) {
                ThemedSystemBars(this, light = false)
                CallRoot(request.value, onRequest = { request.value = it }, onDone = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(parse(intent))
    }

    /**
     * This screen shows over the lock screen for ringing, answering and a running call. Placing
     * a call is not one of those: on a locked phone the user unlocks first, or nothing happens.
     */
    private fun accept(r: Request) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (r !is Request.Outgoing || keyguard == null || !keyguard.isKeyguardLocked) {
            request.value = r
            return
        }
        request.value = Request.Unlocking
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                request.value = r
            }

            override fun onDismissCancelled() = finish()

            override fun onDismissError() = finish()
        })
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private fun parse(intent: Intent?): Request {
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        val nid = intent?.getLongExtra(EXTRA_NID, 0L) ?: 0L
        // Every intent that arrives is a request of its own, also a second tap on one old
        // notification button (a recreated screen does not parse its intent again).
        val launch = System.nanoTime()
        return when (intent?.action) {
            ACTION_OUTGOING -> if (token.isNotEmpty()) {
                Request.Outgoing(token, intent.getBooleanExtra(EXTRA_VIDEO, false), launch, join = intent.getBooleanExtra(EXTRA_JOIN, false))
            } else {
                Request.Show
            }
            ACTION_ANSWER -> if (token.isNotEmpty()) Request.Answer(token, nid, launch) else Request.Show
            ACTION_RING -> if (token.isNotEmpty()) Request.Ring(token, nid, launch) else Request.Show
            else -> Request.Show
        }
    }

    companion object {
        private const val ACTION_OUTGOING = "talq.app.call.OUTGOING"
        private const val ACTION_ANSWER = "talq.app.call.ANSWER"
        private const val ACTION_RING = "talq.app.call.RING"
        private const val ACTION_SHOW = "talq.app.call.SHOW"
        private const val EXTRA_TOKEN = "talq.call.token"
        private const val EXTRA_NID = "talq.call.nid"
        private const val EXTRA_VIDEO = "talq.call.video"
        private const val EXTRA_JOIN = "talq.call.join"
        private const val EXTRA_LAUNCH = "talq.call.launch"

        private fun base(context: Context, action: String): Intent =
            Intent(context, CallActivity::class.java)
                .setAction(action)
                .putExtra(EXTRA_LAUNCH, System.nanoTime())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun outgoingIntent(context: Context, token: String, video: Boolean): Intent =
            base(context, ACTION_OUTGOING).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_VIDEO, video)

        /** Joins the call that runs in [token] (the Join bar, the Join notification); never starts one. */
        fun joinIntent(context: Context, token: String, video: Boolean): Intent =
            outgoingIntent(context, token, video).putExtra(EXTRA_JOIN, true)

        fun answerIntent(context: Context, token: String, nid: Long): Intent =
            base(context, ACTION_ANSWER).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_NID, nid)

        fun ringIntent(context: Context, token: String, nid: Long): Intent =
            base(context, ACTION_RING).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_NID, nid)

        fun showIntent(context: Context): Intent = base(context, ACTION_SHOW)

        /** The permissions a call asks for: the microphone always, the camera for video, Bluetooth names on 12+. */
        fun permissionsFor(video: Boolean): List<String> = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        fun granted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
