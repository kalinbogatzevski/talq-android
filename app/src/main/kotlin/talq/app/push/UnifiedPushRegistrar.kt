package talq.app.push

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.ResolvedDistributor
import talq.app.AppContainer
import talq.app.Brand
import talq.app.SessionState
import talq.app.ui.common.userMessage
import talq.core.TalkError
import talq.core.WebPushRegistration
import talq.core.WebPushResult
import talq.core.model.NotificationLevel
import talq.core.model.RoomType
import talq.core.model.TalkNotification
import talq.core.repo.TalkSession
import java.time.OffsetDateTime

/**
 * Push through UnifiedPush: a distributor app on the phone holds the connection, the server's
 * notifications app sends Web Push to the distributor's endpoint, and the connector library
 * decrypts it for us.
 *
 * The chain, each step triggered by the one before:
 * 1. [onLoggedIn]: pick a distributor, fetch the server's VAPID key, `UnifiedPush.register`.
 * 2. The distributor answers with an endpoint -> [onNewEndpoint] registers endpoint and keys
 *    with the server. 200 = already active. 201 = the server sends an activation push.
 * 3. The activation push arrives -> [onMessage] -> `webpush/activate`. Now [PushStatus.Active].
 *
 * Steps 2 and 3 run again whenever the distributor hands out a new endpoint. The connector
 * callbacks arrive through [TalqPushService], possibly in a freshly started process, so each
 * of them waits for the stored session to be restored first.
 */
class UnifiedPushRegistrar(context: Context, private val container: AppContainer) : PushRegistrar {
    private val appContext = context.applicationContext
    private val appName = Brand.appName(appContext)

    private val _status = MutableStateFlow<PushStatus>(PushStatus.Unknown)
    override val status: StateFlow<PushStatus> = _status.asStateFlow()

    /** Fails a registration that never gets its answer (distributor or activation push). */
    private var watchdog: Job? = null

    /** Our own `UnifiedPush.unregister` must not be reported as "the push app dropped us". */
    @Volatile
    private var loggingOut = false

    init {
        TalqNotifications.ensureChannels(appContext)
    }

    // ------------------------------------------------------------------ PushRegistrar

    override suspend fun onLoggedIn() {
        loggingOut = false
        setUp()
    }

    override fun retry() {
        container.scope.launch { setUp() }
    }

