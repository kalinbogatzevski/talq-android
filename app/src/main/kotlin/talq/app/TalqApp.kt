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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.badge.BadgeRefreshWorker
import talq.app.badge.UnreadBadge
import talq.app.badge.runSilencedUnread
import talq.app.call.CallController
import talq.app.call.CallRinger
import talq.app.data.AppearanceStore
import talq.app.data.KeystoreCredentialStore
import talq.app.data.ListSortStore
import talq.app.data.MessageStore
import talq.app.data.NotifyLevelStore
import talq.app.data.NotifyNoticeStore
import talq.app.data.RoomTabStore
import talq.app.data.SavedListStore
import talq.app.data.UnsentStore
import talq.app.files.FileNotice
import talq.app.files.ReceivedFiles
import talq.app.files.RecordingState
import talq.app.files.UploadManager
import talq.app.files.VoiceMessages
import talq.app.files.VoicePlayer
import talq.app.folders.FolderController
import talq.app.nav.DeepLink
import talq.app.net.NetLink
import talq.app.net.NetworkWatch
import talq.app.push.MessageActions
import talq.app.push.NoOpPushRegistrar
import talq.app.push.NotificationGate
import talq.app.push.PushRegistrar
import talq.app.push.PushStatus
import talq.app.push.UnifiedPushRegistrar
import talq.app.share.ChatShortcuts
import talq.app.shift.ShiftStatusService
import talq.app.sound.SoundStore
import talq.app.status.OwnStatusController
import talq.app.typing.TypingPresence
import talq.app.ui.chat.ComposerDrafts
import talq.app.ui.common.technical
import talq.app.update.AppUpdates
import talq.app.update.newAppUpdates
import talq.core.TalkClient
import talq.core.TalkError
import talq.core.TalqCore
import talq.core.model.Credentials
import talq.core.model.Room
import talq.core.repo.SavedList
import talq.core.repo.TalkSession
import talq.core.repo.UnsentText

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
 * The conversation list as the last run of the app left it, on screen until the server's own is there.
 * [session] is the account it is of, NOT checked (`TalkSession.unchecked`): it is for the screens only and
 * never [AppContainer.session], so nothing that works with an account sees it.
 */
