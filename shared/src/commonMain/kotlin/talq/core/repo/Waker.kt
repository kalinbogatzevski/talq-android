package talq.core.repo

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cuts short the waits of a session's retries when the phone's network is usable again
 * ([TalkSession.networkBack]): a room feed that backs off after a failure, a first load that
 * tries again every few seconds, the conversation list's reload timer. What works is not
 * touched, except a long poll that was already on its way (see [unlessWoken]).
 */
internal class Waker {
    private val wakes = MutableStateFlow(0L)

    fun wake() = wakes.update { it + 1 }

    /** The wake count now: taken before a request, handed to [pause] after it, no wake in between is missed. */
    fun mark(): Long = wakes.value

    /**
     * Wait [millis], or less when [wake] is called meanwhile or was called since [since] (a
     * [mark]). True when it was woken.
     */
    suspend fun pause(millis: Long, since: Long = wakes.value): Boolean {
        if (wakes.value != since) return true
        return withTimeoutOrNull(millis) { wakes.first { it != since } } != null
    }

    /**
     * Run [block], or give it up when [wake] is called before it answered (null then). For a
     * long poll that may be sitting on a connection the network change has left dead: it is
     * asked again at once instead of waiting out its timeout. Its failure is thrown as it is.
     */
    suspend fun <T : Any> unlessWoken(block: suspend () -> T): T? = coroutineScope {
        val seen = wakes.value
        val work = async { block() }
        val woken = async { wakes.first { it != seen } }
        select<T?> {
            work.onAwait { answer ->
                woken.cancel()
                answer
            }
            woken.onAwait {
                work.cancel()
                null
            }
        }
    }
}
