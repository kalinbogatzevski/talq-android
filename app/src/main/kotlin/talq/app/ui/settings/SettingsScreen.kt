package talq.app.ui.settings

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import talq.app.push.PushRegistrar
import talq.app.push.PushStatus
import talq.app.shift.ErpLink
import talq.app.shift.ShiftStatusService
import talq.app.theme.TalqTheme
import talq.app.ui.common.Avatar
import talq.core.repo.TalkSession
import talq.core.shift.ApproveTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    session: TalkSession,
    push: PushRegistrar,
    onBack: () -> Unit,
    /** Revokes the app password and clears storage. May take a moment. */
    onLogout: () -> Unit,
) {
    val colors = TalqTheme.colors
    val appName = appName()
    val pushStatus by push.status.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(true) }
    // Re-read on every return to the screen: the user may just have been in system settings.
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
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
            SectionLabel("Notifications")
            // Push can be registered and still silent: Android itself may be blocking TalQ.
            val blocked = pushStatus == PushStatus.Active && !notificationsAllowed
            val (headline, detail, tone) =
                if (blocked) {
                    Triple("Blocked on this phone", "Android is not letting $appName show notifications. Allow them in the system settings.", Tone.Bad)
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
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
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
            // Which route delivers push: Google Play services (built in) or an app such as ntfy.
            val pushRoutes by push.routes.collectAsStateWithLifecycle()
            val routes = pushRoutes.all
            val currentRoute = pushRoutes.current
            var choosingRoute by remember { mutableStateOf(false) }
            val inUse = routes.firstOrNull { it.packageName == currentRoute }
            if (inUse != null) {
                SettingRow(title = "Delivered by", value = inUse.label)
                if (routes.size > 1) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { choosingRoute = true }) { Text("Change") }
                    }
                }
            }
            if (isHonorOrHuawei()) {
                Text(
                    "On this phone, also let $appName start in the background, or notifications stop once it " +
                        "is swiped away: Settings, Apps, App launch, $appName, Manage manually, all switches on.",
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
            SectionLabel("Shift status")
            ShiftSection(context.appContainer.shift)

            Divider()
            SectionLabel("About")
            SettingRow("$appName for Android", BuildConfig.VERSION_NAME)

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
                    loggingOut = true
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
                    detail = "Shows whether a colleague is on shift, on a break or off shift, from your organisation's ERP. " +
                        "You approve this phone once in your browser; no password is typed here.",
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
        (if (BuildConfig.GOOGLE_PUSH) "This phone has no Google Play services. " else "") +
            "$appName receives push through a UnifiedPush distributor app, which keeps the connection for it. " +
            "Install one (for example ntfy), open it once, then check again.",
        Tone.Neutral,
    )
    PushStatus.ServerDisabled -> Triple(
        "Off on the server",
        "Push is turned off on the server. Ask the administrator to enable web push in the notifications settings.",
        Tone.Neutral,
    )
    PushStatus.Registering -> Triple("Setting up…", null, Tone.Neutral)
    PushStatus.Active -> Triple("On", "You are notified while $appName is closed.", Tone.Good)
    is PushStatus.Error -> Triple("Not working", status.message, Tone.Bad)
}

/** Honor and Huawei stop apps in the background unless the user allows it per app. */
private fun isHonorOrHuawei(): Boolean =
    Build.MANUFACTURER.equals("HONOR", ignoreCase = true) || Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

/** The app's page in the system notification settings (permission and channels). */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
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

@Composable
private fun SettingRow(title: String, value: String, detail: String? = null, valueColor: Color = TalqTheme.colors.textSecondary) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
        if (detail != null) {
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = TalqTheme.colors.textMuted)
        }
    }
}
