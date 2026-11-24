package talq.app.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the app's own update check stands. */
sealed interface UpdateState {
    /** Not asked yet. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    /** A newer version is on the channel. [notes]: its changelog section, may be empty. */
    data class Available(val version: String, val notes: String) : UpdateState

    /** The check failed and nothing is on offer. */
    data class Failed(val message: String) : UpdateState
}

/**
 * The app learning about its own updates from its organisation's channel: the branded build
 * only, which is shared as an APK and has no store to update it. "Update" hands the new
 * version's APK to Android's download manager, and the user installs the finished download from
 * its notification or the Downloads app, as any downloaded app. The app itself installs nothing
 * and holds no permission to (an unknown app that installs APKs is what Play Protect blocks as a
 * "dropper"). The generic build (Google Play, F-Droid, GitHub) gets [NoAppUpdates].
 */
interface AppUpdates {
    /** False: nothing about updates is shown anywhere. */
    val enabled: Boolean
    val state: StateFlow<UpdateState>

    /** The version the user put off with "Not now" (no banner for it; Settings still offers it). */
    val dismissed: StateFlow<String?>

    /** Asks the channel unless it was asked recently. Called when the app comes to the front. */
    fun checkIfDue()

    /** Asks the channel now (Settings). */
    fun checkNow()

    /**
     * Starts the available version's download (Android's download manager). A second tap while it
     * runs starts nothing; once it is done, a tap opens Downloads. False: it could not start.
     */
    fun download(): Boolean

    /** True while the available version is downloading: the Update buttons are disabled meanwhile. */
    val downloading: StateFlow<Boolean>

    /** "Not now" for the version on offer. */
    fun dismiss()
}

/** The generic build: no update channel. */
object NoAppUpdates : AppUpdates {
    override val enabled = false
    override val state: StateFlow<UpdateState> = MutableStateFlow<UpdateState>(UpdateState.Idle).asStateFlow()
    override val dismissed: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override fun checkIfDue() = Unit
    override fun checkNow() = Unit
    override fun download() = false
    override val downloading: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override fun dismiss() = Unit
}
