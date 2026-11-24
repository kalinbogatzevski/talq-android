package talq.app.ui.chatlist

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import talq.app.push.NotificationKind
import talq.app.push.PushRegistrar
import talq.app.push.PushStatus
import talq.app.push.TalqNotifications
import talq.app.push.notificationPromptPending
import talq.app.theme.TalqTheme
import talq.app.ui.common.StatusDisc
import talq.app.ui.settings.openNotificationSettings

/*
 * "Notifications are off" at the top of the conversation list (ours; desktop has no such line).
 * Rules:
 * - Shown when Android does not let the app post notifications (the app blocked, or the Messages
 *   or Mentions channel switched off), else when push could not be set up (no push app, web push
 *   off on the server, a failed registration). Never while push is still unknown or registering,
 *   and not while the first-run permission question is still to come.
 * - The phone's own state is read again every time the list comes to the front (the user may just
 *   have been in the system settings), and the line goes away by itself once all is well.
 * - The conversation list draws it under the header, in the place of the connection line, and only
 *   when there is none (offline, push looks broken too; the network's return retries it), and not
 *   while searching.
 * - Fix goes where it can be fixed: the app's notification page, the channel's page, or Settings
 *   scrolled to its Notifications section. The X hides it for that account until the app closes.
 */

/** What keeps notifications from reaching the user. */
sealed interface NotifyProblem {
    val text: String

    /** Android does not let the app post notifications (the permission, or the app switched off). */
    data object Blocked : NotifyProblem {
        override val text = "Notifications are off"
    }

    /** The app may post, but the channel [channelId] of messages (or, [mentions], of mentions) is switched off. */
    data class ChannelOff(val channelId: String, val mentions: Boolean) : NotifyProblem {
        override val text = if (mentions) "Mention notifications are off" else "Message notifications are off"
    }

    /** Push is not set up: nothing arrives while the app is closed. */
    data object PushFailed : NotifyProblem {
        override val text = "Notifications aren't working"
    }
}

/** The problem as of the last time the screen came to the front (and push's status as it is now); null when all is well. */
@Composable
fun rememberNotifyProblem(push: PushRegistrar): NotifyProblem? {
    val context = LocalContext.current
    val status by push.status.collectAsStateWithLifecycle()
    var onPhone by remember { mutableStateOf<NotifyProblem?>(null) }
    var askingFirst by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        onPhone = phoneProblem(context)
        askingFirst = notificationPromptPending(context)
        onPauseOrDispose { }
    }
    return when {
        askingFirst -> null
        onPhone != null -> onPhone
        status is PushStatus.Error || status == PushStatus.NoDistributor || status == PushStatus.ServerDisabled -> NotifyProblem.PushFailed
        else -> null
    }
}

private fun phoneProblem(context: Context): NotifyProblem? {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return NotifyProblem.Blocked
    for (kind in listOf(NotificationKind.Message, NotificationKind.Mention)) {
        val id = TalqNotifications.channelId(context, kind)
        val channel = manager.getNotificationChannelCompat(id) ?: continue
        if (channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            return NotifyProblem.ChannelOff(id, mentions = kind == NotificationKind.Mention)
        }
    }
    return null
}

/** Fix: the system page that turns it back on, or ([onOpenPushSettings]) Settings at its Notifications section. */
fun fixNotifications(context: Context, problem: NotifyProblem, onOpenPushSettings: () -> Unit) {
    when (problem) {
        NotifyProblem.Blocked -> openNotificationSettings(context)
        is NotifyProblem.ChannelOff -> {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, problem.channelId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                openNotificationSettings(context)
            }
        }
        NotifyProblem.PushFailed -> onOpenPushSettings()
    }
}

/** The line itself: a painted disc (red: switched off on the phone; amber: push not set up), the words, Fix and X. */
@Composable
fun NotificationsNotice(problem: NotifyProblem, onFix: () -> Unit, onDismiss: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bgSecondary)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDisc(if (problem == NotifyProblem.PushFailed) colors.amber else colors.danger)
        Spacer(Modifier.width(8.dp))
        Text(
            problem.text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFix) { Text("Fix") }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = colors.textSecondary)
        }
    }
}
