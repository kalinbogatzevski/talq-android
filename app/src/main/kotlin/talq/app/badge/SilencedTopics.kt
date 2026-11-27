package talq.app.badge

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import talq.app.SessionState
import talq.app.data.NotifyLevelStore
import talq.app.net.NetworkWatch
import talq.app.net.offline
import talq.app.push.NotificationGate
import talq.core.TalkError

/**
 * What the grey unread numbers of the conversation list need from the phone (the rules are in
 * `talq.core.repo.SilencedUnread`): the topics stored as Never, and whether a request may go out, which is
 * only while the app is in front and not offline. Runs for as long as the process lives: the figures are
 * looked at again after every reload of the list (the core does that) and every change of a stored level.
 */
suspend fun runSilencedUnread(sessionState: StateFlow<SessionState>, levels: NotifyLevelStore, network: NetworkWatch) {
    sessionState.collectLatest { state ->
        val session = (state as? SessionState.SignedIn)?.session ?: return@collectLatest
        val account = NotificationGate.accountKey(session)
        session.silencedUnread.attach(
            silencedTopics = { token -> levels.silencedTopics(account, token) },
            mayAsk = {
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    !network.link.value.offline(failing = session.rooms.state.value.error is TalkError.Network)
            },
        )
        levels.version.drop(1).collect { session.silencedUnread.recheck() }
    }
}
