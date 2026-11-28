package talq.app.call

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import talq.app.appContainer
import talq.core.call.CallState

/**
 * Keeps a call running with the screen off or in another app, and shows it in the notification
 * shade (CallStyle: the peer, the duration, Hang up; a tap opens the call screen).
 *
 * Foreground service types: phoneCall always, microphone once the permission is granted, camera
 * while the camera is on (Android lets a call keep the microphone and camera in the background
 * only through these). It is started from the visible call screen as a call starts, goes to the
 * foreground at once ("Connecting…"), then follows the call by itself and stops itself when the
 * call is over or the start came to nothing. Nobody else stops it: stopping a service before it
 * went to the foreground makes Android end the app.
 */
class TalqCallService : Service() {
    private val scope = MainScope()
    private var watching: Job? = null
    private var types = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always to the foreground first, whatever follows.
        val call = appContainer.calls.current.value?.takeIf { it.session.state.value !is CallState.Ended }
        val shown = if (call != null) {
            foreground(CallNotifications.ongoing(this, call.title, call.session.camera.value, null), call.session.camera.value)
        } else {
            foreground(CallNotifications.connecting(this), camera = false)
        }
        if (!shown) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watching == null) watching = scope.launch { follow() }
        return START_NOT_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun follow() {
        val calls = appContainer.calls
        combine(calls.current, calls.starting) { call, starting -> call to starting }.collectLatest { (call, starting) ->
            // An ended call (its end screen still showing) counts as none: a new start may be under way.
            if (call == null || call.session.state.value is CallState.Ended) {
                // Nothing to show once no start is under way any more.
                if (!starting) stopSelf()
                return@collectLatest
            }
            combine(
                call.session.state.map { (it as? CallState.Active)?.since to (it is CallState.Ended) },
                call.session.camera,
            ) { (since, ended), camera -> Triple(since, ended, camera) }
                .distinctUntilChanged()
                .collect { (since, ended, camera) ->
                    if (ended) {
                        if (!calls.starting.value) stopSelf()
                    } else {
                        foreground(CallNotifications.ongoing(this, call.title, camera, since), camera)
                    }
                }
        }
    }

    /** startForeground with the types the permissions allow; at least phoneCall. */
    private fun foreground(notification: Notification, camera: Boolean): Boolean {
        CallNotifications.ensureChannels(this)
        var wanted = TYPE_PHONE_CALL
        if (granted(Manifest.permission.RECORD_AUDIO)) wanted = wanted or TYPE_MICROPHONE
        if (camera && granted(Manifest.permission.CAMERA)) wanted = wanted or TYPE_CAMERA
        for (t in listOf(wanted, types, TYPE_PHONE_CALL).distinct()) {
            if (t == 0 && TYPE_PHONE_CALL != 0) continue
            try {
                ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID, notification, t)
                types = t
                return true
            } catch (e: Exception) {
                // Adding a type from the background (or a revoked permission) is refused: fall
                // back to what was granted before, then to phoneCall alone.
                Log.w(TAG, "foreground service types $t refused (${e::class.simpleName})")
            }
        }
        return false
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TalqCall"
        private val TYPE_PHONE_CALL = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
        private val TYPE_MICROPHONE = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        private val TYPE_CAMERA = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0

        /** From the visible call screen only (Android refuses a microphone service from the background). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TalqCallService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "the call service did not start (${e::class.simpleName})")
            }
        }
    }
}
