package talq.app.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where push notifications stand, for the Notifications row in settings. */
sealed interface PushStatus {
    /** Nothing has been tried yet. */
    data object Unknown : PushStatus

    /** No UnifiedPush distributor app is installed on the phone. */
    data object NoDistributor : PushStatus

    /** The server's admin has not enabled web push. */
    data object ServerDisabled : PushStatus

    data object Registering : PushStatus

    /** Registered and activated: notifications arrive while the app is closed. */
    data object Active : PushStatus

    data class Error(val message: String) : PushStatus
}

/**
 * Owns the push subscription for the signed-in account. The container calls [onLoggedIn]
 * after a session is open (fresh sign-in and every app start with stored credentials) and
 * [onLoggedOut] before the session is closed, while the server can still be told.
 */
interface PushRegistrar {
    val status: StateFlow<PushStatus>
    suspend fun onLoggedIn()
    suspend fun onLoggedOut()

    /**
     * The session ended without a sign-out (the server revoked the app password). The server
     * can no longer be told; clean up what is on the phone.
     */
    fun onSessionLost() {}

    /** Start the registration again (the "Try again" button in settings). Does not suspend. */
    fun retry() {}
}

/** Does nothing, reports [PushStatus.Unknown]. The real one is [UnifiedPushRegistrar]. */
class NoOpPushRegistrar : PushRegistrar {
    override val status: StateFlow<PushStatus> = MutableStateFlow(PushStatus.Unknown)
    override suspend fun onLoggedIn() {}
    override suspend fun onLoggedOut() {}
}
