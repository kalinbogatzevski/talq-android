package talq.core.call

/**
 * Which signaling node (HPB) a call uses, when the server lists more than one.
 *
 * `signaling/settings` names one node for the conversation ([SignalingSettings.server]) and, on a
 * clustered setup, lists all of them ([SignalingSettings.servers]). Any node of the cluster takes
 * the same ticket and reaches the same rooms, so the phone may use the nearest one: each signaling
 * message, and the media server behind that node, are then one short hop away instead of a
 * continent. The rule is TalQ for desktop's (`HpbRehomePolicy.h`, `pickHpb`), ported as it is: the
 * minimum of several TCP:443 connect times per node (jitter only ever adds), and the node in use
 * (the incumbent) is left only for one that is [HPB_SWITCH_MARGIN_MS] faster with at least
 * [HPB_DISPLACE_MIN_SAMPLES] samples and not held down. A silent incumbent takes any node that
 * answered; nobody answering keeps the named node.
 *
 * The core only asks ([HpbNodes]); the app measures. [HpbConnection] falls back to the named node
 * when the chosen one fails before hello.
 */

/** A challenger must be at least this much faster than the incumbent. */
const val HPB_SWITCH_MARGIN_MS = 30

/** ...and have at least this many landed samples to displace an incumbent that answered. */
const val HPB_DISPLACE_MIN_SAMPLES = 2

/** The host of a node's URL, lower case, without port or path; "" when there is none. */
fun hpbHost(url: String): String {
    val rest = url.trim().substringAfter("://", "")
    return rest.substringBefore('/').substringAfterLast('@').let { hostPort ->
        if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    }.lowercase()
}

/** The nodes to choose from: the conversation's named one first, then the listed ones, one per host. */
fun hpbCandidates(settings: SignalingSettings): List<String> {
    val seen = HashSet<String>()
    return (listOf(settings.server) + settings.servers).filter { url ->
        val host = hpbHost(url)
        host.isNotEmpty() && seen.add(host)
    }
}

/** One node's probe outcome. */
data class HpbProbeSample(
    /** Lowest TCP:443 connect time; -1 = nothing landed. */
    val minRttMs: Int = -1,
    /** Landed samples. */
    val samples: Int = 0,
    /** The node in use, or last connected to on this network (else the named one). */
    val isIncumbent: Boolean = false,
    /** Failed a connect recently. */
    val heldDown: Boolean = false,
)

enum class HpbPickReason(val text: String) {
    NoneAnswered("no candidate answered"),
    Sticky("sticky (within margin)"),
    SwitchedByMargin("switched (beat incumbent by margin, >=2 samples)"),
    IncumbentSilent("incumbent silent"),
    HeldDown("sticky (nearer server in hold-down)"),
}

data class HpbPick(
    /** The chosen candidate, -1 = none (keep the named node). */
    val index: Int = -1,
    val reason: HpbPickReason = HpbPickReason.NoneAnswered,
    val incumbentRttMs: Int = -1,
    /** Best eligible challenger with enough samples. */
    val bestChallengerRttMs: Int = -1,
    /** Best answering challenger (taken when the incumbent is silent). */
    val anyChallengerRttMs: Int = -1,
    /** Best held-down challenger with enough samples. */
    val heldDownRttMs: Int = -1,
    /** Every candidate landed at least one sample. */
    val allAnswered: Boolean = false,
)

/** Desktop's `pickHpb`. Candidate order breaks ties (the first wins). */
fun pickHpb(c: List<HpbProbeSample>, marginMs: Int = HPB_SWITCH_MARGIN_MS): HpbPick {
    var incumbentIdx = -1
    var bestIdx = -1
    var anyIdx = -1
    var heldAnyIdx = -1
    var heldAnyRtt = -1
    var incumbentRtt = -1
    var bestRtt = -1
    var anyRtt = -1
    var heldRtt = -1
    var all = c.isNotEmpty()
    c.forEachIndexed { i, s ->
        if (s.minRttMs < 0) all = false
        if (s.isIncumbent) {
            if (incumbentIdx < 0) {
                incumbentIdx = i
                if (s.minRttMs >= 0) incumbentRtt = s.minRttMs
            }
        } else if (s.minRttMs >= 0 && s.heldDown) {
            if (heldAnyRtt < 0 || s.minRttMs < heldAnyRtt) { heldAnyRtt = s.minRttMs; heldAnyIdx = i }
            if (s.samples >= HPB_DISPLACE_MIN_SAMPLES && (heldRtt < 0 || s.minRttMs < heldRtt)) heldRtt = s.minRttMs
        } else if (s.minRttMs >= 0) {
            if (anyRtt < 0 || s.minRttMs < anyRtt) { anyRtt = s.minRttMs; anyIdx = i }
            if (s.samples >= HPB_DISPLACE_MIN_SAMPLES && (bestRtt < 0 || s.minRttMs < bestRtt)) { bestRtt = s.minRttMs; bestIdx = i }
        }
    }
    val base = HpbPick(
        incumbentRttMs = incumbentRtt, bestChallengerRttMs = bestRtt, anyChallengerRttMs = anyRtt,
        heldDownRttMs = heldRtt, allAnswered = all,
    )
    return when {
        incumbentRtt >= 0 && bestRtt >= 0 && bestRtt <= incumbentRtt - marginMs ->
            base.copy(index = bestIdx, reason = HpbPickReason.SwitchedByMargin)
        incumbentRtt >= 0 -> base.copy(
            index = incumbentIdx,
            reason = if (heldRtt >= 0 && heldRtt <= incumbentRtt - marginMs) HpbPickReason.HeldDown else HpbPickReason.Sticky,
        )
        anyRtt >= 0 -> base.copy(index = anyIdx, reason = HpbPickReason.IncumbentSilent)
        // Only held-down nodes answer: something beats nothing.
        heldAnyRtt >= 0 -> base.copy(index = heldAnyIdx, reason = HpbPickReason.IncumbentSilent, anyChallengerRttMs = heldAnyRtt)
        else -> base
    }
}

/**
 * Where a call's signaling connects (the app measures, see [hpbCandidates]). Every function is
 * safe to call from any thread.
 */
interface HpbNodes {
    /**
     * The node to use for [settings]: one of [hpbCandidates], [SignalingSettings.server] when there is
     * nothing to choose or nothing measured in time. Waits a bounded time (about a second) at most.
     */
    suspend fun choose(settings: SignalingSettings): String

    /** Signaling on [server] failed before hello: it is not chosen again for a while. */
    fun failed(server: String)

    /** Signaling on [server] said hello. */
    fun connected(server: String)

    /** Always the named node: no choice, no measuring. */
    object Named : HpbNodes {
        override suspend fun choose(settings: SignalingSettings): String = settings.server
        override fun failed(server: String) {}
        override fun connected(server: String) {}
    }
}
