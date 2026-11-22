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
 * One way push can reach this phone: a UnifiedPush distributor app ([builtIn] false, e.g.
 * ntfy) or Google Play services through the distributor built into the app ([builtIn] true).
 */
data class PushRoute(val packageName: String, val label: String, val builtIn: Boolean)

/** The routes this phone offers ([all], the built-in one first) and the one in use ([current], null = none). */
data class PushRoutes(val all: List<PushRoute> = emptyList(), val current: String? = null)

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

    /** The routes and the one in use, for Settings. Kept up to date by the registrar. */
    val routes: StateFlow<PushRoutes> get() = MutableStateFlow(PushRoutes())

    /** Look at the phone again (an app may have been installed or removed). Does not suspend. */
    fun refreshRoutes() {}

    /** Switch to [packageName] (one of [routes]) and register through it. Does not suspend. */
    fun useRoute(packageName: String) {}
}

/** Does nothing, reports [PushStatus.Unknown]. The real one is [UnifiedPushRegistrar]. */
class NoOpPushRegistrar : PushRegistrar {
    override val status: StateFlow<PushStatus> = MutableStateFlow(PushStatus.Unknown)
    override suspend fun onLoggedIn() {}
    override suspend fun onLoggedOut() {}
}
