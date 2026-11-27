package talq.app.ui.common

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import talq.core.TalkError

/**
 * The language of day and month names (and AM/PM) everywhere in the app: the app's texts are
 * English, so the dates are too, whatever the phone's language ("Mon", not the phone's word for
 * it, next to English words). Only the 12/24-hour choice follows the phone ([clockPattern]).
 * Never `Locale.setDefault`: the folder order and case folding read the phone's locale.
 */
val UiLocale: Locale = Locale.ENGLISH

/** "HH:mm" or "h:mm a", as the phone's clock setting says. */
fun clockPattern(context: Context): String = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"

/**
 * Compact "when" for list rows: now, 12m, 14:05, Tue, 3 Nov, 3 Nov 2025.
 * [timestamp] and [nowMillis] are UTC; the output is in the phone's zone and clock style.
 */
fun relativeTime(context: Context, timestamp: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0) return ""
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochSecond(timestamp).atZone(zone)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val seconds = ChronoUnit.SECONDS.between(then, now)
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
    val locale = UiLocale
    return when {
        seconds < 60 -> "now"
        seconds < 3600 -> "${seconds / 60}m"
        days == 0L -> then.format(DateTimeFormatter.ofPattern(clockPattern(context), locale))
        days in 1..6 -> then.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        then.year == now.year -> then.format(DateTimeFormatter.ofPattern("d MMM", locale))
        else -> then.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
}

/** "Maria Petrova" -> "Maria". */
fun firstName(displayName: String): String = displayName.trim().substringBefore(' ')

/**
 * What is underneath a request that failed: the exceptions and the library's own words, for Diagnostics and the
 * start screen. No address's query, and no more than a line or two.
 */
fun TalkError.technical(): String =
    generateSequence(cause) { c -> c.cause?.takeIf { it !== c } }.take(4).joinToString(" < ") { c ->
        val words = c.message.orEmpty().substringBefore('?').take(90)
        (c::class.simpleName ?: "?") + if (words.isBlank()) "" else ": $words"
    }.ifEmpty { this::class.simpleName.orEmpty() }

/** A sentence for a person, not a status code. */
fun TalkError.userMessage(): String = when (this) {
    is TalkError.Network -> "Can't reach the server. Check your connection."
    is TalkError.Unauthorized -> "You were signed out by the server."
    is TalkError.Forbidden -> "The server doesn't allow that."
    is TalkError.NotFound -> "That no longer exists on the server."
    is TalkError.Server -> "The server reported an error ($status). Try again in a moment."
    is TalkError.Http -> "The server refused the request ($status)."
    is TalkError.Protocol -> "The server answered with something unexpected."
    is TalkError.QuotaExceeded -> "Your storage on the server is full."
    is TalkError.LocalFile -> "The file couldn't be read or saved on this device."
}
