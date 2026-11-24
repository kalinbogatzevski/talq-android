package talq.app.status

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import talq.app.SessionState
import talq.core.TalkError
import talq.core.model.OwnStatus
import talq.core.model.PredefinedStatus
import talq.core.model.StatusType
import talq.core.repo.TalkSession
import java.time.ZonedDateTime

/**
 * "Clear after" of a status message, in TalQ for desktop's order (StatusPopover::buildUi), and
 * when each one clears it (StatusPopover::selectedClearAt): in the phone's own time zone.
 */
enum class ClearAfter(val label: String) {
    Never("Don't clear"),
    ThirtyMinutes("30 minutes"),
    OneHour("1 hour"),
    FourHours("4 hours"),
    Today("Today"),
    ThisWeek("This week"),
    ;

    /** Unix seconds when the message goes; 0 = never. Today ends at 23:59:59, the week on Sunday at 23:59:59. */
    fun clearAt(now: ZonedDateTime = ZonedDateTime.now()): Long = when (this) {
        Never -> 0
        ThirtyMinutes -> now.toEpochSecond() + 30 * 60
        OneHour -> now.toEpochSecond() + 60 * 60
        FourHours -> now.toEpochSecond() + 4 * 60 * 60
        Today -> endOfDay(now, 0)
        // ISO days: Monday 1 .. Sunday 7, so Sunday itself ends its own week.
        ThisWeek -> endOfDay(now, 7L - now.dayOfWeek.value)
    }

    private fun endOfDay(now: ZonedDateTime, plusDays: Long): Long =
        now.toLocalDate().plusDays(plusDays).atTime(23, 59, 59).atZone(now.zone).toEpochSecond()
}

/** What the conversation list's profile bar shows of the user's own status. */
data class OwnStatusUi(
    /** Signed in, and the server has the user status app. Nothing is shown or asked otherwise. */
    val available: Boolean = false,
    /** The status was read (or written) at least once since sign-in; before that [status] is a placeholder. */
    val loaded: Boolean = false,
    val status: OwnStatus = OwnStatus(),
    val predefined: List<PredefinedStatus> = emptyList(),
    /** The server takes "busy" as a status type. */
    val supportsBusy: Boolean = false,
    /** A custom message may carry an emoji. */
    val supportsEmoji: Boolean = true,
)

/**
 * What the status sheet asks to save.
 *
 * @param type the status type chosen, or null when none is (nothing is sent for the type)
 * @param presetId the predefined message picked and not edited since, else empty (a custom message)
 * @param clearAfter null = the message keeps the time it is cleared at now
 */
data class StatusDraft(
    val type: String?,
    val icon: String,
    val message: String,
    val presetId: String,
    val clearAfter: ClearAfter?,
)

/**
 * The signed-in user's own status ([appContainer][talq.app.AppContainer]`.ownStatus`): read after
 * sign-in and whenever the app comes to the front, set from the profile bar. Ported from TalQ for desktop's
 * UserStatusManager (endpoints, bodies, status strings) and StatusPopover (choices, clear after),
 * WITHOUT its heartbeat, status poll and auto-away: on the phone the status only changes when the
 * user sets it, so the phone never works against the desktop's auto-away.
 *
 * MAIN THREAD ONLY. One save at a time ([saving]); it runs on the container's scope, so closing the
 * sheet does not cut it between its requests. A read that was sent before a save is dropped (the
 * desktop's grace window after a local change, done with a counter).
 */
class OwnStatusController(private val scope: CoroutineScope) {

    private val _ui = MutableStateFlow(OwnStatusUi())
    val ui: StateFlow<OwnStatusUi> = _ui.asStateFlow()

    private val _saving = MutableStateFlow(false)

    /** A save is on its way: Save is off until it has answered. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private var session: TalkSession? = null
    private var readJob: Job? = null
    private var saveJob: Job? = null

    /** Counts saves: a read is applied only if no save started after it was sent. */
    private var writes = 0L

    /** Follows the session for as long as the process lives; signed out, everything is forgotten. */
    suspend fun run(states: StateFlow<SessionState>) {
        states.map { (it as? SessionState.SignedIn)?.session }.distinctUntilChanged().collect { s ->
            if (s == null) signedOut() else attach(s)
        }
    }

    private fun attach(s: TalkSession) {
        if (session === s) return
        signedOut()
        session = s
        val caps = s.capabilities
        _ui.value = OwnStatusUi(
            available = caps.userStatus,
            supportsBusy = caps.userStatusBusy,
            supportsEmoji = caps.userStatusEmoji,
        )
        refresh()
    }

