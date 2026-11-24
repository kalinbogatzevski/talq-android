package talq.app.ui.chat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import talq.app.theme.TalqTheme
import talq.core.model.ScheduleRefusal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import talq.app.ui.common.UiLocale
import talq.app.ui.common.clockPattern

// "Send silently" and "Send later", ported from TalQ for desktop's send-button menu
// (src/ui/ComposerWidget.cpp buildScheduleMenu / openScheduleMenu) and the words of
// MessageListModel::scheduleRefusalText, each cut to one line.

/** One "Send later" choice: what it says and the time it stands for, in the phone's zone. */
class LaterChoice(val label: String, val at: ZonedDateTime)

/**
 * Desktop's presets, in its order: in 1 hour, in 3 hours, tomorrow 8:00, tomorrow 18:00, next
 * Monday 9:00. "Tomorrow" is the next calendar day (not 24 hours on); "next Monday" is the coming
 * Monday, a week on when today is one. The hours follow the phone's clock style.
 */
fun laterChoices(now: ZonedDateTime, is24Hour: Boolean): List<LaterChoice> {
    val zone = now.zone
    val today = now.toLocalDate()
    fun clock(hour: Int) = LocalTime.of(hour, 0).format(DateTimeFormatter.ofPattern(if (is24Hour) "H:mm" else "h:mm a", UiLocale))
    fun at(day: LocalDate, hour: Int) = ZonedDateTime.of(day, LocalTime.of(hour, 0), zone)
    val tomorrow = today.plusDays(1)
    val nextMonday = today.plusDays(8L - today.dayOfWeek.value)
    return listOf(
        LaterChoice("In 1 hour", now.plusHours(1)),
        LaterChoice("In 3 hours", now.plusHours(3)),
        LaterChoice("Tomorrow ${clock(8)}", at(tomorrow, 8)),
        LaterChoice("Tomorrow ${clock(18)}", at(tomorrow, 18)),
        LaterChoice("Next Monday ${clock(9)}", at(nextMonday, 9)),
    )
}

/** "Mon 23 Nov, 15:40" (the year added when it is not this one), in the phone's zone and clock style. */
fun scheduleTimeLabel(context: Context, epochSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val day = if (then.year == LocalDate.now(zone).year) "EEE d MMM" else "EEE d MMM yyyy"
    return then.format(DateTimeFormatter.ofPattern("$day, ${clockPattern(context)}", UiLocale))
}

/** Why a "send later" was refused, in one line ([status] is the HTTP status, 0 or less without one). */
fun scheduleRefusalText(why: ScheduleRefusal, status: Int): String = when (why) {
    ScheduleRefusal.NoConnection -> "Not scheduled: can't reach the server."
    ScheduleRefusal.PastTime -> "Not scheduled: that time has already passed."
    ScheduleRefusal.EmptyMessage -> "Not scheduled: there was no text."
    ScheduleRefusal.TooLong -> "Not scheduled: the message is too long."
    ScheduleRefusal.ReplyGone -> "Not scheduled: the message you reply to is gone."
    ScheduleRefusal.ReplyForbidden -> "Not scheduled: you can't reply to that message here."
    ScheduleRefusal.NotAMember -> "Scheduled messages need you to be a member here."
    ScheduleRefusal.Unknown -> if (status > 0) "Not scheduled: the server refused it (HTTP $status)." else "Not scheduled: the server refused it."
}

/**
 * The send button's long-press sheet: "Send silently" (with [silent]) and "Send later" (with
 * [later]): desktop's presets, then "Pick a time…". The preset times are fixed when the sheet opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendOptionsSheet(
    silent: Boolean,
    later: Boolean,
    onSilent: () -> Unit,
    onLater: (LaterChoice) -> Unit,
    onPickTime: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val choices = remember { laterChoices(ZonedDateTime.now(), DateFormat.is24HourFormat(context)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (silent) {
                OptionRow(onClick = onSilent) {
                    Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
                    Spacer(Modifier.width(16.dp))
                    Text("Send silently", style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (later) {
                if (silent) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.divider)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = colors.textSecondary)
                    Spacer(Modifier.width(16.dp))
                    Text("Send later", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                }
                for (choice in choices) {
                    OptionRow(onClick = { onLater(choice) }) {
                        Spacer(Modifier.width(40.dp))
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            scheduleTimeLabel(context, choice.at.toEpochSecond()),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OptionRow(onClick = onPickTime) {
                    Icon(Icons.Outlined.EditCalendar, contentDescription = null)
                    Spacer(Modifier.width(16.dp))
                    Text("Pick a time…", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun OptionRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The platform's date picker, then its time picker, starting at [initial]; [onPicked] gets the
 * chosen minute in the phone's zone, [onDismiss] runs when either is cancelled. No day before
 * today can be picked; how far ahead the time is, the caller judges. The dialogs follow the app's
 * light or dark palette and go away with the composable (a rotation, the room left).
 */
@Composable
fun DateTimePickerDialogs(initial: ZonedDateTime, onPicked: (ZonedDateTime) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val light = TalqTheme.colors.isLight
    val picked by rememberUpdatedState(onPicked)
    val dismissed by rememberUpdatedState(onDismiss)
    DisposableEffect(context, light) {
        val theme = if (light) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert
        val zone = initial.zone
        var timeDialog: TimePickerDialog? = null
        val dateDialog = DatePickerDialog(
            context, theme,
            { _, year, month, day ->
                val date = LocalDate.of(year, month + 1, day)
                timeDialog = TimePickerDialog(
                    context, theme,
                    { _, hour, minute -> picked(ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)) },
                    initial.hour, initial.minute, DateFormat.is24HourFormat(context),
                ).apply {
                    setOnCancelListener { dismissed() }
                    show()
                }
            },
            initial.year, initial.monthValue - 1, initial.dayOfMonth,
        )
        dateDialog.datePicker.minDate = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        dateDialog.setOnCancelListener { dismissed() }
        dateDialog.show()
        onDispose {
            dateDialog.dismiss()
            timeDialog?.dismiss()
        }
    }
}
