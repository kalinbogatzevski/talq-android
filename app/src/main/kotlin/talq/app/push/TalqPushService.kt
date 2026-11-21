package talq.app.push

import android.util.Log
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import talq.app.appContainer

/**
 * Where the UnifiedPush connector delivers its events. The connector's own receiver binds to
 * this service for a few seconds per event (declared in the manifest with the
 * `org.unifiedpush.android.connector.PUSH_EVENT` action). All the work is in
 * [UnifiedPushRegistrar], on the app container's scope.
 */
class TalqPushService : PushService() {
    private val registrar: UnifiedPushRegistrar?
        get() = applicationContext.appContainer.pushRegistrar as? UnifiedPushRegistrar

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        registrar?.onNewEndpoint(endpoint)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) {
            // Registered with a VAPID key, the connector decrypts. Anything else is not ours to read.
            Log.w("TalqPush", "dropping a push that could not be decrypted")
            return
        }
        registrar?.onMessage(message.content)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        registrar?.onRegistrationFailed(reason)
    }

    override fun onUnregistered(instance: String) {
        registrar?.onUnregistered()
    }
}