    /** Signed out (any path): stop what runs and forget the account's status. */
    fun signedOut() {
        readJob?.cancel()
        saveJob?.cancel()
        readJob = null
        saveJob = null
        session = null
        _saving.value = false
        _ui.value = OwnStatusUi()
    }

    /** Read the status (and, until they are known, the predefined messages). After sign-in and on coming to the front. */
    fun refresh() {
        val s = session ?: return
        if (!_ui.value.available || readJob?.isActive == true) return
        val sentAt = writes
        readJob = scope.launch {
            try {
                val status = s.client.ownStatus() ?: OwnStatus()
                if (session === s && sentAt == writes && !_saving.value) {
                    _ui.update { it.copy(loaded = true, status = status) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TalkError) {
                Log.w(TAG, "own status not read: ${e.message}")
            }
            if (session === s && _ui.value.predefined.isEmpty()) {
                try {
                    val list = s.client.predefinedStatuses()
                    if (session === s) _ui.update { it.copy(predefined = list) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TalkError) {
                    Log.w(TAG, "predefined statuses not read: ${e.message}")
                }
            }
        }
    }

    /**
     * Save what the sheet holds: the type when it changed, then the message when it changed (a
     * picked preset as predefined, typed text as custom, nothing left = cleared). Only what
     * changed is sent. [onDone] gets null when everything was saved, else one short line.
     */
    fun save(draft: StatusDraft, onDone: (error: String?) -> Unit) {
        val s = session ?: return onDone("Not signed in.")
        if (_saving.value) return
        _saving.value = true
        writes++
        saveJob = scope.launch {
            var error: String? = null
            try {
                saveSteps(s, draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TalkError) {
                Log.w(TAG, "own status not saved: ${e.message}")
                error = saveErrorText(e)
            } finally {
                // A read sent while this save ran may answer after it: it is dropped too.
                writes++
                if (session === s) _saving.value = false
            }
            if (session === s) onDone(error)
        }
    }

    private suspend fun saveSteps(s: TalkSession, draft: StatusDraft) {
        val before = _ui.value
        // Like desktop, what was written is applied locally; the answers' bodies are not relied on.
        var now = before.status
        val type = draft.type
        if (type != null && (type != now.status || !before.loaded)) {
            s.client.setStatusType(type)
            now = now.copy(status = type, userDefined = true)
            publish(s, now)
        }

        val icon = draft.icon.trim()
        val text = draft.message.trim()
        val nowSeconds = System.currentTimeMillis() / 1000
        val clearAt = draft.clearAfter?.clearAt() ?: now.clearAt.takeIf { it > nowSeconds } ?: 0L
        when {
            draft.presetId.isEmpty() && icon.isEmpty() && text.isEmpty() -> if (now.hasMessage) {
                s.client.clearStatusMessage()
                publish(s, now.copy(message = "", icon = "", messageId = "", clearAt = 0))
            }
            // Unchanged and no new "Clear after": nothing to send (a predefined message stays one).
            draft.clearAfter == null && draft.presetId == now.messageId &&
                icon == now.icon.trim() && text == now.message.trim() -> Unit
            draft.presetId.isNotEmpty() -> {
                s.client.setPredefinedStatusMessage(draft.presetId, clearAt)
                // The preset's own words and emoji, as desktop's setPredefined takes them from its list.
                val preset = before.predefined.firstOrNull { it.id == draft.presetId }
                publish(
                    s,
                    now.copy(
                        messageId = draft.presetId,
                        message = preset?.message ?: text,
                        icon = preset?.icon ?: icon,
                        clearAt = clearAt,
                    ),
                )
            }
            else -> {
                s.client.setCustomStatusMessage(icon, text, clearAt)
                publish(s, now.copy(messageId = "", message = text, icon = icon, clearAt = clearAt))
            }
        }
    }

    private fun publish(s: TalkSession, status: OwnStatus) {
        if (session === s) _ui.update { it.copy(loaded = true, status = status) }
    }

    private fun saveErrorText(e: TalkError): String = when {
        e is TalkError.Network -> "No connection. Try again."
        e is TalkError.Http && e.status == 400 -> "Not accepted by the server."
        else -> "Not saved. Try again."
    }

    private companion object {
        const val TAG = "OwnStatus"
    }
}
