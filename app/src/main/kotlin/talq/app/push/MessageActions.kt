package talq.app.push

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.AppContainer
import talq.app.SessionState
import talq.app.ui.chat.ComposerDrafts
import talq.app.ui.message.notSentLine
import talq.core.TalkError
import talq.core.repo.ChatScope
import talq.core.repo.OutboxStatus
import talq.core.repo.OutboxStop
import talq.core.repo.RoomAction
import talq.core.repo.TalkSession
import talq.core.repo.UnsentText

/**
 * The buttons of a message notification, "Reply" and "Mark as read" ([MessageActionReceiver]). Talk's and
 * Android's own: TalQ for desktop's notifications have neither.
 *
 * - A reply is an ordinary message through the room's one sender of texts (the chat screen's), into the
 *   notification's topic. First of all the text is safe: in the open session's outbox, or, in a process that
 *   has no session yet (the app was closed), in the same file the outbox keeps its unsent texts in, from
 *   which it goes like any other once the account is open. So it is never lost and never sent twice. Until it
 *   is in the chat the notification shows it with one line on how it stands and no buttons; "Not sent" also
 *   stands in the room and in the list. [ReplyWorker] brings the app back for it.
 * - Sent, the conversation is read up to it (Talk's server does that with the user's own post; it is made
 *   sure and shown). The notification that was answered goes, with the older ones of its topic; others stay.
 * - "Mark as read" moves the conversation's read marker to the message shown, the way reading it in its own
 *   tab does ([talq.core.repo.UnreadTracker.markSeen]): never back, and in a conversation with topics never past
 *   an unread message of another one. The marker is asked for first: the server writes the one it is sent,
 *   also an older one than another device has set.
 *
 * Main thread only. No text and no token ever goes to the log.
 */
class MessageActions(context: Context, private val container: AppContainer) {
    private val appContext = context.applicationContext

    /** Notifications being marked as read. */
    private val marking = HashSet<Long>()

    /** Replies on their way, by outbox key. */
    private val following = HashMap<String, Job>()

    /** Replies this process has seen through to the end: nothing is left to do about them. */
    private val over = HashSet<String>()

    /**
     * What the buttons of the notification [content] act on; null = it gets none: nobody signed in, not about
     * a chat message, or a conversation that is not in the list.
     */
    fun offered(session: TalkSession?, content: NotificationContent): MessageTarget? {
        // The topic decides where a reply goes and what is read: no guessing.
        if (session == null || container.session !== session || !content.aboutChat || !content.topicKnown) return null
        val room = session.rooms.room(content.token) ?: return null
        val caps = session.capabilities
        val canReply = room.takesPosts(caps, System.currentTimeMillis() / 1000)
        // The id shown for a conversation on another server is not checked: there only "everything" can be marked.
        val canMarkRead = caps.readMarker && if (room.isFederated) caps.readLast else content.messageId > 0
        if (!canReply && !canMarkRead) return null
        return MessageTarget(
            nid = content.nid,
            account = ComposerDrafts.accountOf(session),
            token = content.token,
            topicId = content.threadId,
            messageId = content.messageId,
            canReply = canReply,
            canMarkRead = canMarkRead,
        )
    }

    // ------------------------------------------------------------------ reply

