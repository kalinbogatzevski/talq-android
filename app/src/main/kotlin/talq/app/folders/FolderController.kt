package talq.app.folders

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import talq.app.SessionState
import talq.app.data.AppearanceStore
import talq.app.data.FolderStore
import talq.core.folders.FolderResult
import talq.core.folders.FolderState
import talq.core.folders.MAX_ASSIGNMENTS
import talq.core.folders.MAX_FOLDERS
import talq.core.folders.MAX_FOLDER_NAME_CHARS
import talq.core.folders.accountScopeKey
import talq.core.folders.folderIndexOf
import talq.core.folders.folderOf
import talq.core.folders.normaliseFolderName
import talq.core.repo.TalkSession
import java.io.File

/**
 * Conversation folders for the app: follows the signed-in account, owns its [FolderStore] and the
 * opt-in [FolderSyncController], and is the one place the screens change folders. Ported from TalQ
 * for desktop's MainWindow folder plumbing (attachFolders, detachFolders, createFolderInteractive,
 * newFolderForChat, renameFolderInteractive, fileChatInFolder, reportFolderResult) and the
 * sidebar's reveal of a chat in a closed folder.
 *
 * MAIN THREAD ONLY. Every action returns the store's [FolderResult] (or [FolderCreated]) for the
 * screen to put into words with [folderResultText]; a refused action leaves the folders as they
 * were. Folders are per account (accountScopeKey of the server and the CANONICAL user id); "Show
 * folders" ([AppearanceStore.showFolders]) is per device and never synced. Collapsing a folder is a
 * change of the folders like any other: it is saved, and synced when sync is on.
 */
class FolderController(context: Context, private val scope: CoroutineScope, private val appearance: AppearanceStore) {

    val store = FolderStore(File(context.applicationContext.filesDir, "folders"))

    /** The opt-in sync of the attached account (Settings reads [FolderSyncController.status]). */
    val sync = FolderSyncController(context, scope, store)

    /** The attached account's folders; the EMPTY state while nobody is signed in. */
    val state: StateFlow<FolderState> = store.state

    private val _loadFailed = MutableStateFlow(false)

    /** The account's folders file exists but could not be read at sign-in: folders are off for this session. */
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** Everything the conversation list needs, in one value. */
    val ui: StateFlow<FolderUi> = combine(store.state, store.status, _loadFailed, appearance.showFolders) { s, st, failed, show ->
        FolderUi(state = s, attached = st.attached, readOnly = st.readOnly, loadFailed = failed, showFolders = show)
    }.stateIn(scope, SharingStarted.Eagerly, FolderUi(showFolders = appearance.showFolders.value))

    private var attachedSession: TalkSession? = null

    /** A notification's reveal waiting for the conversation list's first answer ([revealWhenListed]). */
    private var pendingReveal: Job? = null

    // ------------------------------------------------------------------------------ the account

    /**
     * Follows the session for as long as the process lives. Signed in: the account's store is
     * attached, then its sync. Signed out: the sync stops first, then the store detaches (flushing).
     * The files stay on the device: the folders come back at the next sign-in to the same account.
     */
    suspend fun run(states: StateFlow<SessionState>) {
        states.map { (it as? SessionState.SignedIn)?.session }.distinctUntilChanged().collect { session ->
            if (session == null) detachAll() else attachTo(session)
        }
    }

    private fun attachTo(session: TalkSession) {
        if (attachedSession === session && store.isAttached) return
        // The canonical user id, never the login name; an empty key means folders stay off
        // rather than guess an account.
        val key = accountScopeKey(session.credentials.server, session.user.userId)
        if (key.isEmpty()) {
            Log.w(TAG, "no account identity yet; folders stay off")
            detachAll()
            return
        }
        // Sync first, store second: the sync drops its chain while the store is still that account's.
        sync.detach()
        _loadFailed.value = false
        attachedSession = null
        if (!store.attach(key)) {
            // Once per sign-in, no retry timer: Settings says so, so folders are not simply "gone".
            Log.w(TAG, "the folders file of this account could not be opened; folders stay off for this session")
            _loadFailed.value = true
            return
        }
        attachedSession = session
        sync.attach { session.folderDav }
    }

    private fun detachAll() {
        dropPendingReveal()
        sync.detach()
        store.detach()
        _loadFailed.value = false
        attachedSession = null
    }

