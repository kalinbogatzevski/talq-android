package talq.app.ui.settings

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import talq.app.Brand
import talq.app.BuildConfig
import talq.app.appContainer
import talq.app.data.ThemeChoice
import talq.app.theme.colorsFor
import talq.app.appName
import talq.app.push.AlertState
import talq.app.push.NotificationKind
import talq.app.push.PushRegistrar
import talq.app.push.TalqNotifications
import talq.app.ui.update.updateHowTo
import talq.app.update.UpdateState
import talq.app.push.PushStatus
import talq.app.shift.ErpLink
import talq.app.shift.ShiftStatusService
import talq.app.theme.TalqTheme
import talq.app.ui.common.Avatar
import talq.app.ui.common.StatusDisc
import talq.app.ui.common.WAITING_FOR_SERVER
import talq.core.repo.TalkSession
import talq.core.shift.ApproveTarget

/** [session] may be one that is not checked yet (TalqNavHost): the phone's own settings, and Log out, are there then. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    session: TalkSession,
    push: PushRegistrar,
    onBack: () -> Unit,
    /** Revokes the app password and clears storage. May take a moment. */
    onLogout: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    /** Opened by the list's "Notifications aren't working" Fix: scroll to the Notifications section once. */
    showNotifications: Boolean = false,
) {
    val colors = TalqTheme.colors
    val appName = appName()
    val pushStatus by push.status.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val loggingOut by context.appContainer.loggingOut.collectAsStateWithLifecycle()
    var notificationsAllowed by remember { mutableStateOf(true) }
    var directAlert by remember { mutableStateOf(AlertState.On) }
    var otherAlert by remember { mutableStateOf(AlertState.On) }
    // Picked below, a message sound makes both message alerts anew.
    val messageSound by context.appContainer.sounds.message.collectAsStateWithLifecycle()
    var fullScreenCalls by remember { mutableStateOf(true) }
    val callsAvailable by produceState(false, session) { value = context.appContainer.calls.available(session) }
    // Re-read on every return to the screen: the user may just have been in system settings.
    LifecycleResumeEffect(messageSound) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        directAlert = TalqNotifications.alertState(context, NotificationKind.Direct)
        otherAlert = TalqNotifications.alertState(context, NotificationKind.Other)
        fullScreenCalls = canShowCallsFullScreen(context)
        // An app such as ntfy may have been installed or removed meanwhile.
        push.refreshRoutes()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val scroll = rememberScrollState()
        // Where the Notifications section starts in the scrolled column, once it was laid out.
        var notificationsTop by remember { mutableIntStateOf(-1) }
        var shownNotifications by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(notificationsTop) {
            if (showNotifications && !shownNotifications && notificationsTop >= 0) {
                shownNotifications = true
                scroll.animateScrollTo(notificationsTop)
            }
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll)) {
            // Your own status is set from the conversation list's header, not here.
            SectionLabel("Account")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    url = session.client.userAvatarUrl(session.user.userId, 128, !TalqTheme.colors.isLight),
                    authorization = session.client.authorizationHeader,
                    name = session.user.displayName.ifEmpty { session.user.userId },
                    size = 52.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        session.user.displayName.ifEmpty { session.user.userId },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(session.user.userId, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
            }
            SettingRow("Server", session.client.serverUrl.removePrefix("https://"))
            SettingRow("Talk version", session.capabilities.talkVersion.ifEmpty { "unknown" })

            Divider()
            SectionLabel("Appearance")
            AppearanceSection()

            Divider()
            SectionLabel("Folders")
            FoldersSection()

            Divider()
            Box(Modifier.onGloballyPositioned { notificationsTop = it.positionInParent().y.toInt() }) {
                SectionLabel("Notifications")
            }
            // Push can be registered and still silent: Android itself may be blocking TalQ.
            val blocked = pushStatus == PushStatus.Active && !notificationsAllowed
            val (headline, detail, tone) =
                if (blocked) {
                    Triple("Blocked on this phone", "Android is not letting $appName show notifications. Allow them in the system settings.", Tone.Bad)
                } else if (!session.checked) {
                    Triple(WAITING_FOR_SERVER, null, Tone.Neutral)
                } else {
                    describe(pushStatus, appName)
                }
            SettingRow(
                title = "Push notifications",
                value = headline,
                detail = detail,
                valueColor = when (tone) {
                    Tone.Good -> colors.online
                    Tone.Bad -> colors.danger
                    Tone.Neutral -> colors.textSecondary
                },
                disc = true,
            )
            if (session.checked) Row(Modifier.padding(horizontal = 8.dp)) {
                when (pushStatus) {
                    PushStatus.Active ->
                        TextButton(onClick = { openNotificationSettings(context) }) { Text("Notification settings") }
                    PushStatus.NoDistributor -> {
                        TextButton(onClick = { openDistributorStorePage(context) }) { Text("Get ntfy") }
                        TextButton(onClick = { push.retry() }) { Text("Check again") }
                    }
                    PushStatus.ServerDisabled, PushStatus.Unknown, is PushStatus.Error ->
                        TextButton(onClick = { push.retry() }) { Text("Try again") }
                    PushStatus.Registering -> Unit
                }
            }
            // The two message alerts; their sound and importance are Android's settings.
            fun openAlert(kind: NotificationKind) {
                if (notificationsAllowed) openChannelSettings(context, TalqNotifications.channelId(context, kind)) else openNotificationSettings(context)
            }
            AlertRow("Direct messages and mentions", directAlert) { openAlert(NotificationKind.Direct) }
            AlertRow("Other messages", otherAlert) { openAlert(NotificationKind.Other) }
            // Which route delivers push: Google Play services (built in) or an app such as ntfy.
            val pushRoutes by push.routes.collectAsStateWithLifecycle()
            val routes = pushRoutes.all
            val currentRoute = pushRoutes.current
            var choosingRoute by remember { mutableStateOf(false) }
            val inUse = routes.firstOrNull { it.packageName == currentRoute }
            if (inUse != null) {
                SettingRow(title = "Delivered by", value = inUse.label)
                if (routes.size > 1 && session.checked) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { choosingRoute = true }) { Text("Change") }
                    }
                }
            }
            if (isHonorOrHuawei()) {
                Text(
                    "Also needed here: Settings \u203a Apps \u203a App launch \u203a $appName \u203a Manage manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (choosingRoute) {
                AlertDialog(
                    onDismissRequest = { choosingRoute = false },
                    title = { Text("Deliver notifications through") },
                    text = {
                        Column {
                            routes.forEach { route ->
                                val pick = {
                                    choosingRoute = false
                                    push.useRoute(route.packageName)
                                }
                                Row(
                                    Modifier.fillMaxWidth().clickable(onClick = pick).padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = route.packageName == currentRoute, onClick = pick)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(route.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            if (route.builtIn) "Built in: nothing else to install" else "A separate push app",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textMuted,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { choosingRoute = false }) { Text("Close") } },
                )
            }

            Divider()
            SectionLabel("Sounds")
            SoundsSection()

            // Android 14 and later can take full-screen access away (the user, or the store for
            // apps it does not see as calling apps): a call then only shows a notification, also
            // on the lock screen, and nothing else would say why.
            if (callsAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Divider()
                SectionLabel("Calls")
                SettingRow(
                    title = "Incoming calls",
                    value = if (fullScreenCalls) "Full screen" else "Notification only",
                    detail = if (fullScreenCalls) null else
                        "Calls ring as a notification only.",
                    valueColor = if (fullScreenCalls) colors.online else colors.danger,
                    disc = true,
                )
                if (!fullScreenCalls) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { openFullScreenCallSettings(context) }) { Text("Allow full screen") }
                    }
                }
            }

            if (session.checked) {
                Divider()
                SectionLabel("Shift status")
                ShiftSection(context.appContainer.shift)
            }

            Divider()
            SectionLabel("Diagnostics")
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpenDiagnostics).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("Connection diagnostics", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Checks the server, push, call signaling and every STUN/TURN address, and makes a report to send for support.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }

            Divider()
            SectionLabel("About")
            SettingRow("$appName for Android", BuildConfig.VERSION_NAME)
            val updates = context.appContainer.updates
            if (updates.enabled) {
                val update by updates.state.collectAsStateWithLifecycle()
                SettingRow(
                    title = "Updates",
                    value = when (val s = update) {
                        UpdateState.Idle -> "Not checked yet"
                        UpdateState.Checking -> "Checking\u2026"
                        UpdateState.UpToDate -> "Up to date"
                        is UpdateState.Available -> "${s.version} available"
                        is UpdateState.Failed -> "Could not check"
                    },
                    detail = when (val s = update) {
                        is UpdateState.Failed -> s.message
                        is UpdateState.Available -> updateHowTo()
                        else -> null
                    },
                    valueColor = when (update) {
                        is UpdateState.Available -> colors.accentText
                        is UpdateState.Failed -> colors.danger
                        else -> colors.textSecondary
                    },
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    val downloading by updates.downloading.collectAsStateWithLifecycle()
                    if (update is UpdateState.Available) {
                        TextButton(onClick = { updates.download() }, enabled = !downloading) {
                            Text(if (downloading) "Downloading\u2026" else "Update")
                        }
                    }
                    if (update !is UpdateState.Checking) TextButton(onClick = updates::checkNow) { Text("Check now") }
                }
            }

            Divider()
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { confirmLogout = true },
                enabled = !loggingOut,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(if (loggingOut) "Logging out…" else "Log out", color = colors.danger)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("$appName's access to your account is revoked on the server and removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text("Log out", color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

/**
 * "Shift status": the pairing with the organisation's ERP. This composable only shows the
 * service's state and hands the approval page to the browser; every request is the
 * app-level [ShiftStatusService]'s, so leaving this screen (or the app) stops nothing.
 */
@Composable
private fun ShiftSection(shift: ShiftStatusService) {
    val colors = TalqTheme.colors
    val context = LocalContext.current
    val link by shift.link.collectAsStateWithLifecycle()
    val notice by shift.notice.collectAsStateWithLifecycle()
    val address by shift.address.collectAsStateWithLifecycle()
    fun open(target: ApproveTarget) {
        if (openApprovalPage(context, target.url)) shift.approveOpened()
        else shift.cancelPairing("No browser could be opened for the approval page.")
    }

    val pairing = link as? ErpLink.Pairing
    LaunchedEffect(pairing?.approve, pairing?.opened) {
        // The target is https on the ERP's own host and port, or the service made none.
        val target = pairing?.approve
        if (target != null && !pairing.opened) open(target)
    }

    val current = link
    when (current) {
        ErpLink.NotPaired, ErpLink.Rejected -> {
            if (current == ErpLink.Rejected) {
                SettingRow("ERP", "Not accepted", detail = "The ERP no longer accepts this device. Pair again.", valueColor = colors.danger)
            } else {
                SettingRow(
                    "ERP", "Not paired",
                    detail = "Shows colleagues' shift status. You approve this phone once, in your browser.",
                )
                if (!Brand.isBranded) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = shift::setAddress,
                        label = { Text("ERP address") },
                        placeholder = { Text("https://") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            notice?.let { NoticeText(it) }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { shift.startPairing() }) {
                    Text(if (current == ErpLink.Rejected) "Pair again" else "Pair with ERP")
                }
                if (current == ErpLink.Rejected) TextButton(onClick = { shift.unpair() }) { Text("Unpair") }
            }
        }

        is ErpLink.Pairing -> {
            SettingRow(
                "ERP", "Pairing…",
                detail = if (current.approve == null) "Asking the ERP…" else "Approve this device in your browser…",
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                val target = current.approve
                if (target != null && current.opened) {
                    TextButton(onClick = { open(target) }) { Text("Open the page again") }
                }
                TextButton(onClick = { shift.cancelPairing() }) { Text("Cancel") }
            }
        }

        is ErpLink.Paired -> {
            SettingRow(
                "ERP",
                if (current.displayName.isBlank()) "Paired" else "Paired as ${current.displayName}",
                valueColor = colors.online,
            )
            notice?.let { NoticeText(it) }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { shift.unpair() }) { Text("Unpair") }
            }
        }
    }
}

