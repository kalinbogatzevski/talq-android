package talq.app.push

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.SessionState
import talq.app.data.NotifyLevelStore
import talq.core.model.NotificationLevel
import talq.core.model.TalkThreadInfo
import talq.core.repo.TalkSession

/**
 * "Never means never": whether a notification about a conversation, or a topic in it, may be
 * shown at all. The rule is the desktop app's (and Talk's own):
 *
 * - a topic's own level wins, unless it is "default", which follows the conversation;
 * - "never" = nothing: no message, no mention, no reply, no reaction.
 *
 * The server already holds back messages, mentions and replies of a "never" topic. It does
 * not hold back reactions (it only looks at the conversation's level for those), and a
 * level changed a moment ago may not have reached a push that was already on its way, so
 * every push is judged here as well. Only "never" is judged: "mentions only" is left to the
 * server, which also knows about replies to the user.
 *
 * Only notifications about chat messages are judged (object type `chat`: messages, mentions,
 * replies, reactions). Calls, invitations and reminders the user set are shown as before.
 */
class NotificationGate(context: Context, private val levels: NotifyLevelStore, private val currentSession: () -> TalkSession?) {
    private val appContext = context.applicationContext

    /** What to do with the first, undetailed version of a notification. */
    enum class Quick {
        Show,

        /** Not sure yet: show nothing until the details (and the topic) are known. */
        Wait,
    }

    /** The answer for one notification. [topic] is the topic as just fetched, when it was. */
    class Verdict(val blocked: Boolean, val topic: TalkThreadInfo?)

    /** The account notifications are about: the open session's, else (a push started the app) the last one's. */
    fun account(session: TalkSession? = currentSession()): String =
        session?.let { accountKey(it) } ?: levels.account

    private fun roomLevel(session: TalkSession?, account: String, token: String): Int =
        session?.rooms?.room(token)?.notificationLevel ?: levels.level(account, token, 0L)

    private fun effective(roomLevel: Int, topicLevel: Int): Int =
        if (topicLevel != NotificationLevel.DEFAULT) topicLevel else roomLevel

    /**
     * Judge from what the phone already knows, without waiting for anything. [threadId] is
     * the topic when the push itself names it, else 0 (it usually does not).
     *
     * Anything that might turn out to be muted waits: a conversation on "never" (one of its
     * topics may be switched back on), a topic stored as "never" (it may have been switched
     * back on elsewhere), and any message without a known topic in a conversation that has a
     * "never" topic.
     */
    fun quick(token: String, threadId: Long): Quick {
        if (token.isEmpty()) return Quick.Show
        val session = currentSession()
        val account = account(session)
        if (roomLevel(session, account, token) == NotificationLevel.NEVER) return Quick.Wait
        val silenced = levels.silencedTopics(account, token)
        return when {
            silenced.isEmpty() -> Quick.Show
            threadId > 0 && threadId !in silenced -> Quick.Show
            else -> Quick.Wait
        }
    }

    /**
     * Whether the line [subject] of a push is a message in a one-to-one conversation, judged without waiting for
     * anything, also in a process that has no session yet. There the first line is the other person's name and
     * nothing else (Talk's `Notifier::parseChatMessage`, whatever the language); a reaction's says more, and a
     * sensitive conversation's names nobody. False = no, or not known.
     */
    fun isDirectMessage(token: String, subject: String): Boolean {
        val firstLine = subject.substringBefore('\n', "")
        return token.isNotEmpty() && firstLine.isNotEmpty() && levels.person(account(), token) == firstLine.hashCode()
    }

    /** Whether [token] is marked sensitive, as the list says, else as the last one had it; null = not known. */
    fun isSensitive(session: TalkSession?, token: String): Boolean? =
        if (token.isEmpty()) false else session?.rooms?.room(token)?.isSensitive ?: levels.sensitive(account(session), token)

    /**
     * Some topic of the conversation is stored as "never". A chat notification whose topic
     * could not be found out must then not be shown: it may be from that topic.
     */
    fun hasSilencedTopics(session: TalkSession?, token: String): Boolean =
        token.isNotEmpty() && levels.silencedTopics(account(session), token).isNotEmpty()

    /**
     * The full answer, once the notification's conversation and topic are known ([threadId] 0
     * = in no topic; when the topic could not be found out, ask [hasSilencedTopics] first). The topic's level is asked from the server
     * each time (one small request, short timeout): it may have been changed on another
     * device. What the phone has stored is the fallback.
     */
    suspend fun check(session: TalkSession?, token: String, threadId: Long): Verdict {
        if (token.isEmpty()) return Verdict(false, null)
        val account = account(session)
        val room = roomLevel(session, account, token)
        var topic: TalkThreadInfo? = null
        var topicLevel = NotificationLevel.DEFAULT
        if (threadId > 0) {
            topicLevel = levels.level(account, token, threadId)
            if (session != null && session.supportsTopics) {
                topic = try {
                    withTimeoutOrNull(TOPIC_TIMEOUT_MS) { session.client.thread(token, threadId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "topic $threadId could not be asked for its level", e)
                    null
                }
                if (topic != null) {
                    topicLevel = topic.attendee.notificationLevel
                    levels.putTopics(account, token, mapOf(threadId to topicLevel))
                }
            }
        }
        return Verdict(effective(room, topicLevel) == NotificationLevel.NEVER, topic)
    }

    /** Take down what is already in the shade for conversations and topics that are now on "never". */
    fun sweep() {
        val session = currentSession()
        val account = account(session)
        try {
            TalqNotifications.cancelWhere(appContext) { token, topicId, topicKnown ->
                val topicLevel = if (topicId > 0) levels.level(account, token, topicId) else NotificationLevel.DEFAULT
                effective(roomLevel(session, account, token), topicLevel) == NotificationLevel.NEVER ||
                    // Shown before its topic was known: it may be from a topic that is muted now.
                    (!topicKnown && levels.silencedTopics(account, token).isNotEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "muted notifications could not be taken down", e)
        }
    }

    /**
     * Keep the stored levels current for as long as the process lives: conversation levels (and
     * which ones are one-to-one) from every conversation list that loads, topic levels through the session's listener
     * (set in `AppContainer.opened`), and a [sweep] after each change.
     */
    suspend fun run(sessionState: StateFlow<SessionState>) {
        sessionState.collectLatest { state ->
            val session = (state as? SessionState.SignedIn)?.session ?: return@collectLatest
            val account = accountKey(session)
            levels.account = account
            coroutineScope {
                launch {
                    session.rooms.rooms.collect { rooms ->
                        if (session.rooms.state.value.loaded) {
                            levels.putRooms(account, rooms.associate { it.token to it.notificationLevel })
                            levels.putPeople(account, rooms.filter { it.isOneToOne }.associate { it.token to it.displayName })
                            levels.putSensitive(account, rooms.associate { it.token to it.isSensitive })
                        }
                    }
                }
                launch { levels.version.drop(1).collect { sweep() } }
            }
        }
    }

    companion object {
        private const val TAG = "TalqPush"
        private const val TOPIC_TIMEOUT_MS = 3_000L

        /** `server|user`: what the stored levels are keyed by. */
        fun accountKey(session: TalkSession): String = session.credentials.server + "|" + session.user.userId
    }
}