    /** Send what the user [typed] in the notification of [target]. [done]: the receiver may go (once, soon). */
    fun reply(target: MessageTarget, typed: String, done: () -> Unit) {
        val text = typed.trim()
        if (text.isEmpty()) {
            // Nothing to send: only the system's own "sending" sign goes.
            TalqNotifications.ready(appContext, target, null)
            return done()
        }
        var finished = false
        fun finish() {
            if (!finished) done()
            finished = true
        }
        container.scope.launch {
            delay(RECEIVER_MS)
            finish()
        }
        container.scope.launch {
            try {
                val ref = handOver(target, text)
                TalqNotifications.replying(appContext, target, ref, text, if (ref != null) SENDING else "Not sent")
                if (ref != null) {
                    ReplyWorker.start(appContext, target.token, ref, target.nid)
                    settle(target.token, ref, target.nid)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reply from notification ${target.nid}: ${e::class.simpleName}")
            } finally {
                finish()
            }
        }
    }

    /** Make [text] safe, then it is on its way: its outbox key. Null = there was no way to keep it. */
    private suspend fun handOver(target: MessageTarget, text: String): String? {
        ownSession(target)?.let { return send(it, target, text) }
        val kept = UnsentText.fresh(target.token, text, target.topicId, System.currentTimeMillis())
        if (container.keepText(target.account, kept)) return kept.referenceId
        // The account is being opened this moment (or the phone's storage refuses): the session takes it.
        container.restoreIfUnreachable()
        awaitState()
        return ownSession(target)?.let { send(it, target, text) }
    }

    private fun ownSession(target: MessageTarget): TalkSession? =
        container.session?.takeIf { ComposerDrafts.accountOf(it) == target.account }

    /** As the chat screen of that tab sends. */
    private fun send(session: TalkSession, target: MessageTarget, text: String): String =
        session.chat(target.token, if (target.topicId > 0) ChatScope.Topic(target.topicId) else ChatScope.All).send(text)

    /**
     * Stay with the reply [ref] of notification [nid] until it is in the chat, or waits for the user, for a
     * minute at most. False: it still goes by itself, so come back for it later.
     */
    suspend fun settle(token: String, ref: String, nid: Long): Boolean {
        container.restoreIfUnreachable()
        val state = awaitState()
        val session = (state as? SessionState.SignedIn)?.session
        if (session == null) {
            // Offline this comes at once, and an update only counts once the system has taken the one before it.
            delay(FLICKER_MS)
            val line = when (state) {
                is SessionState.SignedOut -> "Not sent"
                is SessionState.Unreachable if state.error !is TalkError.Network -> "Not sent. Trying again soon."
                else -> "Not sent. Waiting for network."
            }
            TalqNotifications.replyLine(appContext, nid, ref, line)
            // Signed out: nothing of the account is unsent any more (or its storage could not be read: the next start tries).
            return state is SessionState.SignedOut
        }
        if (ref in over) return true
        val job = follow(session, token, ref, nid)
        // Kept from an earlier run, it may have been waiting for a better moment: this is one.
        session.tryUnsent()
        withTimeoutOrNull(SETTLE_MS) { job.join() }
        if (job.isCompleted) return true
        val out = session.chat(token).state.value.outbox.firstOrNull { it.referenceId == ref } ?: return true
        return out.status == OutboxStatus.FAILED && (out.stop == OutboxStop.REFUSED || out.stop == OutboxStop.UNCLEAR)
    }

    /** Keep the notification of reply [ref] saying how it stands, for as long as this process lives; once per reply. */
    private fun follow(session: TalkSession, token: String, ref: String, nid: Long): Job {
        following[ref]?.takeIf { it.isActive }?.let { return it }
        val job = container.scope.launch(start = CoroutineStart.LAZY) {
            try {
                val began = SystemClock.elapsedRealtime()
                val chat = session.chat(token)
                val fate = combine(chat.sent, chat.state) { sent, s -> sent[ref] to s.outbox.firstOrNull { it.referenceId == ref } }
                    .distinctUntilChanged()
                val (messageId, _) = coroutineScope {
                    val lines = launch {
                        fate.collectLatest { (_, out) ->
                            if (out == null) return@collectLatest
                            // One kept from an earlier run says "not sent" for the moment until it is looked for.
                            if (out.status == OutboxStatus.FAILED) delay(FLICKER_MS)
                            TalqNotifications.replyLine(appContext, nid, ref, if (out.status == OutboxStatus.FAILED) notSentLine(out) else SENDING)
                        }
                    }
                    fate.first { (id, out) -> id != null || out == null }.also { lines.cancel() }
                }
                // Signed out meanwhile: its notifications are gone, and nothing more is done in its name.
                if (container.session !== session) return@launch
                // Right after a reply the system keeps a notification it is asked to take down.
                delay((SHOWN_MS - (SystemClock.elapsedRealtime() - began)).coerceAtLeast(0L))
                if (messageId == null) {
                    TalqNotifications.replyGone(appContext, nid, ref)
                } else {
                    TalqNotifications.replied(appContext, token, nid, ref)
                    answered(session, token, messageId)
                }
                over.add(ref)
            } finally {
                following.remove(ref)
            }
        }
        following[ref] = job
        job.start()
        return job
    }

    /**
     * The user answered in [token] with message [messageId]: read up to it. The list is reloaded first (it
     * mostly says so by then), so a marker that is further on is never sent back.
     */
    private suspend fun answered(session: TalkSession, token: String, messageId: Long) {
        if (!session.rooms.refresh()) return
        val room = session.rooms.room(token) ?: return
        when {
            !session.capabilities.readMarker -> Unit
            !room.isFederated -> session.unread(token).markSeen(ChatScope.All, messageId)
            session.capabilities.readLast -> withTimeoutOrNull(READ_ALL_MS) { readAll(session, token) }
        }
    }

    // ------------------------------------------------------------------ mark as read

    /** Mark what the notification of [target] shows as read. [done]: the receiver may go. */
    fun markRead(target: MessageTarget, done: () -> Unit) {
        if (!marking.add(target.nid)) return done()
        TalqNotifications.working(appContext, target.nid, "Marking as read…")
        container.scope.launch {
            var read = false
            try {
                read = withTimeoutOrNull(RECEIVER_MS) { readUpTo(target) } == true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "mark as read from notification ${target.nid}: ${e::class.simpleName}")
            } finally {
                marking.remove(target.nid)
                if (read) {
                    // On another server everything was marked, up to its last message.
                    val everything = ownSession(target)?.rooms?.room(target.token)?.isFederated == true
                    TalqNotifications.cancelRead(
                        appContext, target.token,
                        topicId = if (everything) null else target.topicId,
                        upTo = if (everything) Long.MAX_VALUE else target.messageId,
                        nid = target.nid,
                    )
                } else {
                    TalqNotifications.ready(appContext, target, "Not marked as read")
                }
                done()
            }
        }
    }