    /**
     * Both sign-out paths, BEFORE the session's app password is revoked or the session closed: the
     * sync chain is cancelled and waited for, nothing is uploaded, then the store detaches. The
     * files stay.
     */
    suspend fun signedOut() {
        dropPendingReveal()
        sync.signedOut()
        store.detach()
        _loadFailed.value = false
        attachedSession = null
    }

    /** The app came to the front or left it (the sync pulls, pushes and polls by it). */
    fun setForeground(on: Boolean) {
        sync.setForeground(on)
    }

    /** The device-wide "Show folders" switch (Settings). */
    fun setShowFolders(on: Boolean) = appearance.setShowFolders(on)

    // ------------------------------------------------------------------------------ user actions

    /**
     * Before the "New folder" name dialog opens: the refusal no name could fix (no account, a
     * read-only store, already MAX_FOLDERS folders), or null when a name is worth asking for.
     */
    fun createRefusal(): FolderResult? = when {
        !store.isAttached -> FolderResult.NotAttached
        store.isReadOnly -> FolderResult.ReadOnly
        store.state.value.folders.size >= MAX_FOLDERS -> FolderResult.TooMany
        else -> null
    }

    /**
     * A new folder named [name]; with [fileToken], that chat is filed in it (the "New folder…" of
     * a chat's "Move to folder"). The FIRST folder turns "Show folders" on: asking for a folder and
     * not seeing it would be odd. [FolderCreated.shortened] says the name was cut to
     * MAX_FOLDER_NAME_CHARS characters (worth a word on screen).
     */
    fun create(name: String, fileToken: String? = null): FolderCreated {
        val firstFolder = store.state.value.folders.isEmpty()
        val change = store.createFolder(name)
        if (change.result != FolderResult.Ok) return FolderCreated(change.result)
        if (firstFolder) appearance.setShowFolders(true)
        val filing = fileToken?.let { store.assign(it, change.folderId) }
        return FolderCreated(change.result, change.folderId, filing, normaliseFolderName(name).shortened)
    }

    /**
     * NotFound here usually means the folder was deleted on another device while the dialog was
     * open ([FOLDER_REMOVED_ELSEWHERE_TEXT]). A cut name is worth a word also on Unchanged (a long
     * name whose first 40 characters are the current one): see [nameShortened].
     */
    fun rename(id: String, name: String): FolderResult = store.renameFolder(id, name)

    /** No confirmation: its chats go back to Other. */
    fun delete(id: String): FolderResult = store.deleteFolder(id)

    fun moveUp(id: String): FolderResult = store.moveFolder(id, -1)

    fun moveDown(id: String): FolderResult = store.moveFolder(id, +1)

    /** "Sort folders A to Z": Unchanged when they already are. */
    fun sortAZ(): FolderResult = store.sortFoldersByName()

    /** File [token] in [folderId]; null (or empty) takes it out of its folder. */
    fun assign(token: String, folderId: String?): FolderResult = store.assign(token, folderId ?: "")

    /** Open or close a folder (a header tap). Saved, and synced when sync is on. */
    fun toggle(id: String): FolderResult {
        val s = store.state.value
        val idx = folderIndexOf(s, id)
        if (idx < 0) return if (store.isAttached) FolderResult.NotFound else FolderResult.NotAttached
        return store.setCollapsed(id, !s.folders[idx].collapsed)
    }

    /** Open or close "Other". */
    fun toggleOther(): FolderResult = store.setOtherCollapsed(!store.state.value.otherCollapsed)

    /**
     * Opening a chat that sits in a closed folder (or in a closed Other) opens that folder, so the
     * open chat is never hidden in the list, and that is kept and synced like a tap on the header.
     * Nothing while folders are not shown (Show folders off, or no folder yet). Call it for a chat
     * the list shows: for one it does not, opening its section would show nothing.
     */
    fun reveal(token: String): FolderResult {
        if (!appearance.showFolders.value) return FolderResult.Unchanged
        val s = store.state.value
        if (s.folders.isEmpty()) return FolderResult.Unchanged
        val folderId = folderOf(s, token)   // empty: unfiled, or filed in a folder that is gone (Other)
        if (folderId.isEmpty()) return if (s.otherCollapsed) store.setOtherCollapsed(false) else FolderResult.Unchanged
        val idx = folderIndexOf(s, folderId)
        return if (s.folders[idx].collapsed) store.setCollapsed(folderId, false) else FolderResult.Unchanged
    }

