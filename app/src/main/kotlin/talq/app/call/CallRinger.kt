package talq.app.call

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.CircleCropTransformation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.AppContainer
import talq.app.SessionState
import talq.app.ui.common.authorizedImage
import talq.core.TalkError
import talq.core.call.NotificationState
import talq.core.repo.TalkSession

/**
 * Incoming calls (`appContainer.ringer`). Main thread.
 *
 * A call push in a one-to-one conversation becomes a ringing CallStyle notification at once
 * (full screen over the lock screen, the ringtone, Answer and Decline), held up by
 * [TalqRingService] so the app keeps running while it rings; the caller's name and picture
 * follow once the conversation list answers. A call in a group conversation does not ring (it
 * cannot be answered here): it becomes an ordinary notice. The phone does not join the
 * conversation or its signaling while it rings: it joins only on Answer.
 *
 * While it rings, `GET call/{token}/notification-state` is asked every 3 s: 200 keeps ringing,
 * 201 (the call is over) stops and leaves a missed call, 404 (answered on another device, or the
 * conversation is gone) and 403 stop quietly. A delete push for the notification stops it
 * quietly too. 45 s after the push it stops and leaves a missed call, also when the account
 * could not be reached all that time. Decline stops it and dismisses the server's notification,
 * so the user's other devices stop ringing as well (the caller keeps ringing until it gives up:
 * there is no way to decline towards the caller).
 *
 * During a call (or while another call rings) a new call does not ring: it becomes a notice
 * "called while you were in another call".
 */
class CallRinger(context: Context, private val container: AppContainer) {
    /** [oneToOne]: the conversation is known to be one-to-one (null: not known yet). */
    data class Ringing(val nid: Long, val token: String, val callerName: String, val callerUserId: String, val oneToOne: Boolean? = null)

    private val appContext = context.applicationContext
    private val scope = container.scope

    private val _ringing = MutableStateFlow<Ringing?>(null)

    /** The call ringing now (one at a time). */
    val ringing: StateFlow<Ringing?> = _ringing.asStateFlow()

    private var job: Job? = null
    private var refineJob: Job? = null
    private var avatar: Bitmap? = null
    private var deadline = 0L

    /** The ring the user answered, until the answer is settled: a failed one becomes a missed call. */
    private var answeredRing: Ringing? = null

    /**
     * Rings for the call push [nid] in [token]. Returns false when this call is not one to ring
     * (a group conversation): the caller shows the push as an ordinary notification then.
     */
    fun ring(nid: Long, token: String, subject: String): Boolean {
        if (token.isEmpty()) return true
        val current = _ringing.value
        if (current?.nid == nid) return true
        val room = container.session?.rooms?.room(token)
        if (room != null && !room.isOneToOne) return false
        val name = room?.displayName?.ifBlank { null } ?: subject.ifBlank { "Incoming call" }
        val calls = container.calls
        if (calls.busy || (current != null && current.token != token)) {
            // Already in (or setting up) a call, or another one rings: no ringing over it.
            if (calls.current.value?.token != token) CallNotifications.showMissed(appContext, nid, token, name, busy = true)
            return true
        }
        stopRinging()
        val ring = Ringing(nid, token, name, room?.partnerUserId.orEmpty(), oneToOne = room?.isOneToOne)
        _ringing.value = ring
        avatar = null
        deadline = System.currentTimeMillis() + RING_TIMEOUT_MS
        post(ring)
        TalqRingService.start(appContext)
        job = scope.launch { follow(ring) }
        refineJob = scope.launch { refine(ring) }
        return true
    }

    /** A delete push for these server notifications: answered or dismissed elsewhere. */
    fun stop(nids: Collection<Long>) {
        if (_ringing.value?.let { it.nid in nids } == true) stopRinging()
        nids.forEach { CallNotifications.cancel(appContext, it) }
    }

    /** Everything was dismissed on the server. */
    fun stopAll() = stopRinging()

    /**
     * Declined here: stop, and take the server's notification down for the user's other
     * devices. [done] runs once that is over (the notification button keeps the app up till then).
     */
    fun decline(nid: Long, done: () -> Unit = {}) {
        if (_ringing.value?.nid == nid) stopRinging()
        if (nid == 0L) return done()
        scope.launch {
            try {
                // A decline can start the app (the ring outlived it): wait for the account.
                val session = container.calls.awaitAccount() ?: return@launch
                withTimeoutOrNull(REQUEST_WAIT_MS) { session.client.dismissNotification(nid) }
            } catch (e: TalkError) {
                Log.w(TAG, "dismissing the call notification failed (${e::class.simpleName})")
            } finally {
                done()
            }
        }
    }

    /** Answered here: stop ringing (the call screen joins the call). */
    fun answered(nid: Long) {
        val ring = _ringing.value?.takeIf { it.nid == nid }
        if (ring != null) {
            answeredRing = ring
            stopRinging()
        }
    }

    /**
     * The answer [nid] went through ([answered]) or not; a failed or cancelled one is a missed
     * call ([groupCall]: a notice that a group call started instead).
     */
    fun answerSettled(nid: Long, answered: Boolean, groupCall: Boolean = false) {
        val ring = answeredRing?.takeIf { it.nid == nid } ?: return
        answeredRing = null
        when {
            answered -> Unit
            groupCall -> CallNotifications.showGroupCall(appContext, ring.nid, ring.token, ring.callerName)
            else -> CallNotifications.showMissed(appContext, ring.nid, ring.token, ring.callerName, busy = false, callBack = ring.oneToOne != false)
        }
    }