    private suspend fun readUpTo(target: MessageTarget): Boolean {
        container.restoreIfUnreachable()
        awaitState()
        val session = ownSession(target) ?: return false
        if (!session.rooms.refresh()) return false
        // Not in the conversation any more: nothing there is unread.
        val room = session.rooms.room(target.token) ?: return true
        if (room.isFederated) return readAll(session, target.token)
        val scope = when {
            target.topicId > 0 -> ChatScope.Topic(target.topicId)
            hasTopics(session, target.token) -> ChatScope.General
            else -> ChatScope.All
        }
        return session.unread(target.token).markSeen(scope, target.messageId)
    }

    /** Asked when not known; "yes" when it could not be found out (then no topic's unread messages are passed over). */
    private suspend fun hasTopics(session: TalkSession, token: String): Boolean {
        if (!session.supportsTopics) return false
        if (token in session.roomsWithTopics.value) return true
        val topics = session.topics(token)
        return !topics.refresh() || topics.topics.value.isNotEmpty()
    }

    /** As the list's own "Mark as read". */
    private suspend fun readAll(session: TalkSession, token: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        if (!session.roomActions.run(token, RoomAction.MarkRead) { answer.complete(it == null) }) return false
        return answer.await()
    }

    // ------------------------------------------------------------------ helpers

    /** Whether somebody is signed in, waiting while the stored account is being opened (a button can start the app); null = not yet. */
    private suspend fun awaitState(): SessionState? =
        withTimeoutOrNull(SESSION_MS) { container.sessionState.first { it !is SessionState.Loading } }

    private companion object {
        const val TAG = "TalqPush"
        const val SENDING = "Sending…"

        /** A receiver that takes ten seconds is an error (a slow start of the app counts). */
        const val RECEIVER_MS = 7_500L
        const val SESSION_MS = 20_000L
        const val SETTLE_MS = 60_000L
        const val READ_ALL_MS = 15_000L

        /** A "not sent" shorter than this is not shown. */
        const val FLICKER_MS = 1_500L
        const val SHOWN_MS = 1_000L
    }
}