    /**
     * [reveal] for a chat opened from a notification, only once the list shows it: at once when the
     * list of [session] has answered; after a cold start, when it first answers, if [session] is
     * still the attached one. A chat the list does not show (one the user left) is left alone, as
     * desktop's reveal does: opening Other for it would show nothing and be synced to every device.
     * A newer link, or a sign-out, drops a reveal still waiting.
     */
    fun revealWhenListed(session: TalkSession, token: String) {
        dropPendingReveal()
        if (session.rooms.state.value.loaded) {
            if (session.rooms.room(token) != null) reveal(token)
            return
        }
        pendingReveal = scope.launch {
            session.rooms.state.first { it.loaded }
            pendingReveal = null
            if (attachedSession === session && session.rooms.room(token) != null) reveal(token)
        }
    }

    private fun dropPendingReveal() {
        pendingReveal?.cancel()
        pendingReveal = null
    }

    /** The typed name will be cut to MAX_FOLDER_NAME_CHARS characters. */
    fun nameShortened(typed: String): Boolean = normaliseFolderName(typed).shortened

    /** While a folder dialog is open, a change from another device waits (one [releaseApplies] per call). */
    fun holdApplies() = sync.holdApplies()

    fun releaseApplies() = sync.releaseApplies()

    private companion object {
        const val TAG = "Folders"
    }
}

/** What the conversation list and Settings need of the folders, in one value. */
data class FolderUi(
    val state: FolderState = FolderState(),
    val attached: Boolean = false,
    val readOnly: FolderStore.ReadOnlyReason = FolderStore.ReadOnlyReason.None,
    val loadFailed: Boolean = false,
    /** The device-wide "Show folders" switch. */
    val showFolders: Boolean = false,
) {
    /** The list is drawn in folders: shown, and at least one folder exists (with none, the list stays flat). */
    val grouped: Boolean get() = showFolders && state.folders.isNotEmpty()
}

/**
 * [result] is the folder's; [folderId] is set when it is Ok. [filing] is the result of filing the
 * chat given to create (null when none was given, or the folder was not made). [shortened]: the name
 * was cut to MAX_FOLDER_NAME_CHARS characters.
 */
data class FolderCreated(
    val result: FolderResult,
    val folderId: String = "",
    val filing: FolderResult? = null,
    val shortened: Boolean = false,
)

/** Said after a create or rename whose name was cut. */
const val FOLDER_NAME_SHORTENED_TEXT = "The folder name was shortened to $MAX_FOLDER_NAME_CHARS characters."

/** Said when a rename finds its folder gone (deleted on another device while the dialog was open). */
const val FOLDER_REMOVED_ELSEWHERE_TEXT = "That folder was removed on another device."

/**
 * What to tell the user about a refused folder action, or null when there is nothing to say (it
 * worked, nothing needed doing, or a refusal the user cannot act on). [typedName] is what was typed
 * (for DuplicateName); [filing] says a TooMany came from filing a chat, not from making a folder.
 */
fun folderResultText(
    result: FolderResult,
    readOnly: FolderStore.ReadOnlyReason,
    appName: String,
    typedName: String = "",
    filing: Boolean = false,
): String? = when (result) {
    FolderResult.Ok, FolderResult.Unchanged -> null
    FolderResult.EmptyName -> "Please type a name for the folder."
    FolderResult.DuplicateName -> "You already have a folder called “${normaliseFolderName(typedName).text}”."
    FolderResult.UnknownCharacters -> "This name uses characters this phone does not know yet. Try other characters."
    FolderResult.TooMany ->
        if (filing) "You can file up to $MAX_ASSIGNMENTS chats in folders." else "You can have up to $MAX_FOLDERS folders."
    FolderResult.ReadOnly ->
        if (readOnly == FolderStore.ReadOnlyReason.CouldNotSetAside) {
            "Your folders file could not be opened for changes. Restart $appName to try again."
        } else {
            "Your folders were saved by a newer version of $appName, so this version can't change them."
        }
    // Nothing the user can act on: no account attached, or a menu that went stale.
    FolderResult.NotAttached, FolderResult.BadToken, FolderResult.NotFound -> null
}