    /**
     * A call is being placed: a call ringing meanwhile becomes a missed call, unless it rings in
     * [exceptToken] (the person being called is calling too: both join the same call).
     */
    fun missCurrent(exceptToken: String) {
        val ring = _ringing.value ?: return
        if (ring.token == exceptToken) stopRinging() else missed(ring)
    }

    fun signedOut() = stopRinging()

    /** The ringing notification as it should show now (for [TalqRingService]). */
    fun notification(): Notification? = _ringing.value?.let { CallNotifications.incoming(appContext, it, avatar) }

    private suspend fun follow(ring: Ringing) {
        var lastRestore = 0L
        var session: TalkSession? = null
        while (_ringing.value?.nid == ring.nid) {
            val now = System.currentTimeMillis()
            if (now >= deadline) return missed(ring)
            val current = container.session
            if (session != null && current !== session) return stopRinging() // signed out meanwhile
            session = current
            when {
                current == null -> if (container.sessionState.value is SessionState.Unreachable && now - lastRestore > RESTORE_GAP_MS) {
                    lastRestore = now
                    container.restoreIfUnreachable()
                }
                current.capabilities.callNotificationState -> {
                    val state = try {
                        // Bounded: a hanging request must not hold up the 45 s deadline.
                        withTimeoutOrNull(REQUEST_WAIT_MS) { current.client.callNotificationState(ring.token) }
                    } catch (e: TalkError) {
                        null // the next poll tries again
                    }
                    if (_ringing.value?.nid != ring.nid) return
                    when (state) {
                        NotificationState.Missed -> return missed(ring)
                        NotificationState.Gone, NotificationState.Forbidden -> return stopRinging()
                        NotificationState.Ringing, null -> Unit
                    }
                }
            }
            delay(POLL_MS)
        }
    }

    /** The caller's name from the conversation list, and their picture; a group call stops here. */
    private suspend fun refine(ring: Ringing) {
        val session = awaitSession() ?: return
        // Tried again while it rings: a cold start may have no network for a moment.
        var room = session.rooms.room(ring.token)
        while (room == null) {
            if (_ringing.value?.nid != ring.nid) return
            session.rooms.refresh()
            room = session.rooms.room(ring.token)
            if (room == null) delay(REFINE_RETRY_MS)
        }
        if (_ringing.value?.nid != ring.nid) return
        if (!room.isOneToOne) {
            stopRinging()
            CallNotifications.showGroupCall(appContext, ring.nid, ring.token, room.displayName)
            return
        }
        val named = ring.copy(callerName = room.displayName.ifBlank { ring.callerName }, callerUserId = room.partnerUserId, oneToOne = true)
        if (named != ring) {
            _ringing.value = named
            post(named)
        }
        if (room.partnerUserId.isEmpty()) return
        val picture = withTimeoutOrNull(AVATAR_WAIT_MS) { loadAvatar(session, room.partnerUserId) } ?: return
        if (_ringing.value?.nid != ring.nid) return
        avatar = picture
        post(_ringing.value ?: return)
    }

    private suspend fun loadAvatar(session: TalkSession, userId: String): Bitmap? = try {
        val request = authorizedImage(appContext, session.client.userAvatarUrl(userId, 256), session.client.authorizationHeader)
            .newBuilder()
            .size(256)
            .allowHardware(false)
            .transformations(CircleCropTransformation())
            .build()
        (SingletonImageLoader.get(appContext).execute(request) as? SuccessResult)?.image?.toBitmap()
    } catch (e: Exception) {
        null
    }

    private fun missed(ring: Ringing) {
        val now = _ringing.value?.takeIf { it.nid == ring.nid } ?: ring
        stopRinging()
        // "Call back" only where it can work (a one-to-one conversation, or not known to be a group).
        CallNotifications.showMissed(appContext, now.nid, now.token, now.callerName, busy = false, callBack = now.oneToOne != false)
    }

    private fun stopRinging() {
        _ringing.value = null
        job?.cancel()
        job = null
        refineJob?.cancel()
        refineJob = null
        avatar = null
        CallNotifications.cancelRing(appContext)
    }

    // The ringing notification is a CallStyle one with a full-screen intent, which Android shows
    // even where ordinary notifications are off for the app.
    @SuppressLint("MissingPermission")
    private fun post(ring: Ringing) {
        CallNotifications.ensureChannels(appContext)
        try {
            NotificationManagerCompat.from(appContext).notify(CallNotifications.RING_ID, CallNotifications.incoming(appContext, ring, avatar))
        } catch (e: Exception) {
            Log.w(TAG, "the ringing notification was refused (${e::class.simpleName})")
        }
    }

    private suspend fun awaitSession(): TalkSession? {
        val state = withTimeoutOrNull(SESSION_WAIT_MS) { container.sessionState.first { it is SessionState.SignedIn || it is SessionState.SignedOut } }
        return (state as? SessionState.SignedIn)?.session
    }

    companion object {
        private const val TAG = "TalqCall"
        const val RING_TIMEOUT_MS = 45_000L
        private const val POLL_MS = 3_000L
        private const val SESSION_WAIT_MS = 40_000L
        private const val RESTORE_GAP_MS = 10_000L
        private const val AVATAR_WAIT_MS = 10_000L
        private const val REQUEST_WAIT_MS = 5_000L
        private const val REFINE_RETRY_MS = 5_000L
    }
}
