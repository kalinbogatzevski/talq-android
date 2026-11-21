package talq.core.shift

// Colleague shift status: what the client does with the ERP's answer.
//
// The app knows nothing about rosters, working hours or breaks. The organisation's ERP
// resolves all of that and answers a closed state plus a display label:
//
//   {"statuses": {"<user id>": {"state": "on_shift", "label": "Working"}}}
//
// Unknown is on purpose the same as "never asked": a refused request, a timeout and a
// colleague the ERP does not know all draw nothing.

enum class ShiftState {
    /** No ERP link, no permission, a state this build has never heard of, or any error. */
    Unknown,
    OnShift,
    OnBreak,
    OffShift;

    /** Unknown draws nothing, anywhere. */
    val isKnown: Boolean get() = this != Unknown

    companion object {
        /**
         * Exact match only: no trimming, no case folding. A state a newer ERP adds must become
         * Unknown here, not a near miss.
         */
        fun fromWire(state: String?): ShiftState = when (state) {
            "on_shift" -> OnShift
            "on_break" -> OnBreak
            "off_shift" -> OffShift
            else -> Unknown
        }
    }
}

/** What is known about one colleague, and when it was asked ([fetchedAtMs], wall clock). */
data class ShiftEntry(val state: ShiftState, val label: String, val fetchedAtMs: Long)

/** A request never carries more names than this. */
const val MAX_SHIFT_BATCH = 100

/** Shift state moves in minutes. */
const val SHIFT_TTL_MS = 120_000L

const val MAX_SHIFT_LABEL_CHARS = 24

/**
 * Whether an entry must be asked again. A stamp in the future (the clock went backwards) is
 * stale, not "very fresh".
 */
fun shiftEntryIsStale(fetchedAtMs: Long, nowMs: Long): Boolean {
    if (fetchedAtMs <= 0) return true
    if (nowMs < fetchedAtMs) return true
    return nowMs - fetchedAtMs >= SHIFT_TTL_MS
}

/** Drops empty ids and repeats, keeps the first-seen order, stops at [MAX_SHIFT_BATCH]. */
fun buildShiftBatch(userIds: Iterable<String>): List<String> {
    val out = LinkedHashSet<String>()
    for (id in userIds) {
        if (id.isEmpty()) continue
        out.add(id)
        if (out.size >= MAX_SHIFT_BATCH) break
    }
    return out.toList()
}

/**
 * The ERP owns the wording; the client owns what can break the layout: control characters
 * become spaces (a newline would make the one-line chip two lines) and the length is capped.
 */
fun sanitizeShiftLabel(label: String?): String {
    if (label == null) return ""
    val out = StringBuilder()
    for (c in label) {
        if (out.length >= MAX_SHIFT_LABEL_CHARS) break
        out.append(if (c.code < 0x20 || c.code == 0x7f) ' ' else c)
    }
    return out.toString()
}