/** The theme: the four of desktop TalQ, or following the phone's light/dark setting. */
@Composable
private fun AppearanceSection() {
    val appearance = LocalContext.current.appContainer.appearance
    val theme by appearance.theme.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    SettingRow(title = "Theme", value = theme.label, detail = theme.description)
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { choosing = true }) { Text("Change") }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Theme") },
            text = {
                Column {
                    ThemeChoice.entries.forEach { choice ->
                        val pick = {
                            choosing = false
                            appearance.setTheme(choice)
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = pick).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == theme, onClick = pick)
                            Spacer(Modifier.width(8.dp))
                            ThemeSwatch(choice)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                                Text(choice.description, style = MaterialTheme.typography.bodySmall, color = TalqTheme.colors.textMuted)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}

/** A small disc in the theme's ground with its accent in the middle. */
@Composable
private fun ThemeSwatch(choice: ThemeChoice) {
    val c = colorsFor(choice)
    Box(
        Modifier.size(28.dp).background(c.bgPrimary, CircleShape).border(1.dp, TalqTheme.colors.divider, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).background(c.accent, CircleShape))
    }
}

@Composable
private fun NoticeText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = TalqTheme.colors.danger,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** The ERP's approval page (https, checked when the [ApproveTarget] was made) in a Custom Tab, else any browser. */
private fun openApprovalPage(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        return true
    } catch (_: ActivityNotFoundException) {
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private enum class Tone { Good, Bad, Neutral }

private fun describe(status: PushStatus, appName: String): Triple<String, String?, Tone> = when (status) {
    PushStatus.Unknown -> Triple("Not set up", "Messages arrive while $appName is open.", Tone.Neutral)
    PushStatus.NoDistributor -> Triple(
        "No push app",
        "Install a push app (for example ntfy), open it once, then check again.",
        Tone.Neutral,
    )
    PushStatus.ServerDisabled -> Triple(
        "Off on the server",
        "Web push is off on the server. Ask your administrator.",
        Tone.Neutral,
    )
    PushStatus.Registering -> Triple("Setting up…", null, Tone.Neutral)
    PushStatus.Active -> Triple("On", "You are notified while $appName is closed.", Tone.Good)
    is PushStatus.Error -> Triple("Not working", status.message, Tone.Bad)
}

/** Honor and Huawei stop apps in the background unless the user allows it per app. */
private fun isHonorOrHuawei(): Boolean =
    Build.MANUFACTURER.equals("HONOR", ignoreCase = true) || Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

/** The app's page in the system notification settings (permission and channels). Also the list's Fix. */
internal fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

/** One channel's page in the system notification settings (its sound, importance, off). Also the list's Fix. */
internal fun openChannelSettings(context: Context, channelId: String) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        openNotificationSettings(context)
    }
}

