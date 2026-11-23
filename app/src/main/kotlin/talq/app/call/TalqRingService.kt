package talq.app.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import talq.app.appContainer

/**
 * Keeps the app running while a call rings, so the ringer can ask every 3 s whether the caller
 * gave up and stop after 45 s; a cached app would be frozen within seconds. Its notification is
 * the ringing one ([CallNotifications.RING_ID]). Type phoneCall only (no microphone: nothing
 * records while it rings). Started from the call push, which allows a start from the background;
 * where Android refuses it anyway, the ring still shows, just without that guarantee. It stops
 * itself when the ringing ends.
 */
class TalqRingService : Service() {
    private val scope = MainScope()
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ringer = appContainer.ringer
        CallNotifications.ensureChannels(this)
        val notification = ringer.notification() ?: CallNotifications.ended(this)
        try {
            ServiceCompat.startForeground(this, CallNotifications.RING_ID, notification, TYPE_PHONE_CALL)
        } catch (e: Exception) {
            Log.w(TAG, "the ring service could not go to the foreground (${e::class.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        if (watching == null) {
            watching = scope.launch {
                ringer.ringing.first { it == null }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // A new ring may already show under the same id: leave it to that ring then.
        val ringingAgain = appContainer.ringer.ringing.value != null
        ServiceCompat.stopForeground(this, if (ringingAgain) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TalqCall"
        private val TYPE_PHONE_CALL = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TalqRingService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "the ring service did not start (${e::class.simpleName})")
            }
        }
    }
}
