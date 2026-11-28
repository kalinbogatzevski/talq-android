package talq.app.call

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import talq.core.call.HpbNodes
import talq.core.call.HpbPick
import talq.core.call.HpbProbeSample
import talq.core.call.SignalingSettings
import talq.core.call.hpbCandidates
import talq.core.call.hpbHost
import talq.core.call.pickHpb
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The nearest signaling node, measured on this phone ([HpbNodes]; the rule is the core's `pickHpb`,
 * TalQ for desktop's).
 *
 * - Measured: per node [SAMPLES] TCP connects to its port 443, [SPACING_MS] apart, each bounded to
 *   [PROBE_MS] after the first; the minimum counts. The name is looked up first ([DNS_MS] at most) and
 *   not timed, as desktop does: a cold lookup would decide otherwise.
 * - Kept per network (Android's network handle) and candidate list for [KEEP_MS]: a phone moves
 *   between networks, a desk does not. Asked with the conversation open ([warm]) and when a call
 *   rings, so a tap rarely waits; [choose] waits [CHOOSE_WAIT_MS] at most, then takes the named node
 *   (the measurement goes on and is kept for the next call).
 * - The incumbent is the node that last said hello on this network, else the named one.
 * - A node whose signaling failed before hello is held down for [HOLD_DOWN_MS]: it may not replace an
 *   incumbent that answers (and a kept pick of it is dropped).
 * - Nothing is measured behind a proxy (a raw socket would not go through it; the named node is used),
 *   nor without a network, nor when the server names one node only (a stock server lists none).
 * - Nothing is stored: a new process measures again. [clear] at sign-out.
 *
 * Only during a call's setup: a running call never changes its node (see `HpbConnection`).
 */
class NearestHpb(context: Context, private val scope: CoroutineScope) : HpbNodes {
    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** What one measurement saw, and the node it chose. */
    class Reading(
        val candidates: List<String>,
        val samples: List<HpbProbeSample>,
        val pick: HpbPick,
        val chosen: String,
        val at: Long,
    ) {
        /** "host 12 ms, host 210 ms, host no answer", in candidate order. */
        fun line(): String = candidates.indices.joinToString(", ") { i ->
            val s = samples[i]
            hpbHost(candidates[i]) + if (s.minRttMs < 0) " no answer" else " ${s.minRttMs} ms"
        }
    }

    /** Why [measure] measured nothing. */
    enum class Skipped { OneNode, NoNetwork, Proxy }

    private data class Key(val network: Long, val candidates: List<String>)

    private val lock = Any()
    private val readings = HashMap<Key, Reading>()
    private val probes = HashMap<Key, Deferred<Reading?>>()
    private val heldSince = HashMap<String, Long>()
    private val lastHello = HashMap<Long, String>()

    /** Starts measuring for [settings] unless a fresh reading is kept or one runs. */
    fun warm(settings: SignalingSettings) {
        val key = keyFor(settings) ?: return
        synchronized(lock) { if (kept(key) == null) start(key) }
    }

    override suspend fun choose(settings: SignalingSettings): String {
        val key = keyFor(settings) ?: return settings.server
        val probe = synchronized(lock) {
            kept(key)?.let { return it.chosen }
            start(key)
        }
        val reading = try {
            withTimeoutOrNull(CHOOSE_WAIT_MS) { probe.await() }
        } catch (e: CancellationException) {
            // The measurement was stopped ([clear]), not this caller: the named node then.
            currentCoroutineContext().ensureActive()
            null
        }
        return reading?.chosen ?: settings.server
    }

    override fun failed(server: String) {
        val host = hpbHost(server)
        Log.i(TAG, "signaling node $host failed before hello: held down")
        synchronized(lock) {
            heldSince[host] = SystemClock.elapsedRealtime()
            readings.values.removeAll { hpbHost(it.chosen) == host }
        }
    }

    override fun connected(server: String) {
        val network = connectivity?.activeNetwork?.networkHandle ?: return
        synchronized(lock) { lastHello[network] = hpbHost(server) }
    }

    /** A fresh measurement for Diagnostics (not kept), or why there is none. */
    suspend fun measure(settings: SignalingSettings): Pair<Reading?, Skipped?> {
        val candidates = hpbCandidates(settings)
        if (candidates.size < 2) return null to Skipped.OneNode
        val cm = connectivity ?: return null to Skipped.NoNetwork
        val network = cm.activeNetwork ?: return null to Skipped.NoNetwork
        if (proxied(cm, network)) return null to Skipped.Proxy
        return decide(Key(network.networkHandle, candidates), probe(candidates)) to null
    }

    /** Forget everything (sign-out). */
    fun clear() {
        synchronized(lock) {
            probes.values.forEach { it.cancel() }
            probes.clear()
            readings.clear()
            heldSince.clear()
            lastHello.clear()
        }
    }

    /** Null when there is nothing to measure: one node, no network, a proxy. */
    private fun keyFor(settings: SignalingSettings): Key? {
        val candidates = hpbCandidates(settings)
        if (candidates.size < 2) return null
        val cm = connectivity ?: return null
        val network = cm.activeNetwork ?: return null
        if (proxied(cm, network)) return null
        return Key(network.networkHandle, candidates)
    }

    private fun proxied(cm: ConnectivityManager, network: android.net.Network): Boolean =
        cm.defaultProxy != null || cm.getLinkProperties(network)?.httpProxy != null

    /** The kept reading when fresh and its node not held down since. Under [lock]. */
    private fun kept(key: Key): Reading? {
        val r = readings[key] ?: return null
        val now = SystemClock.elapsedRealtime()
        if (now - r.at >= KEEP_MS || held(hpbHost(r.chosen), now)) {
            readings.remove(key)
            return null
        }
        return r
    }

    /** The measurement running for [key], or a new one. Under [lock]. */
    private fun start(key: Key): Deferred<Reading?> {
        probes[key]?.takeIf { it.isActive }?.let { return it }
        val d = scope.async(Dispatchers.IO) {
            val r = runCatching { decide(key, probe(key.candidates)) }.getOrNull()
            synchronized(lock) {
                if (r != null) readings[key] = r
                probes.remove(key)
            }
            r?.let { Log.i(TAG, "signaling nodes: ${it.pick.reason.text}, chosen ${hpbHost(it.chosen)} (${it.line()})") }
            r
        }
        probes[key] = d
        return d
    }

    private fun held(host: String, now: Long): Boolean = heldSince[host]?.let { now - it < HOLD_DOWN_MS } == true

    private fun decide(key: Key, measured: List<Pair<Int, Int>>): Reading = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        val hosts = key.candidates.map(::hpbHost)
        val incumbent = lastHello[key.network]?.takeIf { it in hosts } ?: hosts.first()
        val samples = hosts.mapIndexed { i, host ->
            HpbProbeSample(
                minRttMs = measured[i].first,
                samples = measured[i].second,
                isIncumbent = host == incumbent,
                heldDown = held(host, now),
            )
        }
        val pick = pickHpb(samples)
        Reading(key.candidates, samples, pick, key.candidates[pick.index.coerceAtLeast(0)], now)
    }

    /** Per candidate: (lowest connect time or -1, samples that landed). */
    private suspend fun probe(candidates: List<String>): List<Pair<Int, Int>> = coroutineScope {
        candidates.map { url -> async(Dispatchers.IO) { probeOne(url) } }.awaitAll()
    }

    private suspend fun probeOne(url: String): Pair<Int, Int> = coroutineScope {
        val host = hpbHost(url).removePrefix("[").removeSuffix("]")
        val port = portOf(url)
        // Not a child: a lookup that hangs must not hold the measurement up past its bound.
        val lookup = scope.async(Dispatchers.IO) { runCatching { InetAddress.getByName(host) }.getOrNull() }
        val address = withTimeoutOrNull(DNS_MS) { lookup.await() } ?: return@coroutineScope -1 to 0
        val times = (0 until SAMPLES).map { s ->
            async(Dispatchers.IO) {
                delay(s * SPACING_MS)
                connectMs(address, port, (PROBE_MS - s * SPACING_MS).toInt())
            }
        }.awaitAll().filter { it >= 0 }
        (times.minOrNull() ?: -1) to times.size
    }

    private suspend fun connectMs(address: InetAddress, port: Int, timeoutMs: Int): Int = runInterruptible(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                val started = System.nanoTime()
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                ((System.nanoTime() - started) / 1_000_000).toInt()
            }
        } catch (e: IOException) {
            -1
        }
    }

    private fun portOf(url: String): Int {
        val hostPort = url.substringAfter("://", "").substringBefore('/').substringAfterLast('@')
        val explicit = if (hostPort.startsWith("[")) hostPort.substringAfter("]:", "") else hostPort.substringAfter(':', "")
        return explicit.toIntOrNull() ?: if (url.startsWith("http://") || url.startsWith("ws://")) 80 else 443
    }

    private companion object {
        const val TAG = "TalqCall"
        const val SAMPLES = 4
        const val SPACING_MS = 120L
        const val PROBE_MS = 900L
        const val DNS_MS = 1_000L
        const val CHOOSE_WAIT_MS = 1_500L
        const val KEEP_MS = 30 * 60 * 1000L
        const val HOLD_DOWN_MS = 15 * 60 * 1000L
    }
}
