package talq.app.ui.chatlist

import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
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
import talq.app.data.NotifyNoticeStore
import talq.app.push.AlertState
import talq.app.push.NotificationKind
import talq.app.push.PushRegistrar
import talq.app.push.PushStatus
import talq.app.push.TalqNotifications
import talq.app.push.notificationPromptPending
import talq.app.theme.TalqTheme
import talq.app.ui.common.StatusDisc
import talq.app.ui.settings.openChannelSettings
import talq.app.ui.settings.openNotificationSettings

/*
 * "Notifications are off" at the top of the conversation list (ours; desktop has no such line).
 * Rules:
 * - Shown when Android does not let the app post notifications (the app blocked, or one of the two message
 *   alerts switched off: the line names that one, and only counts for it), else when push could not be set up
 *   (no push app, web push off on the server, a failed registration). Never while push is still unknown or
 *   registering, and not while the first-run permission question is still to come.
 * - The phone's own state is read again every time the list comes to the front (the user may just
 *   have been in the system settings), and the line goes away by itself once all is well.
 * - The conversation list draws it under the header, in the place of the connection line, and only
 *   when there is none (offline, push looks broken too; the network's return retries it), and not
 *   while searching.
 * - Fix goes where it can be fixed: the app's notification page, the channel's page, or Settings
 *   scrolled to its Notifications section.
 * - The X hides it for that account until the state changes ([NotifyNoticeStore], kept on the phone):
 *   the same problem stays hidden, also after a restart; once it was fixed, or another one is there, the
 *   next one shows again. Only what is known counts as a change: a push that is still unknown or
 *   registering (every start of the app begins so) is neither fixed nor another problem.
 */

/** What keeps notifications from reaching the user. */
sealed interface NotifyProblem {
    val text: String

    /** Which state this is, for a dismissal of it (a channel's id changes with its sound: not part of it). */
    val state: String

    /** Android does not let the app post notifications (the permission, or the app switched off). */
    data object Blocked : NotifyProblem {
        override val text = "Notifications are off"
        override val state = "blocked"
    }

    /** The app may post, but the alert (channel [channelId]) of [kind] is switched off. */
    data class ChannelOff(val channelId: String, val kind: NotificationKind) : NotifyProblem {
        override val text = if (kind == NotificationKind.Direct) "Direct message alerts are off" else "Other message alerts are off"
        override val state = if (kind == NotificationKind.Direct) "mentions-off" else "messages-off"
    }

    /** Push is not set up: nothing arrives while the app is closed. */
    data object PushFailed : NotifyProblem {
        override val text = "Notifications aren't working"
        override val state = "push"
    }
}

/**
 * The problem to show [account]: as of the last time the screen came to the front (and push's status
 * as it is now), unless it was dismissed. Null when all is well, or not known yet.
 */
@Composable
fun rememberNotifyProblem(push: PushRegistrar, notices: NotifyNoticeStore, account: String): NotifyProblem? {
    val context = LocalContext.current
    val status by push.status.collectAsStateWithLifecycle()
    val dismissed by notices.dismissed.collectAsStateWithLifecycle()
    var onPhone by remember { mutableStateOf<NotifyProblem?>(null) }
    var askingFirst by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        onPhone = phoneProblem(context)
        askingFirst = notificationPromptPending(context)
        onPauseOrDispose { }
    }
    val problem = when {
        askingFirst -> null
        onPhone != null -> onPhone
        status is PushStatus.Error || status == PushStatus.NoDistributor || status == PushStatus.ServerDisabled -> NotifyProblem.PushFailed
        else -> null
    }
    // All well counts as fixed only once push is really up.
    val known = problem != null || (!askingFirst && status == PushStatus.Active)
    LaunchedEffect(account, known, problem?.state) {
        if (known) notices.now(account, problem?.state)
    }
    return problem?.takeIf { !notices.isDismissed(dismissed, account, it.state) }
}

private fun phoneProblem(context: Context): NotifyProblem? {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return NotifyProblem.Blocked
    // The one that matters more first.
    return listOf(NotificationKind.Direct, NotificationKind.Other)
        .firstOrNull { TalqNotifications.alertState(context, it) == AlertState.Off }
        ?.let { NotifyProblem.ChannelOff(TalqNotifications.channelId(context, it), it) }
}

/** Fix: the system page that turns it back on, or ([onOpenPushSettings]) Settings at its Notifications section. */
fun fixNotifications(context: Context, problem: NotifyProblem, onOpenPushSettings: () -> Unit) {
    when (problem) {
        NotifyProblem.Blocked -> openNotificationSettings(context)
        is NotifyProblem.ChannelOff -> openChannelSettings(context, problem.channelId)
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