    override suspend fun onLoggedOut() {
        loggingOut = true
        watchdog?.cancel()
        // TalkSession.logout() also removes the server subscription; doing it here as well
        // keeps this class correct on its own. Both are best effort.
        try {
            container.session?.client?.webPushUnregister()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "server unregister failed", e)
        }
        forgetOnPhone()
    }

    override fun onSessionLost() {
        loggingOut = true
        watchdog?.cancel()
        forgetOnPhone()
    }

    private fun forgetOnPhone() {
        try {
            UnifiedPush.unregister(appContext, INSTANCE)
        } catch (e: Exception) {
            Log.w(TAG, "distributor unregister failed", e)
        }
        TalqNotifications.cancelAll(appContext)
        _status.value = PushStatus.Unknown
    }

    // ------------------------------------------------------------------ step 1

    private suspend fun setUp() {
        val session = container.session ?: return
        if (!chooseDistributor()) {
            watchdog?.cancel()
            _status.value = PushStatus.NoDistributor
            return
        }
        _status.value = PushStatus.Registering
        val vapid = try {
            when (val result = session.client.webPushVapidKey()) {
                is WebPushResult.Ok -> result.value
                WebPushResult.Disabled -> return fail(PushStatus.ServerDisabled)
                WebPushResult.Unsupported ->
                    return fail(PushStatus.Error("This server's notifications app is too old for push to $appName."))
                is WebPushResult.Rejected ->
                    return fail(PushStatus.Error("The server would not hand out its push key (${result.status})."))
            }
        } catch (e: TalkError) {
            return fail(PushStatus.Error(e.userMessage()))
        }
        try {
            // Passing the server's VAPID key makes the connector create the Web Push key pair
            // and decrypt incoming messages itself. The answer comes back as onNewEndpoint
            // or onRegistrationFailed.
            UnifiedPush.register(appContext, INSTANCE, null, vapid)
        } catch (e: Exception) {
            Log.w(TAG, "UnifiedPush.register failed", e)
            return fail(PushStatus.Error("The push app refused the registration."))
        }
        startWatchdog("Your push app did not answer. Open it once, then try again.")
    }

    /** True when a distributor is (now) selected. Never shows UI: this runs without an activity. */
    private fun chooseDistributor(): Boolean {
        try {
            if (UnifiedPush.getAckDistributor(appContext) != null) return true
            if (UnifiedPush.getSavedDistributor(appContext) != null) return true
            val resolved = UnifiedPush.resolveDefaultDistributor(appContext)
            if (resolved is ResolvedDistributor.Found) {
                UnifiedPush.saveDistributor(appContext, resolved.packageName)
                return true
            }
            // Several installed and no system default (or none at all): take the first one
            // rather than interrupting with a chooser.
            val first = UnifiedPush.getDistributors(appContext).firstOrNull() ?: return false
            UnifiedPush.saveDistributor(appContext, first)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "distributor lookup failed", e)
            return false
        }
    }

    // ------------------------------------------------------------------ connector callbacks

    /** Step 2. Also called when the distributor rotates the endpoint. */
    fun onNewEndpoint(endpoint: PushEndpoint) {
        container.scope.launch {
            val session = awaitSession() ?: return@launch
            val keys = endpoint.pubKeySet
            if (keys == null) {
                fail(PushStatus.Error("Your push app is too old: it does not support encrypted push."))
                return@launch
            }
            _status.value = PushStatus.Registering
            val result = try {
                session.client.webPushRegister(endpoint.url, keys.pubKey, keys.auth, APP_TYPES)
            } catch (e: TalkError) {
                fail(PushStatus.Error(e.userMessage()))
                return@launch
            }
            when (result) {
                is WebPushResult.Ok -> when (result.value) {
                    WebPushRegistration.ALREADY_ACTIVE -> succeed()
                    WebPushRegistration.CREATED_NEEDS_ACTIVATION ->
                        startWatchdog("The server's test push did not arrive. Check that your push app is running and connected.")
                }
                WebPushResult.Disabled -> fail(PushStatus.ServerDisabled)
                WebPushResult.Unsupported ->
                    fail(PushStatus.Error("This server's notifications app is too old for push to $appName."))
                is WebPushResult.Rejected -> fail(PushStatus.Error(rejectionMessage(result)))
            }
        }
    }

    fun onRegistrationFailed(reason: FailedReason) {
        if (loggingOut) return
        fail(
            PushStatus.Error(
                when (reason) {
                    FailedReason.NETWORK -> "Your push app has no connection right now."
                    FailedReason.ACTION_REQUIRED -> "Your push app needs attention. Open it and finish its setup."
                    else -> "Your push app could not register $appName."
                },
            ),
        )
    }

    /** The distributor dropped us (uninstalled, account removed there, ...). */
    fun onUnregistered() {
        if (loggingOut) return
        container.scope.launch {
            try {
                container.session?.client?.webPushUnregister()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "server unregister failed", e)
            }
            if (container.session == null) return@launch
            val anyLeft = try {
                UnifiedPush.getDistributors(appContext).isNotEmpty()
            } catch (_: Exception) {
                false
            }
            // No automatic re-registration: a distributor that keeps refusing would loop.
            fail(if (anyLeft) PushStatus.Error("Your push app dropped $appName's subscription.") else PushStatus.NoDistributor)
        }
    }

    /** A decrypted push from the server. */
    fun onMessage(content: ByteArray) {
        val payload = PushPayload.parse(content)
        if (payload == null) {
            Log.w(TAG, "unreadable push payload (${content.size} bytes)")
            return
        }
        // A push can start the process while it has no network of its own; a start-up that
        // failed for that reason gets another go now that something is happening.
        container.restoreIfUnreachable()
        when (payload) {
            is PushPayload.Activation -> container.scope.launch { activate(payload.token) }
            is PushPayload.Notify -> {
                showAtOnce(payload)
                container.scope.launch { showNotification(payload) }
            }
            // Deletes mean something was read elsewhere: the number on the icon follows.
            is PushPayload.Delete -> {
                TalqNotifications.cancel(appContext, payload.nids)
                recountAfterRead()
            }
            PushPayload.DeleteAll -> {
                TalqNotifications.cancelAll(appContext)
                recountAfterRead()
            }
        }
    }

    private fun recountAfterRead() {
        if (loggingOut) return
        container.scope.launch {
            val session = awaitSession() ?: return@launch
            if (gone(session)) return@launch
            withTimeoutOrNull(ROOMS_TIMEOUT_MS) { reloadRooms(session).join() }
            container.badge.request()
        }
    }

    /** Signed out (or signed in as someone else) since [session] was taken: nothing of it may be shown or counted. */
    private fun gone(session: TalkSession?): Boolean = loggingOut || (session != null && container.session !== session)

    private var roomsJob: Job? = null
    private var roomsFor: TalkSession? = null
    private var roomsAgain = false

    /**
     * Reload the conversation list for a push. A burst of pushes shares one request: while one
     * is running, further calls only ask for one more after it (what they are about may be
     * newer than its answer). The returned job ends when the list is as new as the call.
     * Main thread only.
     */
    private fun reloadRooms(session: TalkSession): Job {
        val running = roomsJob
        if (running != null && running.isActive && roomsFor === session) {
            roomsAgain = true
            return running
        }
        roomsFor = session
        roomsAgain = false
        return container.scope.launch {
            do {
                roomsAgain = false
                withTimeoutOrNull(ROOMS_TIMEOUT_MS) { session.rooms.refresh() }
            } while (roomsAgain && !gone(session))
        }.also { roomsJob = it }
    }

    // ------------------------------------------------------------------ step 3

    private suspend fun activate(token: String) {
        val session = awaitSession() ?: return
        val result = try {
            session.client.webPushActivate(token)
        } catch (e: TalkError) {
            fail(PushStatus.Error(e.userMessage()))
            return
        }
        when (result) {
            is WebPushResult.Ok -> succeed()
            WebPushResult.Disabled -> fail(PushStatus.ServerDisabled)
            WebPushResult.Unsupported ->
                fail(PushStatus.Error("This server's notifications app is too old for push to $appName."))
            is WebPushResult.Rejected -> fail(PushStatus.Error(rejectionMessage(result)))
        }
    }

    // ------------------------------------------------------------------ notifications

    /**
     * Put the notification up from what the push itself says, before this callback returns.
     * The details ([showNotification]) need up to three requests, and nothing keeps a process
     * that a push started alive for them: without this the notification could be late or lost.
     * The detailed one replaces this one silently (same id).
     */
    private fun showAtOnce(push: PushPayload.Notify) {
        if (loggingOut) return
        val content = describe(push, null, container.session).let {
            it.copy(topicKnown = it.threadId > 0 || !it.aboutChat)
        }
        // A view of this conversation is on screen. Which topic the message is in is not known
        // yet, so whether it is the one being read is decided once the details are in.
        if (content.token.isNotEmpty() && TalqNotifications.openChat?.token == content.token) return
        // Possibly a muted conversation or topic: nothing is shown until that is known.
        if (content.aboutChat &&
            container.gate.quick(content.token, content.threadId) == NotificationGate.Quick.Wait
        ) {
            return
        }
        try {
            TalqNotifications.show(appContext, content)
        } catch (e: Exception) {
            Log.w(TAG, "notification ${push.nid} could not be shown", e)
        }
    }

    private suspend fun showNotification(push: PushPayload.Notify) {
        // Any real notification proves the subscription works.
        if (_status.value != PushStatus.Active && !loggingOut) succeed()

        val session = awaitSession()
        if (gone(session)) return dropped(push)
        // The conversation list, reloaded alongside: it says whether the conversation is muted
        // and is what the number on the app icon is counted from.
        val roomsReload = session?.let { reloadRooms(it) }
        var detail: TalkNotification? = null
        if (session != null) {
            try {
                detail = withTimeoutOrNull(FETCH_TIMEOUT_MS) { session.client.notification(push.nid) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: TalkError.NotFound) {
                // Already read or dismissed elsewhere: nothing to show.
                withTimeoutOrNull(ROOMS_TIMEOUT_MS) { roomsReload?.join() }
                return dropped(push)
            } catch (e: Exception) {
                Log.w(TAG, "notification ${push.nid} could not be fetched", e)
            }
        }
        withTimeoutOrNull(ROOMS_TIMEOUT_MS) { roomsReload?.join() }
        var content = describe(push, detail, session)
        // Which topic the message is in: named by the notification, or asked from the message.
        // "In no topic" and "could not be found out" are different answers.
        var topicKnown = content.threadId > 0 || (session != null && !session.supportsTopics)
        if (!topicKnown && session != null && content.token.isNotEmpty() && content.messageId > 0) {
            var topicId = topicOf(session, content.token, content.messageId)
            if (topicId == null && content.aboutChat && !gone(session) &&
                container.gate.hasSilencedTopics(session, content.token)
            ) {
                // It decides whether this may be shown at all: one more go.
                topicId = topicOf(session, content.token, content.messageId)
            }
            if (topicId != null) {
                topicKnown = true
                if (topicId > 0) {
                    content = content.copy(
                        threadId = topicId,
                        topicTitle = session.topics(content.token).topic(topicId)?.thread?.title.orEmpty(),
                    )
                }
            }
        }
        content = content.copy(topicKnown = topicKnown || !content.aboutChat)
        // The requests above take seconds; signing out meanwhile empties the stored levels.
        if (gone(session)) return dropped(push)
        if (content.aboutChat) {
            // Never means never: a muted conversation or topic shows nothing, whatever this is
            // about (the server still sends reactions for a muted topic).
            if (!topicKnown && container.gate.hasSilencedTopics(session, content.token)) {
                // This conversation has a muted topic and the message may be in it. A muted
                // topic must never fire, so this one is not shown; the icon number still counts it
                // when it is not muted.
                Log.i(TAG, "notification ${push.nid} not shown: its topic could not be found out and the conversation has a muted topic")
                return dropped(push)
            }
            val verdict = container.gate.check(session, content.token, content.threadId)
            if (verdict.blocked || gone(session)) return dropped(push)
            val title = verdict.topic?.title.orEmpty()
            if (content.topicTitle.isEmpty() && title.isNotEmpty()) content = content.copy(topicTitle = title)
        }
        // Count first (briefly), so the number this notification carries includes its message.
        if (session != null) withTimeoutOrNull(RECOUNT_TIMEOUT_MS) { container.badge.refreshNow(session) }
        if (gone(session)) return dropped(push)
        val roomUnread = container.badge.roomCount(content.token)
            ?: session?.rooms?.room(content.token)?.takeIf { it.notificationLevel != NotificationLevel.NEVER }?.unreadMessages
            ?: 0
        TalqNotifications.show(appContext, content, roomUnread)
        container.badge.request()
    }

    /** Not shown after all: take the first, undetailed version down too, and count. */
    private fun dropped(push: PushPayload.Notify) {
        TalqNotifications.cancel(appContext, listOf(push.nid))
        if (!loggingOut) container.badge.request()
    }

    /**
     * The topic [messageId] is in: 0 when it is in none, null when that could not be found out
     * (no answer in time, a failed request, the message not in the answer).
     */
    private suspend fun topicOf(session: TalkSession, token: String, messageId: Long): Long? = try {
        withTimeoutOrNull(TOPIC_LOOKUP_TIMEOUT_MS) {
            session.client.history(token, lastKnownMessageId = messageId, limit = 1, includeLastKnown = true)
                .messages.firstOrNull { it.id == messageId }?.topicId
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "topic of message $messageId could not be looked up", e)
        null
    }

    /**
     * Turn the push (and, when it could be fetched, the full notification) into what to draw.
     * Without [detail] only the server's subject line is known, e.g. "Ana mentioned you in Team".
     */
    private fun describe(push: PushPayload.Notify, detail: TalkNotification?, session: TalkSession?): NotificationContent {
        val isTalk = push.isTalk
        // Talk object ids: "{token}/{messageId}" and "/{threadId}" inside a topic.
        val idParts = push.objectId.split('/')
        val conversational = isTalk && push.objectType in setOf("chat", "room", "call", "reminder", "recording")
        val token = when {
            !conversational -> ""
            detail != null && detail.roomToken.isNotEmpty() -> detail.roomToken
            else -> idParts.firstOrNull().orEmpty()
        }
        val link = detail?.link.orEmpty()
        val messageId = detail?.messageId?.takeIf { it > 0 }
            ?: idParts.getOrNull(1)?.toLongOrNull()
            ?: MESSAGE_IN_LINK.find(link)?.groupValues?.get(1)?.toLongOrNull()
            ?: 0L
        val threadId = detail?.threadId?.takeIf { it > 0 }
            ?: idParts.getOrNull(2)?.toLongOrNull()
            ?: THREAD_IN_LINK.find(link)?.groupValues?.get(1)?.toLongOrNull()
            ?: 0L

        val room = if (token.isNotEmpty()) session?.rooms?.room(token) else null
        val subjectObjects = detail?.subjectRichParameters.orEmpty()
        val title = subjectObjects["call"]?.name?.takeIf { it.isNotEmpty() }
            ?: room?.displayName?.takeIf { it.isNotEmpty() }
            ?: Brand.appName(appContext)
        val sender = (subjectObjects["user"] ?: subjectObjects["actor"])?.name.orEmpty()
        val body = detail?.message.orEmpty()
        val topicTitle =
            if (threadId > 0 && token.isNotEmpty()) session?.topics(token)?.topic(threadId)?.thread?.title.orEmpty() else ""

        val myId = session?.user?.userId
        val mentionsMe = detail?.messageRichParameters.orEmpty().any { (key, obj) ->
            key.startsWith("mention-") && (obj.type != "user" || obj.id == myId)
        }
        val kind = when {
            push.objectType == "call" -> NotificationKind.Call
            mentionsMe -> NotificationKind.Mention
            else -> NotificationKind.Message
        }
        val oneToOne = room?.type == RoomType.ONE_TO_ONE || (room == null && sender.isNotEmpty() && sender == title)

        return NotificationContent(
            nid = push.nid,
            kind = kind,
            token = token,
            threadId = threadId,
            messageId = messageId,
            title = title,
            topicTitle = topicTitle,
            // With no message text the subject line already names the sender: show it whole.
            sender = if (body.isNotEmpty()) sender else "",
            text = body.ifEmpty { detail?.subject.orEmpty() }.ifEmpty { push.subject }.ifEmpty { "New notification" },
            whenMillis = parseTime(detail?.datetime),
            isGroup = !oneToOne,
            aboutChat = isTalk && push.objectType == "chat" && token.isNotEmpty(),
        )
    }

    private fun parseTime(iso: String?): Long {
        if (iso.isNullOrEmpty()) return System.currentTimeMillis()
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The open session, waiting while stored credentials are still being restored (a push can
     * start the process). Null when nobody is signed in or the server cannot be reached.
     */
    private suspend fun awaitSession(): TalkSession? {
        val state = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            container.sessionState.first { it !is SessionState.Loading }
        }
        return (state as? SessionState.SignedIn)?.session
    }

    private fun succeed() {
        watchdog?.cancel()
        _status.value = PushStatus.Active
    }

    private fun fail(status: PushStatus) {
        watchdog?.cancel()
        _status.value = status
    }

    private fun startWatchdog(message: String) {
        watchdog?.cancel()
        watchdog = container.scope.launch {
            delay(ANSWER_TIMEOUT_MS)
            if (_status.value == PushStatus.Registering) _status.value = PushStatus.Error(message)
        }
    }

    private fun rejectionMessage(result: WebPushResult.Rejected): String = when (result.reason) {
        "INVALID_ENDPOINT" -> "The server does not accept your push app's address. It must be a public https address."
        "INVALID_ACTIVATION_TOKEN" -> "The server did not accept the test push. Try again."
        "NO_PUSH_SUBSCRIPTION" -> "The server lost the push subscription. Try again."
        else -> "The server refused the push subscription (${result.reason.ifEmpty { result.status.toString() }})."
    }

    companion object {
        private const val TAG = "TalqPush"

        /** One account, one registration. */
        const val INSTANCE = "default"

        /** `talk` also matches the server's `spreed` and `admin_notification_talk` apps. */
        private const val APP_TYPES = "talk"

        private const val SESSION_TIMEOUT_MS = 15_000L
        private const val FETCH_TIMEOUT_MS = 8_000L
        private const val TOPIC_LOOKUP_TIMEOUT_MS = 5_000L
        private const val ROOMS_TIMEOUT_MS = 6_000L
        private const val RECOUNT_TIMEOUT_MS = 2_000L
        private const val ANSWER_TIMEOUT_MS = 60_000L

        private val MESSAGE_IN_LINK = Regex("#message_(\\d+)")
        private val THREAD_IN_LINK = Regex("[?&]threadId=(\\d+)")
    }
}
