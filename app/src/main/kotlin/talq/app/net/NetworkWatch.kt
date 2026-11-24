package talq.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the phone's default network offers the app. */
enum class NetLink {
    /** No network at all: airplane mode, no signal, nothing connected. */
    None,

    /** Connected, but Android has not seen it reach the internet (Wi-Fi without internet, a sign-in page). */
    Unvalidated,

    /** Connected and validated by Android. */
    Usable,
}

/**
 * Whether the connection line says "No connection": no network at all, or a network Android could
 * not validate while the app's own requests fail for want of a connection ([failing]). A network
 * Android cannot validate (a filtered one) while the server still answers is never called offline.
 */
fun NetLink.offline(failing: Boolean): Boolean = this == NetLink.None || (this == NetLink.Unvalidated && failing)

/**
 * The app's one watch on the phone's default network (`appContainer.network`), for the whole
 * process. [link] is what the screens show; [start]'s handler runs on the main thread each time
 * the network becomes usable (internet, validated) after it was not: TalQ for desktop's
 * reachability edge (MainWindow::onServerReachabilityChanged). A hand-over that keeps a usable
 * network (Wi-Fi to mobile) is no edge, and neither is the report the system makes right after
 * [start]. Folder sync keeps its own watch (FolderSyncController), started and stopped with the sync.
 *
 * "No network" reaches [link] only after [GRACE_MILLIS] without a network, so a hand-over that
 * passes through no network for a moment draws nothing; a network coming back shows at once.
 */
class NetworkWatch(context: Context, private val scope: CoroutineScope) {
    private val connectivity: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The default network the reports are about; a loss of another one (the old one after a hand-over) changes nothing. */
    private var current: Network? = null

    /** The last report, without the grace. */
    private var raw = NetLink.Usable
    private var noneLater: Job? = null

    private val _link = MutableStateFlow(NetLink.Usable)

    /** The network as the screens show it. Usable until the system has said otherwise. */
    val link: StateFlow<NetLink> = _link.asStateFlow()

    /** Watch from now on; [onUsable] gets every edge to a usable network. Main thread, once. */
    fun start(onUsable: () -> Unit) {
        val cm = connectivity ?: return
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Its capabilities follow at once (Android 8 and later).
                current = network
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                current = network
                report(linkOf(networkCapabilities), onUsable)
            }

            override fun onLost(network: Network) {
                if (current != null && network != current) return
                current = null
                report(NetLink.None, onUsable)
            }
        }
        try {
            val active = cm.activeNetwork
            current = active
            raw = if (active == null) NetLink.None else linkOf(cm.getNetworkCapabilities(active))
            _link.value = raw
            cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper()))
            callback = cb
        } catch (e: RuntimeException) {
            // Too many callbacks in this process, or no permission: the timers still retry.
            Log.w(TAG, "the network cannot be watched; retries wait for their timers")
        }
    }

    private fun report(now: NetLink, onUsable: () -> Unit) {
        val was = raw
        raw = now
        if (now == NetLink.None) {
            if (noneLater == null && _link.value != NetLink.None) {
                noneLater = scope.launch {
                    delay(GRACE_MILLIS)
                    noneLater = null
                    if (raw == NetLink.None) _link.value = NetLink.None
                }
            }
        } else {
            noneLater?.cancel()
            noneLater = null
            _link.value = now
        }
        if (now == NetLink.Usable && was != NetLink.Usable) onUsable()
    }

    private fun linkOf(caps: NetworkCapabilities?): NetLink = when {
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetLink.None
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetLink.Usable
        else -> NetLink.Unvalidated
    }

    private companion object {
        const val TAG = "NetworkWatch"
        const val GRACE_MILLIS = 1_500L
    }
}
