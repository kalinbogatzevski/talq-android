package talq.app.push

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.ResolvedDistributor
import talq.app.AppContainer
import talq.app.Brand
import talq.app.BuildConfig
import talq.app.SessionState
import talq.app.ui.common.userMessage
import talq.app.ui.message.plainPreview
import talq.core.TalkError
import talq.core.WebPushRegistration
import talq.core.WebPushResult
import talq.core.model.ChatMessage
import talq.core.model.NotificationLevel
import talq.core.model.RoomType
import talq.core.model.TalkNotification
import talq.core.model.mentionsByName
import talq.core.repo.TalkSession
import java.time.OffsetDateTime

/**
 * Push through UnifiedPush: a distributor holds the connection, the server's notifications app
 * sends Web Push to the distributor's endpoint, and the connector library decrypts it for us.
 * The distributor is an app such as ntfy, or, in the google variant, Google Play services
 * through the embedded FCM distributor in this app (our own package name in the list). The
 * Google route is the default where Play services exist; Settings can switch ([useRoute]).
 * The route last in use is remembered outside the connector (which forgets its distributor at
 * every sign-out), so a sign-out does not move anyone from their own choice back to Google.
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

    /**
     * Bumped by every route switch. A registration with the server that started before a switch
     * and finishes after it may have overwritten the new endpoint with the old one: it then
     * registers again instead of reporting ([onNewEndpoint]).
     */
    @Volatile
    private var routeGeneration = 0

    /** The route that was in use when it last worked: names it after the connector forgot it. */
    @Volatile
    private var lastRoute: String? = null

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _routes = MutableStateFlow(PushRoutes())
    override val routes: StateFlow<PushRoutes> = _routes.asStateFlow()

    init {
        TalqNotifications.ensureChannels(appContext)
    }

    // ------------------------------------------------------------------ PushRegistrar

    override suspend fun onLoggedIn() {
        loggingOut = false
        setUp()
    }

    override fun retry() {
        // At once, so whatever offered the retry (Settings, the list's notice) shows it running.
        if (container.session != null && !loggingOut) {
            watchdog?.cancel()
            _status.value = PushStatus.Registering
        }
        container.scope.launch { setUp() }
    }

    override fun refreshRoutes() {
        container.scope.launch { updateRoutes() }
    }

    /** Package manager and the connector's database: off the main thread. */
    private suspend fun updateRoutes() {
        val routes = withContext(Dispatchers.IO) {
            val all = try {
                UnifiedPush.getDistributors(appContext).map { pkg ->
                    val builtIn = pkg == appContext.packageName
                    PushRoute(pkg, if (builtIn) GOOGLE_ROUTE else labelOf(pkg), builtIn)
                }.sortedByDescending { it.builtIn }
            } catch (e: Exception) {
                Log.w(TAG, "distributor list failed", e)
                emptyList()
            }
            PushRoutes(all, currentRoute())
        }
        _routes.value = routes
    }

    /** The distributor in use per the connector, or null. Not from the main thread. */
    private fun currentRoute(): String? = try {
        UnifiedPush.getAckDistributor(appContext) ?: UnifiedPush.getSavedDistributor(appContext)
    } catch (e: Exception) {
        null
    }

    override fun useRoute(packageName: String) {
        container.scope.launch {
            if (withContext(Dispatchers.IO) { currentRoute() } == packageName) return@launch
            routeGeneration++
            try {
                // Makes it the primary distributor and tells the previous one to drop us (the
                // connector discards that one's answer); the registration below hands the
                // server the new endpoint, which replaces the old one.
                withContext(Dispatchers.IO) { UnifiedPush.saveDistributor(appContext, packageName) }
            } catch (e: Exception) {
                Log.w(TAG, "switching distributor failed", e)
                return@launch
            }
            remember(packageName)
            watchdog?.cancel()
            _status.value = PushStatus.Registering
            setUp()
        }
    }

    /** The user's route, kept across sign-outs (the connector forgets its own at each one). */
    private fun remember(packageName: String) {
        lastRoute = packageName
        prefs.edit().putString(KEY_ROUTE, packageName).apply()
    }

    private fun labelOf(pkg: String): String = try {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** Push goes through Google Play services (the distributor built into this app). */
    private fun viaGoogle(): Boolean = (lastRoute ?: prefs.getString(KEY_ROUTE, null)) == appContext.packageName

    /** Who to name in a message: Google Play services, or the user's push app. */
    private fun pushApp(): String = if (viaGoogle()) GOOGLE_ROUTE else "Your push app"

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
        val chosen = withContext(Dispatchers.IO) { chooseDistributor() }
        updateRoutes()
        if (!chosen) {
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
            return fail(PushStatus.Error(e.userMessage(), network = e is TalkError.Network))
        }
        try {
            // Passing the server's VAPID key makes the connector create the Web Push key pair
            // and decrypt incoming messages itself. The answer comes back as onNewEndpoint
            // or onRegistrationFailed.
            UnifiedPush.register(appContext, INSTANCE, null, vapid)
        } catch (e: Exception) {
            Log.w(TAG, "UnifiedPush.register failed", e)
            return fail(PushStatus.Error("${pushApp()} refused the registration."))
        }
        startWatchdog(
            if (viaGoogle()) "$GOOGLE_ROUTE did not answer. Check the connection, then try again."
            else "Your push app did not answer. Open it once, then try again.",
        )
    }

    /** True when a distributor is (now) selected. Never shows UI: this runs without an activity. */
    private fun chooseDistributor(): Boolean {
        try {
            val inUse = UnifiedPush.getAckDistributor(appContext) ?: UnifiedPush.getSavedDistributor(appContext)
            if (inUse != null) {
                remember(inUse)
                return true
            }
            val available = UnifiedPush.getDistributors(appContext)
            // The route the user had before (kept across sign-outs), if it is still there.
            val remembered = prefs.getString(KEY_ROUTE, null)
            if (remembered != null && remembered in available) {
                UnifiedPush.saveDistributor(appContext, remembered)
                remember(remembered)
                return true
            }
            // The google variant lists itself when Play services are there: that route needs
            // nothing else installed, so it is the default. Another app stays selectable.
            if (BuildConfig.GOOGLE_PUSH && appContext.packageName in available) {
                UnifiedPush.saveDistributor(appContext, appContext.packageName)
                remember(appContext.packageName)
                return true
            }
            val resolved = UnifiedPush.resolveDefaultDistributor(appContext)
            if (resolved is ResolvedDistributor.Found) {
                UnifiedPush.saveDistributor(appContext, resolved.packageName)
                remember(resolved.packageName)
                return true
            }
            // Several installed and no system default (or none at all): take the first one
            // rather than interrupting with a chooser.
            val first = available.firstOrNull() ?: return false
            UnifiedPush.saveDistributor(appContext, first)
            remember(first)
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
                fail(PushStatus.Error("${pushApp()} is too old: it does not support encrypted push."))
                return@launch
            }
            _status.value = PushStatus.Registering
            val generation = routeGeneration
            val result = try {
                session.client.webPushRegister(endpoint.url, keys.pubKey, keys.auth, APP_TYPES)
            } catch (e: TalkError) {
                if (generation != routeGeneration) return@launch
                fail(PushStatus.Error(e.userMessage(), network = e is TalkError.Network))
                return@launch
            }
            if (generation != routeGeneration) {
                // The route changed while this was on its way: the server may now hold this
                // (old) endpoint. Register again through the route in use.
                Log.i(TAG, "push route changed during registration; registering again")
                setUp()
                return@launch
            }
            when (result) {
                is WebPushResult.Ok -> when (result.value) {
                    WebPushRegistration.ALREADY_ACTIVE -> succeed()
                    WebPushRegistration.CREATED_NEEDS_ACTIVATION ->
                        startWatchdog(
                            if (viaGoogle()) "The server's test push did not arrive through $GOOGLE_ROUTE. Try again in a moment."
                            else "The server's test push did not arrive. Check that your push app is running and connected.",
                        )
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
                network = reason == FailedReason.NETWORK,
                message = when (reason) {
                    FailedReason.NETWORK -> "${pushApp()} has no connection right now."
                    FailedReason.ACTION_REQUIRED ->
                        if (viaGoogle()) "$GOOGLE_ROUTE could not register $appName. Check that it is up to date and signed in."
                        else "Your push app needs attention. Open it and finish its setup."
                    else -> "${pushApp()} could not register $appName."
                },
            ),
        )
    }

    /** The distributor dropped us (uninstalled, account removed there, ...). */
    fun onUnregistered() {
        if (loggingOut) return
        // The connector has already forgotten the route (it unregisters before calling us), so
        // what was lost is the route last in use.
        val lost = lastRoute
        container.scope.launch {
            try {
                container.session?.client?.webPushUnregister()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "server unregister failed", e)
            }
            if (container.session == null) return@launch
            val available = withContext(Dispatchers.IO) {
                try {
                    UnifiedPush.getDistributors(appContext)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val google = appContext.packageName
            // A push app went away (uninstalled, subscription removed there) and Google Play
            // services are here: carry on through them. One step only, never back and forth:
            // a route that keeps refusing must not loop.
            if (BuildConfig.GOOGLE_PUSH && lost != null && lost != google && google in available) {
                Log.i(TAG, "push app $lost dropped us; switching to $GOOGLE_ROUTE")
                prefs.edit().remove(KEY_ROUTE).apply()
                setUp()
                return@launch
            }
            updateRoutes()
            val who = if (lost == google) GOOGLE_ROUTE else "Your push app"
            fail(if (available.isNotEmpty()) PushStatus.Error("$who dropped $appName's subscription.") else PushStatus.NoDistributor)
        }
    }

    /** A decrypted push from the server. */
    fun onMessage(content: ByteArray) {
        talq.app.diag.DiagStore.pushReceived(appContext)
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
            // A call rings like a phone call (CallRinger), in a group conversation too; a call
            // push the ringer cannot place stays an ordinary notification.
            is PushPayload.Notify -> if (payload.isTalk && payload.objectType == "call") {
                if (loggingOut) return
                container.scope.launch {
                    if (!container.ringer.ring(payload.nid, payload.objectId.substringBefore('/'), payload.subject)) {
                        showAtOnce(payload)
                        showNotification(payload)
                    }
                }
            } else {
                showAtOnce(payload)
                container.scope.launch { showNotification(payload) }
            }
            // Deletes mean something was read elsewhere: the number on the icon follows. A
            // ringing call whose notification goes was answered or dismissed elsewhere.
            is PushPayload.Delete -> {
                TalqNotifications.cancel(appContext, payload.nids)
                container.scope.launch { container.ringer.stop(payload.nids) }
                recountAfterRead()
            }
            PushPayload.DeleteAll -> {
                TalqNotifications.cancelAll(appContext)
                container.scope.launch { container.ringer.stopAll() }
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
            fail(PushStatus.Error(e.userMessage(), network = e is TalkError.Network))
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
     *
     * The alert it goes to ([kindOf]) is known at once in a one-to-one chat. In any other the push does not say
     * whether it mentions the user, so it is put up among the other messages; where the user has set the two
     * alerts differently, its sound waits for the details ([soundLater]).
     */
    private fun showAtOnce(push: PushPayload.Notify) {
        if (loggingOut) return
        val content = describe(push, null, container.session).let {
            it.copy(topicKnown = it.threadId > 0 || !it.aboutChat)
        }
        // A view of this conversation is on screen. Which topic the message is in is not known
        // yet, so whether it is the one being read is decided once the details are in.
        if (content.aboutChat && TalqNotifications.openChat?.token == content.token) return
        // Possibly a muted conversation or topic: nothing is shown until that is known.
        if (content.aboutChat &&
            container.gate.quick(content.token, content.threadId) == NotificationGate.Quick.Wait
        ) {
            return
        }
        try {
            val later = !content.kindKnown && !TalqNotifications.messagesAlertAlike(appContext)
            TalqNotifications.show(appContext, content, alert = if (later) Alert.Later else Alert.Once)
            if (later) soundLater(content)
        } catch (e: Exception) {
            Log.w(TAG, "notification ${push.nid} could not be shown", e)
        }
    }

    /** Notifications put up without their sound, until it is known which alert they belong to. Main thread only. */
    private val soundPending = HashSet<Long>()

    /** Keeps the phone awake for the few seconds a sound waits: in its sleep no timer runs. */
    private val awake: PowerManager.WakeLock? by lazy {
        appContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "talq:alert")?.apply { setReferenceCounted(false) }
    }

    /**
     * The sound of [content], just put up without one: the details make it ([soundNow]), on the alert they say. When
     * they take too long it sounds as what it is for sure, another message, and not a second time after that. Where
     * those make no sound it goes on waiting: the details may still find a mention.
     */
    private fun soundLater(content: NotificationContent) {
        soundPending.add(content.nid)
        awake?.acquire(SOUND_WAIT_MS + 500L)
        container.scope.launch {
            delay(SOUND_WAIT_MS)
            if (content.nid !in soundPending || TalqNotifications.alertState(appContext, content.kind) != AlertState.On) return@launch
            if (!gone(null) && TalqNotifications.isUp(appContext, content.nid)) soundNow(content)
        }
    }

    /** It is known which alert [content] belongs to: the sound that waited for that, if one did. */
    private fun soundNow(content: NotificationContent) {
        if (soundPending.remove(content.nid)) TalqNotifications.show(appContext, content, alert = Alert.Now)
    }

    private suspend fun showNotification(push: PushPayload.Notify) {
        // Any real notification proves the subscription works.
        if (_status.value != PushStatus.Active && !loggingOut) succeed()

        val session = awaitSession()
        if (gone(session)) return dropped(push)
        // Whether an unread message of the conversation names the user already; null = the list is not there yet.
        var namedBefore: Boolean? = null
        // The conversation's newest messages, loaded alongside (one run per room at a time): a tap
        // on this notification then opens on them instead of a spinner. Not for the one on screen.
        if (session != null) {
            val early = describe(push, null, session)
            namedBefore = session.rooms.room(early.token)?.unreadMentionDirect
            if (early.aboutChat && TalqNotifications.openChat?.token != early.token) session.prefetchChat(early.token)
        }
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
        if (gone(session)) return dropped(push)
        // Named or one-to-one is known now; whether it answers the user, only with the message. What was taken down
        // meanwhile (muted elsewhere, read, swiped off) does not come back for its sound.
        if (detail != null && push.nid in soundPending && TalqNotifications.isUp(appContext, push.nid)) {
            val early = describe(push, detail, session)
            if (early.kind == NotificationKind.Direct) soundNow(early.copy(topicKnown = early.threadId > 0 || !early.aboutChat))
        }
        withTimeoutOrNull(ROOMS_TIMEOUT_MS) { roomsReload?.join() }
        var content = describe(push, detail, session)
        // Which topic the message is in: named by the notification, or asked from the message.
        // "In no topic" and "could not be found out" are different answers.
        var topicKnown = content.threadId > 0 || (session != null && !session.supportsTopics)
        var message: ChatMessage? = null
        if (!topicKnown && session != null && content.token.isNotEmpty() && content.messageId > 0) {
            message = messageOf(session, content.token, content.messageId)
            if (message == null && content.aboutChat && !gone(session) &&
                container.gate.hasSilencedTopics(session, content.token)
            ) {
                // It decides whether this may be shown at all: one more go.
                message = messageOf(session, content.token, content.messageId)
            }
            val topicId = message?.topicId
            if (topicId != null) {
                topicKnown = true
                if (topicId > 0) {
                    content = content.copy(
                        threadId = topicId,
                        topicTitle = if (content.sensitive) "" else session.topics(content.token).topic(topicId)?.thread?.title.orEmpty(),
                    )
                }
            }
        }
        // The message itself says whether it answers the user. Where it was not looked at, the conversation's own
        // flag does when it has just come on (it stays on until the message is read).
        val named = namedBefore == false && session?.rooms?.room(content.token)?.unreadMentionDirect == true
        content = content.copy(
            topicKnown = topicKnown || !content.aboutChat,
            kind = kindOf(push, detail, session, content.token, message, named = message == null && named),
        )
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
            if (content.topicTitle.isEmpty() && !content.sensitive) content = content.copy(topicTitle = title)
        }
        soundNow(content)
        // Count first (briefly), so the number this notification carries includes its message.
        if (session != null) withTimeoutOrNull(RECOUNT_TIMEOUT_MS) { container.badge.refreshNow(session) }
        if (gone(session)) return dropped(push)
        val roomUnread = container.badge.roomCount(content.token)
            ?: session?.rooms?.room(content.token)?.takeIf { it.notificationLevel != NotificationLevel.NEVER }?.unreadMessages
            ?: 0
        TalqNotifications.show(appContext, content, roomUnread, container.messageActions.offered(session, content))
        container.badge.request()
    }

    /** Not shown after all: take the first, undetailed version down too, and count. */
    private fun dropped(push: PushPayload.Notify) {
        soundPending.remove(push.nid)
        TalqNotifications.cancel(appContext, listOf(push.nid))
        if (!loggingOut) container.badge.request()
    }

    /**
     * The message [messageId] itself, for the topic it is in ([ChatMessage.topicId], 0 = in none): null when that
     * could not be found out (no answer in time, a failed request, the message not in the answer).
     */
    private suspend fun messageOf(session: TalkSession, token: String, messageId: Long): ChatMessage? = try {
        withTimeoutOrNull(TOPIC_LOOKUP_TIMEOUT_MS) {
            session.client.history(token, lastKnownMessageId = messageId, limit = 1, includeLastKnown = true)
                .messages.firstOrNull { it.id == messageId }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "topic of message $messageId could not be looked up", e)
        null
    }

    /**
     * Which alert the notification belongs to; what is not known is another message.
     *
     * - [NotificationKind.Direct]: a message in a one-to-one chat, one that mentions the user by name, or an answer
     *   to a message of the user's. The last two are TalQ for desktop's `mentionsUser` and `isReplyToUser`
     *   (`src/core/ToastGatePolicy.h`), of the first only the name, and what Talk itself calls a direct mention
     *   (`ChatManager::sendMessage`; the list's `unreadMentionDirect`).
     * - [NotificationKind.Other]: everything else: @all, mentioned groups and teams, reactions (in a one-to-one chat
     *   too), and what is not about a chat message (invitations, reminders, other apps of the server).
     *
     * The push holds the object type `chat` and a translated line for all of these, and the notification no subject
     * in a fixed language either: it is told from the objects the line names and from the message. [message]: the
     * message, where it was looked up (for its topic); [named]: the conversation's own flag has just come on.
     */
    private fun kindOf(
        push: PushPayload.Notify,
        detail: TalkNotification?,
        session: TalkSession?,
        token: String,
        message: ChatMessage?,
        named: Boolean,
    ): NotificationKind {
        if (push.objectType == "call") return NotificationKind.Call
        if (!push.isTalk || push.objectType != "chat" || token.isEmpty()) return NotificationKind.Other
        val subject = detail?.subjectRichParameters.orEmpty()
        // A reaction is about a message of the user's own, and nobody is notified of a message of his own.
        val reaction = "reaction" in subject || (message != null && session?.isMe(message.actorType, message.actorId) == true)
        if (reaction) return NotificationKind.Other
        val me = session?.user?.userId.orEmpty()
        val callType = subject["call"]?.value("call-type").orEmpty()
        val direct = when {
            // Told by the notification, else by the list; from the push alone by the name on its first line.
            detail == null && message == null -> container.gate.isDirectMessage(token, push.subject)
            callType.isNotEmpty() -> callType == "one2one"
            else -> session?.rooms?.room(token)?.isOneToOne == true
        }
        val answer = message?.quoted?.let { session?.isMe(it.actorType, it.actorId) } == true
        return if (direct || answer || named || mentionsByName(detail?.messageRichParameters.orEmpty(), me) ||
            mentionsByName(message?.parameters.orEmpty(), me)
        ) {
            NotificationKind.Direct
        } else {
            NotificationKind.Other
        }
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
        // The server names neither the conversation nor the topic of one marked sensitive (`Notifier::parseChatMessage`).
        val aboutChat = isTalk && push.objectType == "chat" && token.isNotEmpty()
        // Not known (a chat this phone has never listed): as if it were, so no topic name and no shortcut go out.
        val sensitive = container.gate.isSensitive(session, token) ?: aboutChat
        val title = subjectObjects["call"]?.name?.takeIf { it.isNotEmpty() }
            ?: room?.displayName?.takeIf { it.isNotEmpty() && !sensitive }
            ?: Brand.appName(appContext)
        val sender = (subjectObjects["user"] ?: subjectObjects["actor"])?.name.orEmpty()
        // A chat message comes as its raw text (markdown and escapes included): show it as the
        // bubble would, unstyled, from the version with placeholders, so a name with "_" or "*"
        // in it (a file, a person) is not read as markdown. Other notifications are plain already.
        val rich = detail?.messageRich.orEmpty()
        val body = if (aboutChat && rich.isNotEmpty()) plainPreview(rich, detail?.messageRichParameters.orEmpty(), oneLine = false)
        else detail?.message.orEmpty()
        val topicTitle =
            if (threadId > 0 && token.isNotEmpty() && !sensitive) session?.topics(token)?.topic(threadId)?.thread?.title.orEmpty() else ""

        val kind = kindOf(push, detail, session, token, message = null, named = false)
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
            aboutChat = aboutChat,
            kindKnown = detail != null || !aboutChat || kind == NotificationKind.Direct,
            sensitive = sensitive,
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
        refreshRoutes()
    }

    private fun fail(status: PushStatus) {
        watchdog?.cancel()
        _status.value = status
    }

    private fun startWatchdog(message: String) {
        watchdog?.cancel()
        watchdog = container.scope.launch {
            delay(ANSWER_TIMEOUT_MS)
            // No answer is most often no connection: tried again when the network is back.
            if (_status.value == PushStatus.Registering) _status.value = PushStatus.Error(message, network = true)
        }
    }

    private fun rejectionMessage(result: WebPushResult.Rejected): String = when (result.reason) {
        "INVALID_ENDPOINT" ->
            if (viaGoogle()) "The server does not accept the $GOOGLE_ROUTE address. Ask the administrator whether it may reach fcm.googleapis.com."
            else "The server does not accept your push app's address. It must be a public https address."
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

        /** How long a sound waits for the details that say which alert it is. */
        private const val SOUND_WAIT_MS = 3_000L
        private const val ANSWER_TIMEOUT_MS = 60_000L
        private const val PREFS = "push_route"
        private const val KEY_ROUTE = "route"

        /** How the built-in route is named in the app. */
        const val GOOGLE_ROUTE = "Google Play services"

        private val MESSAGE_IN_LINK = Regex("#message_(\\d+)")
        private val THREAD_IN_LINK = Regex("[?&]threadId=(\\d+)")
    }
}