/** Whether an incoming call may take the whole screen (always before Android 14). */
private fun canShowCallsFullScreen(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false

/** The system page that allows full-screen notifications for this app, else its notification settings. */
private fun openFullScreenCallSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    openNotificationSettings(context)
}

/** Where to get a distributor: the installed app store if it knows ntfy, else its F-Droid page. */
private fun openDistributorStorePage(context: Context) {
    val candidates = listOf("market://details?id=$DISTRIBUTOR_PACKAGE", "https://f-droid.org/packages/$DISTRIBUTOR_PACKAGE/")
    for (uri in candidates) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

private const val DISTRIBUTOR_PACKAGE = "io.heckel.ntfy"

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TalqTheme.colors.textMuted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = TalqTheme.colors.divider)
}

/** [disc]: [value] is a state, so a disc in its colour is painted beside it. */
@Composable
private fun SettingRow(
    title: String,
    value: String,
    detail: String? = null,
    valueColor: Color = TalqTheme.colors.textSecondary,
    disc: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            if (disc) {
                StatusDisc(valueColor)
                Spacer(Modifier.width(8.dp))
            }
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
        if (detail != null) {
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = TalqTheme.colors.textMuted)
        }
    }
}

/** A message alert and how it stands on this phone; a tap opens Android's own page for it. */
@Composable
private fun AlertRow(title: String, state: AlertState, onClick: () -> Unit) {
    val colors = TalqTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        StatusDisc(
            when (state) {
                AlertState.On -> colors.online
                AlertState.Silent -> colors.amber
                AlertState.Off -> colors.danger
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when (state) {
                AlertState.On -> "On"
                AlertState.Silent -> "Silent"
                AlertState.Off -> "Off"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}