class SavedStart(val session: TalkSession, val rooms: List<Room>)

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

    /** Unsent text per conversation tab, in memory until it is sent or the account signs out (see [ComposerDrafts]). */
    val drafts = ComposerDrafts()

    /** Texts sent off but not confirmed by the server, kept for the next run of the app (see [UnsentStore]). */
    private val unsent = UnsentStore(appContext)

    /** The conversation list, kept for the first frame of the next run of the app (see [SavedListStore]). */
    private val savedList = SavedListStore(appContext)

    /** The newest messages of the conversations used last, kept for the next run of the app (see [MessageStore]). */
    val messages = MessageStore(appContext)

    /** Files on their way into chats (see [UploadManager]). */
    val uploads = UploadManager(appContext, scope) { session }

    /** Files received in chats: downloaded into the cache, opened, shared, saved (see [ReceivedFiles]). */
    val received = ReceivedFiles(appContext, scope) { session }

    /**
     * The one voice message (or audio file) that can be playing; none starts while a voice message
     * is recorded. Failing to stream, it is downloaded and opened.
     */
    val voice: VoicePlayer = VoicePlayer(appContext, scope, { session }, { voiceMessages.state.value is RecordingState.Recording }) { file ->
        received.notice.value = FileNotice("Couldn't play ${file.name.ifBlank { "that" }} here. Downloading it to open in another app.")
        received.open(file)
    }

    /** Notification levels of conversations and topics, kept for the push handler (see [NotifyLevelStore]). */
    val notifyLevels = NotifyLevelStore(appContext)

    /** How the app looks on this phone (theme); not tied to an account. */
    val appearance = AppearanceStore(appContext)

    /** What the conversation list is sorted by on this phone (see [ListSortStore]); not tied to an account. */
    val listSort = ListSortStore(appContext, scope)

    /** The message sound and the ringtone (Settings > Sounds); not tied to an account. */
    val sounds = SoundStore(appContext)

    /** The app updating itself: the branded build's own channel; nothing in the generic build. */
    val updates: AppUpdates = newAppUpdates(appContext)

    /** Whether a notification may be shown: a muted conversation or topic shows nothing. */
    val gate = NotificationGate(appContext, notifyLevels) { session }

    /** "Reply" and "Mark as read" on message notifications (see [MessageActions]). */
    val messageActions = MessageActions(appContext, this)

    /** The unread number on the launcher icon. */
    val badge = UnreadBadge(appContext, notifyLevels, { session })

    /** Shift status of colleagues from the organisation's ERP, and the pairing with it (see [ShiftStatusService]). */
    val shift = ShiftStatusService(appContext, scope) { bringToFront() }

    /** Conversation folders of the signed-in account, and their opt-in sync with the user's own Files (see [FolderController]). */
    val folders = FolderController(appContext, scope, appearance)

    /** The user's own status: read after sign-in and on coming to the front, set in Settings (see [OwnStatusController]). */
    val ownStatus = OwnStatusController(scope)

    /** The one call this phone can be in (see [CallController]). */
    val calls = CallController(appContext, this)

    /** Incoming calls ringing (see [CallRinger]). */
    val ringer = CallRinger(appContext, this)

    /** "typing…" in the conversation in front (see [TypingPresence]). */
    val typing = TypingPresence(appContext, this)

    /** Recording a voice message from the composer, one at a time (see [VoiceMessages]). */
    val voiceMessages: VoiceMessages = VoiceMessages(appContext, scope, uploads, voice, { calls.busy }) { session }

    /** The push stage replaces this with the real implementation (in [TalqApp.onCreate]). */
    var pushRegistrar: PushRegistrar = NoOpPushRegistrar()

    /** The phone's network, watched for the whole process: the connection line, and what goes again when it is back (see [NetworkWatch]). */
    val network = NetworkWatch(appContext, scope)

    /** A dismissed "notifications are off" line of the conversation list (one value for both places the list is drawn). */
    val notifyNotices = NotifyNoticeStore(appContext)

    /** The most recent conversations in the share sheet of other apps (see [ChatShortcuts]). */
    val shortcuts = ChatShortcuts(appContext)

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Loading)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /** The open session, or null when nobody is signed in (or it is still loading). */
    val session: TalkSession? get() = (_sessionState.value as? SessionState.SignedIn)?.session

    private val _saved = MutableStateFlow<SavedStart?>(null)

    /** What the list shows until the session's own first list is there; null when nothing was kept, and from then on. */
    val saved: StateFlow<SavedStart?> = _saved.asStateFlow()

    /** The main screen was shown in this process: what was kept is read, once ([showSaved]). */
    private var savedAsked = false
    private var savedRead = false

    /** The stored account as it was read at start; null when nobody is signed in. */
    private val startAccount = CompletableDeferred<Credentials?>()

    /** The check of the stored account that runs now. */
    private var check: Job? = null

    /** The phone's own part of a start is over: the first frame can be drawn (see MainActivity). */
    val startKnown: Boolean
        get() = _sessionState.value is SessionState.SignedOut || ((savedRead || hasList) && listSort.known.value)

    private val _loggingOut = MutableStateFlow(false)

    /** A sign-out runs, which can take seconds: what started it is off meanwhile. */
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    private val _signOutNote = MutableStateFlow<String?>(null)

    /** What the last sign-out could not do, for the sign-in screen; until somebody signs in. */
    val signOutNote: StateFlow<String?> = _signOutNote.asStateFlow()

    /** The session is open and has its list. */
    private val hasList: Boolean get() = session?.rooms?.state?.value?.loaded == true

    /**
     * A notification tap waiting to be navigated to. MainActivity sets it; the nav host
     * consumes it (sets it back to null) once a session is open.
     */
    val pendingDeepLink = MutableStateFlow<DeepLink?>(null)

    private val mutex = Mutex()

    init {
        drafts.trays = uploads.trays
        drafts.onListed = uploads::dropGone
        scope.launch { gate.run(sessionState) }
        scope.launch { badge.run(sessionState) }
        scope.launch { shortcuts.run(sessionState) }
        scope.launch { shift.run(sessionState) }
        scope.launch { folders.run(sessionState) }
        scope.launch { ownStatus.run(sessionState) }
        scope.launch { drafts.run(sessionState) }
        scope.launch { runSilencedUnread(sessionState, notifyLevels, network) }
        network.start(::onNetworkUsable)
    }

    /**
     * The network is usable again (after none, or one Android could not validate): a start that
     * could not reach the server tries again, the session's feeds, first loads and list timer stop
     * waiting, texts and files that failed for want of a connection go again (a text only after the
     * chat was looked through for it, a file with chunk resume and the same look), and a push
     * registration that failed the same way is tried again.
     */
    private fun onNetworkUsable() {
        restoreIfUnreachable()
        val session = session ?: return
        session.networkBack()
        uploads.retryNetworkFailures()
        if ((pushRegistrar.status.value as? PushStatus.Error)?.network == true) pushRegistrar.retry()
    }

    /** Bring MainActivity back on top of whatever the app opened over itself (the sign-in tab). */
    fun bringToFront() {
        appContext.startActivity(
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    /**
     * Show the saved list, if there is one, until the server's is there. Called before the main screen's first
     * frame (a process a push started reads nothing). The file is read and put together off the main thread,
     * beside the stored account; on it only the folders file is read and the rows are handed over.
     */
    fun showSaved() {
        if (savedAsked || hasList) return
        savedAsked = true
        scope.launch {
            val kept = async { savedList.read() }
            val account = startAccount.await()
            val list = kept.await()
            if (account != null && list != null && list.isOf(account)) show(account, list)
            savedRead = true
        }
    }

    private suspend fun show(credentials: Credentials, list: SavedList) {
        val account = ComposerDrafts.accountOf(list.server, list.user.userId)
        fun wanted(): Boolean = when (val state = _sessionState.value) {
            SessionState.SignedOut -> false
            // A push started the app: its session may have its own list by now.
            is SessionState.SignedIn -> ComposerDrafts.accountOf(state.session) == account && !state.session.rooms.state.value.loaded
            else -> true
        }
        val texts = unsent.peek(account)
        if (!wanted()) return
        // The list is never drawn without its folders.
        folders.showSaved(list.server, list.user.userId)
        withTimeoutOrNull(FOLDERS_MILLIS) { folders.ui.first { it.attached || it.loadFailed } }
        if (wanted()) _saved.value = SavedStart(TalkSession.unchecked(credentials, list, texts), list.rooms)
    }

    /** The session has its own list, or the account is gone. */
    private fun dropSaved() {
        _saved.value?.session?.close()
        _saved.value = null
    }

    /** Nothing kept of an account stays: what is unsent, the saved list, and the messages. */
    private fun forgetKept() {
        unsent.clear()
        savedList.clear()
        messages.clear()
    }

    /**
     * Everything of an account goes from the phone, but for the stored sign-in ([clearStored]). On every way out, once
     * whatever still needs the session has ended: signing out, a 401 while it is in use, and a 401 at start.
     */
    private suspend fun forgetAccount() {
        forgetKept()
        uploads.clearAll()
        voice.stop()
        received.clearAll()
        clearImages()
        shift.signedOut()
        notifyLevels.clear()
        notifyNotices.clear()
        roomTabs.clearAll()
        // Unsent text is the account's: never shown to whoever signs in next (as on desktop).
        drafts.clear()
        pendingDeepLink.value = null
    }

    /** The phone's end of push: its subscription, and the notifications that are up. */
    private fun dropPush() {
        try {
            pushRegistrar.onSessionLost()
        } catch (_: Exception) {
        }
    }

    /** The app leaves the screen. */
    fun leaving() {
        savedList.leaving()
        session?.flushMessages()
    }

    /**
     * Conversation [token] as the saved list has it, for what only shows it (its name and kind) until [session]'s own
     * list is there. Null without one, or once that list took its place.
     */
    fun savedRoom(session: TalkSession, token: String): Room? = _saved.value
        ?.takeIf { ComposerDrafts.accountOf(it.session) == ComposerDrafts.accountOf(session) }?.rooms?.firstOrNull { it.token == token }

    /**
     * Open a session from stored credentials. Called at app start and by "Try again". [quiet]: nobody asked
     * for it ([recheck]); until it has answered the screen goes on saying that the server was not reached.
     */
    fun restore(quiet: Boolean = false): Job {
        return scope.launch {
            mutex.withLock {
                // Opened, or signed out, while this waited (a sign-out takes its time).
                if (_sessionState.value is SessionState.SignedIn || _sessionState.value is SessionState.SignedOut) return@withLock
                if (!quiet || _sessionState.value !is SessionState.Unreachable) _sessionState.value = SessionState.Loading
                var unreadable = false
                val stored = try {
                    // Nobody is signed in (that was read for sure): nothing unsent may wait for the next account.
                    loadStored().also { if (it == null) forgetKept() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Storage or Keystore trouble. Nothing is erased: the next start tries again.
                    Log.w(TAG, "stored account could not be read", e)
                    unreadable = true
                    null
                }
                startAccount.complete(stored)
                if (stored == null) {
                    // Nobody is signed in, for sure: no file picked or sent for an account stays on the phone.
                    if (!unreadable) uploads.clearAll()
                    _sessionState.value = SessionState.SignedOut
                    return@withLock
                }
                try {
                    check = currentCoroutineContext().job
                    val session = openSession(stored)
                    withContext(NonCancellable) { opened(session) }
                } catch (e: TalkError.Unauthorized) {
                    // The app password was revoked on the server: the stored one is dead. The phone may be lost.
                    dropPush()
                    clearStored()
                    forgetAccount()
                    _sessionState.value = SessionState.SignedOut
                    dropSaved()
                } catch (e: TalkError) {
                    Log.w(TAG, "the server was not reached at start: ${e.technical()}")
                    _sessionState.value = SessionState.Unreachable(e)
                } finally {
                    check = null
                }
            }
        }
    }

    /** Not on the main thread, where the first frame is being drawn: making the HTTP client takes a moment. */
    private suspend fun openSession(credentials: Credentials): TalkSession {
        var session: TalkSession? = null
        try {
            withContext(Dispatchers.Default) { session = TalkSession.open(credentials) }
        } catch (e: CancellationException) {
            session?.close()
            throw e
        }
        return checkNotNull(session)
    }

    /** [restore] again, but only when the last attempt could not reach the server. */
    fun restoreIfUnreachable() {
        if (_sessionState.value is SessionState.Unreachable) restore()
    }

    /** The same without a word, for the saved list on screen; null when there is nothing to check, or no network to do it over. */
    fun recheck(): Job? =
        if (_sessionState.value is SessionState.Unreachable && network.link.value != NetLink.None) restore(quiet = true) else null

    /** The saved list was pulled down: the check that runs, or a new one. */
    fun checkNow(): Job = check?.takeIf { it.isActive } ?: restore()

    /**
     * Keep a text of [account] that no open session can take yet; it is sent like any unsent one once the
     * account opens. False when it is not kept: a session is open (or opening this moment) and takes it
     * itself, nobody is signed in, or it could not be written.
     */
    suspend fun keepText(account: String, text: UnsentText): Boolean {
        if (_sessionState.value is SessionState.SignedIn || signedOutForSure()) return false
        return unsent.add(account, text)
    }

    /** No account is stored (not merely unreadable just now): nothing of one may be written any more. */
    private suspend fun signedOutForSure(): Boolean = _sessionState.value is SessionState.SignedOut && try {
        credentialStore.load() == null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
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
        _signOutNote.value = null
        // Nothing of another account may be left: a sign-out cut short, or a start that could not read the stored one
        // (its files are kept then, and wait for it).
        val before = notifyLevels.account
        if (before.isNotEmpty() && before != NotificationGate.accountKey(session)) {
            dropPush()
            forgetAccount()
        }
        savedList.clear()
        messages.clear()
        opened(session)
        session
    }

    /**
     * Sign out: drop push, revoke the app password, clear storage. Always ends signed out. A check that still
     * runs is given up first: signing out does not wait long for a server that does not answer.
     */
    suspend fun logout() {
        _loggingOut.value = true
        try {
            signOut()
        } finally {
            _loggingOut.value = false
        }
    }

    private suspend fun signOut() = mutex.also { check?.cancel() }.withLock {
        // A voice message being recorded is the account's: thrown away at once (the microphone
        // closes) and never sent, before its folder is cleared below.
        voiceMessages.signedOut()
        val session = session
        // Unsent texts are the account's too: none goes out, and none is kept, from here on. Nor is the list.
        session?.dropUnsent()
        forgetKept()
        val revoked: Boolean
        if (session != null) {
            // First, folder sync stops (and is waited for): nothing of it starts while the call
            // below is left (which can take seconds), nothing goes out on a dead password, nothing
            // is uploaded at sign-out. It sends nothing itself. The folder files stay on the device.
            folders.signedOut()
            ownStatus.signedOut()
            typing.signedOut()
            // Leave a running call while the account still works (after the revoke, leaving answers 401).
            ringer.signedOut()
            calls.signedOut()
            try {
                pushRegistrar.onLoggedOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            revoked = session.logout()
        } else {
            // Signed out of the saved list, or while the account is checked: no session to do it with.
            typing.abort()
            dropPush()
            revoked = revokeStored()
        }
        clearStored()
        forgetAccount()
        // By the name the server's list of devices has for it, in every build.
        _signOutNote.value = if (revoked) null else "Remove ${TalqCore.NAME} under Security, Devices & sessions."
        _sessionState.value = SessionState.SignedOut
        dropSaved()
    }

    /**
     * A sign-out without a session: the stored account's push subscription and app password go on the server too,
     * if that answers within [REVOKE_MILLIS]. True: the password is dead, or there is none.
     */
    private suspend fun revokeStored(): Boolean = try {
        val stored = loadStored()
        when {
            stored == null -> true
            network.link.value == NetLink.None -> false
            // Making a client takes a moment, and so does closing it.
            else -> withContext(Dispatchers.Default) {
                val client = TalkClient(stored)
                try {
                    withTimeoutOrNull(REVOKE_MILLIS) { client.signOff(pushMillis = REVOKE_MILLIS / 2) } ?: false
                } finally {
                    client.close()
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "the server was not told of the sign-out (${e::class.simpleName})")
        false
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
                // First, before the session closes: the folder sync stops and sends nothing more.
                folders.signedOut()
                ownStatus.signedOut()
                typing.abort()
                ringer.signedOut()
                calls.abort()
                // Push belongs to the account that just went away.
                dropPush()
                voiceMessages.signedOut()
                session.dropUnsent()
                clearStored()
                forgetAccount()
                session.close()
                _sessionState.value = SessionState.SignedOut
                dropSaved()
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

    private suspend fun opened(session: TalkSession) {
        // Before anything asks the server for topics: every level it reports is kept.
        val account = NotificationGate.accountKey(session)
        notifyLevels.account = account
        session.topicLevelListener = { token, levels -> notifyLevels.putTopics(account, token, levels) }
        uploads.opened(session)
        // Before any screen has the session: what an earlier run left unsent is in its conversations.
        // The server has answered by now (an open session means that).
        val (keeper, kept) = unsent.open(ComposerDrafts.accountOf(session))
        session.keepUnsent(keeper, kept)
        session.keepMessages(messages.open(ComposerDrafts.accountOf(session)))
        savedList.open(session)
        _sessionState.value = SessionState.SignedIn(session)
        // From the first answer on: the list, and what the server can do, as they are now.
        session.launch { savedList.keep(session) }
        session.launch {
            session.rooms.state.first { it.loaded }
            // The list has changed over to the server's rows by itself long before.
            delay(SAVED_KEPT_MILLIS)
            withContext(Dispatchers.Main) { if (this@AppContainer.session === session) dropSaved() }
        }
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

/** The saved list waits this long for its folders at most. */
private const val FOLDERS_MILLIS = 200L
private const val SAVED_KEPT_MILLIS = 2_000L

/** Signing out without a session waits this long for the server. */
private const val REVOKE_MILLIS = 5_000L

/** How often the app asks the (throttled) update check while it is in front. */
private const val UPDATE_TICK_MILLIS = 15L * 60 * 1000

class TalqApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /** Asks the update check now and then while the app is in front (see onCreate). */
    private var updateTicks: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        // First: every client the container makes sends this version in its User-Agent.
        TalqCore.setAppVersion(BuildConfig.VERSION_NAME)
        container = AppContainer(this)
        // Before restore(): opening the session is what starts the push registration.
        container.pushRegistrar = UnifiedPushRegistrar(this, container)
        container.restore()
        // Leaving the screen is when the icon is looked at: count again (what was read in a
        // topic is only known here, not on the server).
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    container.badge.request()
                    container.leaving()
                }
            },
        )
        // The ERP is only asked for shift status while the app is in front.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        container.shift.setForeground(true)
                        // What Android refused an app in the background goes now.
                        container.shortcuts.lookAgain()
                        // Folder sync pulls on coming to the front (at most every 2 min) and polls
                        // only while in front.
                        container.folders.setForeground(true)
                        // The own status may have been changed on another device meanwhile.
                        container.ownStatus.refresh()
                        // Back in front: what could not be sent may go now (desktop does the same).
                        container.session?.tryUnsent()
                        // The update check is throttled inside (every two hours); asking now and
                        // then also covers an app that stays open all day on a desk.
                        if (container.updates.enabled) {
                            updateTicks?.cancel()
                            updateTicks = container.scope.launch {
                                while (true) {
                                    container.updates.checkIfDue()
                                    kotlinx.coroutines.delay(UPDATE_TICK_MILLIS)
                                }
                            }
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        container.shift.setForeground(false)
                        // ... and pushes a pending change on leaving it.
                        container.folders.setForeground(false)
                        updateTicks?.cancel()
                        updateTicks = null
                    }
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
