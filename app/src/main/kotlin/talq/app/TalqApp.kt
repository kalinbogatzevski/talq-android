package talq.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import talq.app.badge.BadgeRefreshWorker
import talq.app.badge.UnreadBadge
import talq.app.data.KeystoreCredentialStore
import talq.app.data.NotifyLevelStore
import talq.app.data.RoomTabStore
import talq.app.files.FileNotice
import talq.app.files.ReceivedFiles
import talq.app.files.UploadManager
import talq.app.files.VoicePlayer
import talq.app.nav.DeepLink
import talq.app.push.NoOpPushRegistrar
import talq.app.push.NotificationGate
import talq.app.push.PushRegistrar
import talq.app.push.UnifiedPushRegistrar
import talq.app.shift.ShiftStatusService
import talq.core.TalkError
import talq.core.model.Credentials
import talq.core.repo.TalkSession

/** Whether somebody is signed in. The whole UI hangs off this. */
sealed interface SessionState {
    /** Reading stored credentials / checking them with the server. */
    data object Loading : SessionState

    data object SignedOut : SessionState

    /** Credentials are stored but the server could not be reached to open a session. */
    data class Unreachable(val error: TalkError) : SessionState

    data class SignedIn(val session: TalkSession) : SessionState
}

/**
 * The app's manual dependency container: one instance, owned by [TalqApp]. It holds the
 * signed-in [TalkSession] (whose `rooms`, `chat(...)` and `topics(...)` are the repositories),
 * the credential store and the push registrar.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process; for work that must outlive a screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val credentialStore = KeystoreCredentialStore(appContext)

    /** The tab last open in each conversation (see [RoomTabStore]). */
    val roomTabs = RoomTabStore(appContext, scope)

    /** Files on their way into chats (see [UploadManager]). */
    val uploads = UploadManager(appContext, scope) { session }

    /** Files received in chats: downloaded into the cache, opened, shared, saved (see [ReceivedFiles]). */
    val received = ReceivedFiles(appContext, scope) { session }

    /** The one voice message (or audio file) that can be playing. Failing to stream, it is downloaded and opened. */
    val voice = VoicePlayer(appContext, scope, { session }) { file ->
        received.notice.value = FileNotice("Couldn't play ${file.name.ifBlank { "that" }} here. Downloading it to open in another app.")
        received.open(file)
    }

    /** Notification levels of conversations and topics, kept for the push handler (see [NotifyLevelStore]). */
    val notifyLevels = NotifyLevelStore(appContext)

    /** Whether a notification may be shown: a muted conversation or topic shows nothing. */
    val gate = NotificationGate(appContext, notifyLevels) { session }

    /** The unread number on the launcher icon. */
    val badge = UnreadBadge(appContext, notifyLevels, { session })

    /** Shift status of colleagues from the organisation's ERP, and the pairing with it (see [ShiftStatusService]). */
    val shift = ShiftStatusService(appContext, scope) { bringToFront() }

    /** The push stage replaces this with the real implementation (in [TalqApp.onCreate]). */
    var pushRegistrar: PushRegistrar = NoOpPushRegistrar()

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Loading)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /** The open session, or null when nobody is signed in (or it is still loading). */
    val session: TalkSession? get() = (_sessionState.value as? SessionState.SignedIn)?.session

    /**
     * A notification tap waiting to be navigated to. MainActivity sets it; the nav host
     * consumes it (sets it back to null) once a session is open.
     */
    val pendingDeepLink = MutableStateFlow<DeepLink?>(null)

    private val mutex = Mutex()

    init {
        scope.launch { gate.run(sessionState) }
        scope.launch { badge.run(sessionState) }
        scope.launch { shift.run(sessionState) }
    }

    /** Bring MainActivity back on top of whatever the app opened over itself (the sign-in tab). */
    fun bringToFront() {
        appContext.startActivity(
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    /** Open a session from stored credentials. Called at app start and by "Try again". */
    fun restore() {
        scope.launch {
            mutex.withLock {
                if (_sessionState.value is SessionState.SignedIn) return@withLock
                _sessionState.value = SessionState.Loading
                val stored = try {
                    loadStored()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Storage or Keystore trouble. Nothing is erased: the next start tries again.
                    Log.w(TAG, "stored account could not be read", e)
                    null
                }
                if (stored == null) {
                    _sessionState.value = SessionState.SignedOut
                    return@withLock
                }
                try {
                    opened(TalkSession.open(stored))
                } catch (e: TalkError.Unauthorized) {
                    // The app password was revoked on the server: the stored one is dead.
                    clearStored()
                    shift.signedOut()
                    _sessionState.value = SessionState.SignedOut
                } catch (e: TalkError) {
                    _sessionState.value = SessionState.Unreachable(e)
                }
            }
        }
    }

    /** [restore] again, but only when the last attempt could not reach the server. */
    fun restoreIfUnreachable() {
        if (_sessionState.value is SessionState.Unreachable) restore()
    }

    /** Reads the stored account; a Keystore that is merely busy gets a second chance. */
    private suspend fun loadStored(): Credentials? = try {
        credentialStore.load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        delay(STORAGE_RETRY_MS)
        credentialStore.load()
    }

    /**
     * Finish a sign-in: check [credentials] with the server, persist them, start push.
     * Throws [TalkError], or [CredentialStorageException] when the phone could not store the
     * account; nothing is stored when it does.
     */
    suspend fun signIn(credentials: Credentials): TalkSession = mutex.withLock {
        val session = TalkSession.open(credentials)
        if (!session.capabilities.hasTalk) {
            // Don't leave an orphaned app password behind on a server we can't use.
            session.logout()
            throw TalkError.NotFound("Talk is not installed on this server")
        }
        try {
            credentialStore.save(session.credentials)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An account that can't be kept would be gone at the next start: give the app
            // password back rather than leave it orphaned on the server.
            Log.w(TAG, "account could not be stored", e)
            session.logout()
            throw CredentialStorageException(e)
        }
        opened(session)
        session
    }

    /** Sign out: drop push, revoke the app password, clear storage. Always ends signed out. */
    suspend fun logout() = mutex.withLock {
        val session = session
        if (session != null) {
            try {
                pushRegistrar.onLoggedOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            session.logout()
        }
        uploads.clearAll()
        voice.stop()
        received.clearAll()
        clearImages()
        clearStored()
        shift.signedOut()
        notifyLevels.clear()
        roomTabs.clearAll()
        pendingDeepLink.value = null
        _sessionState.value = SessionState.SignedOut
    }

    /** Pictures and avatars of the account that the image loader kept (memory and disk). */
    private fun clearImages() {
        val loader = SingletonImageLoader.get(appContext)
        loader.memoryCache?.clear()
        scope.launch(Dispatchers.IO) {
            try {
                loader.diskCache?.clear()
            } catch (e: Exception) {
                Log.w(TAG, "the picture cache could not be cleared", e)
            }
        }
    }

    /** The server answered 401 in the middle of a session: the password is gone, sign out locally. */
    fun onUnauthorized() {
        scope.launch {
            mutex.withLock {
                val session = session ?: return@withLock
                // Push belongs to the account that just went away: drop the subscription on
                // the phone, its notifications, and a tap that was waiting to be opened.
                try {
                    pushRegistrar.onSessionLost()
                } catch (_: Exception) {
                }
                pendingDeepLink.value = null
                uploads.clearAll()
                voice.stop()
                received.clearAll()
                clearImages()
                clearStored()
                shift.signedOut()
                notifyLevels.clear()
                roomTabs.clearAll()
                session.close()
                _sessionState.value = SessionState.SignedOut
            }
        }
    }

    /** Signing out must end signed out even when the storage misbehaves. */
    private suspend fun clearStored() {
        try {
            credentialStore.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "stored account could not be cleared", e)
        }
    }

    private fun opened(session: TalkSession) {
        // Before anything asks the server for topics: every level it reports is kept.
        val account = NotificationGate.accountKey(session)
        notifyLevels.account = account
        session.topicLevelListener = { token, levels -> notifyLevels.putTopics(account, token, levels) }
        _sessionState.value = SessionState.SignedIn(session)
        scope.launch {
            try {
                pushRegistrar.onLoggedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Push is best effort; its own status flow reports what went wrong.
            }
        }
    }
}

/** The account could not be written to the phone's storage (Keystore or preferences file). */
class CredentialStorageException(cause: Throwable) : Exception("The account could not be stored", cause)

private const val TAG = "TalqApp"
private const val STORAGE_RETRY_MS = 400L

class TalqApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Before restore(): opening the session is what starts the push registration.
        container.pushRegistrar = UnifiedPushRegistrar(this, container)
        container.restore()
        // Leaving the screen is when the icon is looked at: count again (what was read in a
        // topic is only known here, not on the server).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) container.badge.request() },
        )
        // The ERP is only asked for shift status while the app is in front.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> container.shift.setForeground(true)
                    Lifecycle.Event.ON_STOP -> container.shift.setForeground(false)
                    else -> Unit
                }
            },
        )
        // Messages that send no push (a group on "mentions only", something read on another
        // device) only reach the icon number through a reload now and then. Only on phones
        // whose home screen shows the number.
        container.scope.launch {
            if (container.badge.supported()) BadgeRefreshWorker.schedule(this@TalqApp) else BadgeRefreshWorker.cancel(this@TalqApp)
        }
    }

    /**
     * Coil's app-wide loader. Requests add the `Authorization` header themselves, and every
     * image URL is built from the account's server URL; redirects are NOT followed, so that
     * header can never travel on to another host.
     */
    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = HttpClient(OkHttp) { followRedirects = false })) }
            .crossfade(true)
            .build()
}

val Context.appContainer: AppContainer get() = (applicationContext as TalqApp).container
