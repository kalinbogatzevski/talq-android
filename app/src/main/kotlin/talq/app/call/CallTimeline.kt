package talq.app.call

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last call's steps, each with the milliseconds since the call was asked for (the tap on Call,
 * Join or Answer): how long a call takes to set up, and where. The steps are the call log's lines
 * (no names, no ids, no tickets: people are "them" or "person N"). Diagnostics' report carries the
 * last call's; it is also kept as `files/last-call.txt` in the app's folder on the shared storage
 * (`Android/data/<app id>/`), which a computer can read over USB where the phone keeps app logs out
 * of logcat. A new call replaces it; at most [MAX_LINES] steps. Any thread.
 */
class CallTimeline(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val lines = ArrayList<String>()
    private var startedAt = 0L
    private var token = ""
    private var pending = false
    private var writing = false

    /** A new call in [token]: [what] was asked for (a tap, or a call push that rings). */
    fun begin(what: String, token: String) {
        synchronized(this) {
            lines.clear()
            startedAt = SystemClock.elapsedRealtime()
            this.token = token
            lines += "${CLOCK.get()!!.format(Date())}  $what"
        }
        Log.i(TAG, "timeline: $what")
        save()
    }

    /** Answer tapped for the call that rang in [token]: its ring's steps stay before it. */
    fun answer(what: String, token: String) {
        val same = synchronized(this) {
            this.token == token && startedAt != 0L && SystemClock.elapsedRealtime() - startedAt < RING_KEPT_MS
        }
        if (same) step(what) else begin(what, token)
    }

    /** One step of the call being set up or running. */
    fun step(what: String) {
        synchronized(this) {
            if (startedAt == 0L || lines.size >= MAX_LINES) return
            lines += "%6d ms  %s".format(Locale.ROOT, SystemClock.elapsedRealtime() - startedAt, what)
        }
        save()
    }

    /** Sign-out: the steps and their file go (like every other kept file of the account). */
    fun clear() {
        synchronized(this) {
            lines.clear()
            startedAt = 0L
            token = ""
            pending = false
        }
        scope.launch(Dispatchers.IO) {
            try {
                appContext.getExternalFilesDir(null)?.let { File(it, "last-call.txt").delete() }
            } catch (e: Exception) {
                Log.w(TAG, "the call timeline could not be removed (${e::class.simpleName})")
            }
        }
    }

    /** The last call's steps; empty before the first call of this process. */
    fun text(): String = synchronized(this) { lines.joinToString("\n") }

    /** Written a moment after the latest step (one write for a burst of them), off the main thread. */
    private fun save() {
        synchronized(this) {
            pending = true
            if (writing) return
            writing = true
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(WRITE_DELAY_MS)
                val text = synchronized(this@CallTimeline) {
                    if (!pending || startedAt == 0L) {
                        writing = false
                        return@launch
                    }
                    pending = false
                    lines.joinToString("\n")
                }
                try {
                    val dir = appContext.getExternalFilesDir(null) ?: continue
                    val tmp = File(dir, "last-call.txt.part")
                    tmp.writeText(text + "\n")
                    tmp.renameTo(File(dir, "last-call.txt"))
                } catch (e: Exception) {
                    Log.w(TAG, "the call timeline could not be kept (${e::class.simpleName})")
                }
            }
        }
    }

    private companion object {
        const val TAG = "TalqCall"
        const val MAX_LINES = 400
        /** A ring lasts 45 s; an answer after that is a call of its own. */
        const val RING_KEPT_MS = 120_000L
        const val WRITE_DELAY_MS = 500L
        val CLOCK: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT) }
    }
}
