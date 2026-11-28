package talq.app.diag

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import talq.app.AppContainer
import talq.app.Brand
import talq.app.BuildConfig
import talq.app.fixedErp
import talq.app.SessionState
import talq.app.call.NearestHpb
import talq.app.call.TurnProbe
import talq.app.push.AlertState
import talq.app.push.NotificationKind
import talq.app.push.PushStatus
import talq.app.push.TalqNotifications
import talq.app.shift.ErpLink
import talq.app.ui.common.WAITING_FOR_SERVER
import talq.app.ui.common.technical
import talq.app.ui.common.userMessage
import talq.core.TalkError
import talq.core.call.HpbConnection
import talq.core.call.HpbState
import talq.core.call.IceServer
import talq.core.call.hpbCandidates
import talq.core.call.hpbHost
import talq.core.net.newHpbHttpClient
import talq.core.repo.TalkSession
import java.net.Inet6Address
import java.security.MessageDigest
import java.net.URI

/** How a check came out. Every state has its own colour on screen (never a plain "normal"). */
enum class Health { Good, Warn, Bad, Info, Running }

/** One line of the diagnostics: [action] opens the system page that fixes it, if there is one. */
data class Check(
    val group: String,
    val title: String,
    val detail: String,
    val health: Health,
    val action: DiagAction? = null,
    /** Written under the line in the shared report only (for whoever reads it there, not on screen). */
    val reportNote: String? = null,
)

/** System pages a check can open. */
enum class DiagAction { Notifications, Battery, FullScreenCalls }

/**
 * The connection diagnostics behind Settings > Diagnostics and the report a tester shares. It
 * reads what the app knows (push, permissions, network, shift status) and runs real tests where
 * reading is not proof: one request to the server, a sign-in on the call signaling server that is
 * closed again at once, and a relay (TURN) or address (STUN) request to each ICE server, as a
 * call would make. Nothing runs in the background: only when the screen asks. No secret is ever
 * part of a result: no passwords, tickets, TURN credentials or session ids; servers by host name.
 */
class Diagnostics(private val context: Context, private val container: AppContainer) {

    /** Runs every check; [publish] gets the whole list each time one finishes. */
    suspend fun run(session: TalkSession?, publish: (List<Check>) -> Unit) {
        val results = LinkedHashMap<String, Check>()
        fun put(key: String, check: Check) {
            results[key] = check
            publish(results.values.toList())
        }
        // The quick ones first, so the screen fills at once.
        appChecks().forEachIndexed { i, c -> put("app$i", c) }
        pushChecks().forEachIndexed { i, c -> put("push$i", c) }
        networkChecks().forEachIndexed { i, c -> put("net$i", c) }
        shiftCheck()?.let { put("shift", it) }
        if (session == null) {
            val why = when (val state = container.sessionState.value) {
                is SessionState.Unreachable -> "${state.error.userMessage()} ${state.error.technical()}"
                SessionState.Loading -> WAITING_FOR_SERVER
                else -> "Not signed in"
            }
            put("server", Check(SERVER, "Server", why, Health.Warn))
            return
        }
        put("server", Check(SERVER, "Server", "Checking…", Health.Running))
        put("server", serverCheck(session))
        callChecks(session) { key, check -> put(key, check) }
    }

    private fun appChecks(): List<Check> {
        val flavor = (if (Brand.isBranded) "branded" else "generic") + if (BuildConfig.GOOGLE_PUSH) "" else ", without Google push"
        return listOf(
            Check(APP, "${Brand.appName(context)} ${BuildConfig.VERSION_NAME}", "Build ${BuildConfig.VERSION_CODE}, $flavor", Health.Info),
            Check(APP, "${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", Health.Info),
            googleServicesCheck(),
            containerCheck(),
            // Organisation mode: whether one is set and its random brand id; never its code or file.
            container.organisation.state.value.let { org ->
                Check(APP, "Organisation", if (org == null) "no" else "yes (${org.brandId})", Health.Info)
            },
        )
    }

    /**
     * Google Play services as this phone has it: Google's own, one signed by someone else (microG,
     * or what an emulator app such as GBox brings), or none. Push through Google looks the same to
     * the server in every case; this tells them apart.
     */
    private fun googleServicesCheck(): Check {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(GMS_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(GMS_PACKAGE, PackageManager.GET_SIGNATURES)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return Check(APP, "Google services", "None on this phone", Health.Info)
        }
        val signers: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val google = signers.orEmpty().any { sha1(it.toByteArray()) in GOOGLE_GMS_CERTS }
        val version = info.versionName?.substringBefore(' ')?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return when {
            info.applicationInfo?.enabled == false ->
                Check(APP, "Google services", "Google Play services$version is installed but switched off", Health.Warn)
            google -> Check(APP, "Google services", "Google Play services$version, Google's own", Health.Info)
            else -> Check(APP, "Google services", "Google Play services$version, not signed by Google: a replacement such as microG", Health.Info)
        }
    }

    /**
     * Whether this app runs inside another app: emulator apps such as GBox or Gspace keep the apps
     * they run in their own folder (and under their own Android user id). An update must then be
     * installed from inside that app, and that app must be let run in the background.
     */
    private fun containerCheck(): Check {
        val own = context.packageName
        val fromFolder = HOST_IN_PATH.find(context.applicationInfo.dataDir.orEmpty())?.groupValues?.get(1)
        val fromUid = runCatching { context.packageManager.getNameForUid(android.os.Process.myUid()) }.getOrNull()?.substringBefore(':')
        val host = fromFolder?.takeIf { it != own } ?: fromUid?.takeIf { it.isNotBlank() && it != own }
        return if (host == null) {
            Check(APP, "Inside another app", "No, it runs on its own", Health.Info)
        } else {
            Check(APP, "Inside another app", "Yes, inside $host: install updates from inside that app, and let it run in the background", Health.Warn)
        }
    }

    private suspend fun serverCheck(session: TalkSession): Check {
        val host = hostOf(session.client.serverUrl)
        val account = session.user.userId
        val started = SystemClock.elapsedRealtime()
        return try {
            val caps = withContext(Dispatchers.IO) { session.client.capabilities() }
            val ms = SystemClock.elapsedRealtime() - started
            val versions = listOfNotNull(
                caps.serverVersion.takeIf { it.isNotBlank() }?.let { "Nextcloud $it" },
                caps.talkVersion.takeIf { it.isNotBlank() }?.let { "Talk $it" },
            ).joinToString(" · ")
            Check(SERVER, "Server $host", "Reachable, $ms ms · $versions · signed in as $account", if (ms < 1500) Health.Good else Health.Warn)
        } catch (e: TalkError) {
            val ms = SystemClock.elapsedRealtime() - started
            Check(SERVER, "Server $host", "Not reachable after $ms ms: ${e.technical()} (signed in as $account)", Health.Bad)
        }
    }

    private fun pushChecks(): List<Check> {
        val push = container.pushRegistrar
        val routes = push.routes.value
        val route = routes.all.firstOrNull { it.packageName == routes.current }?.label
        val status = when (val s = push.status.value) {
            PushStatus.Active -> Check(PUSH, "Push", "Active" + (route?.let { ", via $it" } ?: ""), Health.Good)
            PushStatus.Registering -> Check(PUSH, "Push", "Registering with the server…", Health.Running)
            PushStatus.NoDistributor -> Check(PUSH, "Push", "No way to receive push on this phone (no Google Play services, no push app)", Health.Bad)
            // Only the server's admin can change it; a tester sends the report to whoever that is.
            PushStatus.ServerDisabled -> Check(
                PUSH, "Push", "Web push is off on the server: ask its admin to turn it on", Health.Bad,
                reportNote = "Admin, on the server: sudo -u www-data php occ config:app:set notifications " +
                    "webpush_enabled --value=1 --type=boolean   (then reopen TalQ)",
            )
            PushStatus.Unknown -> Check(PUSH, "Push", "Not set up yet", Health.Warn)
            is PushStatus.Error -> Check(PUSH, "Push", s.message, Health.Bad)
        }
        val last = DiagStore.lastPushAt(context)
        val lastCheck = if (last == 0L) {
            Check(PUSH, "Last push", "None received since this version was installed", Health.Info)
        } else {
            Check(PUSH, "Last push", "${ago(System.currentTimeMillis() - last)} ago", Health.Info)
        }
        val manager = NotificationManagerCompat.from(context)
        val allowed = manager.areNotificationsEnabled()
        val directOff = TalqNotifications.alertState(context, NotificationKind.Direct) == AlertState.Off
        val otherOff = TalqNotifications.alertState(context, NotificationKind.Other) == AlertState.Off
        val notifications = when {
            !allowed -> Check(PUSH, "Notifications", "Blocked for this app", Health.Bad, DiagAction.Notifications)
            directOff && otherOff -> Check(PUSH, "Notifications", "Allowed, but all messages are off", Health.Bad, DiagAction.Notifications)
            directOff -> Check(PUSH, "Notifications", "Allowed, but direct messages and mentions are off", Health.Bad, DiagAction.Notifications)
            otherOff -> Check(PUSH, "Notifications", "Allowed, but other messages are off", Health.Warn, DiagAction.Notifications)
            else -> Check(PUSH, "Notifications", "Allowed", Health.Good, DiagAction.Notifications)
        }
        val power = context.getSystemService(PowerManager::class.java)
        val battery = if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            Check(PUSH, "Battery", "Not restricted", Health.Good, DiagAction.Battery)
        } else {
            Check(PUSH, "Battery", "Optimised by Android: some phones then hold back push after a while", Health.Warn, DiagAction.Battery)
        }
        val list = mutableListOf(status, lastCheck, notifications, battery)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val full = context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false
            list += if (full) Check(PUSH, "Full-screen calls", "Allowed", Health.Good, DiagAction.FullScreenCalls)
            else Check(PUSH, "Full-screen calls", "Not allowed: incoming calls ring as a notification only", Health.Bad, DiagAction.FullScreenCalls)
        }
        return list
    }

    private fun networkChecks(): List<Check> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = cm.activeNetwork ?: return listOf(Check(NETWORK, "Network", "No connection", Health.Bad))
        val caps = cm.getNetworkCapabilities(network)
        val link = cm.getLinkProperties(network)
        val kind = when {
            caps == null -> "Unknown"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val parts = mutableListOf(kind)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) parts += "VPN"
        parts += if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) "not metered" else "metered"
        parts += if (link?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress } == true) "IPv6" else "no IPv6"
        link?.httpProxy?.let { parts += "proxy ${it.host}:${it.port}" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && link?.isPrivateDnsActive == true) parts += "private DNS"
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return listOf(
            Check(
                NETWORK, "Network", parts.joinToString(" · ") + if (validated) "" else " · Android sees no internet here",
                if (validated) Health.Good else Health.Warn,
            ),
        )
    }

    private fun shiftCheck(): Check? {
        val shift = container.shift
        val newest = shift.statuses.value.values.maxOfOrNull { it.fetchedAtMs } ?: 0L
        val answered = if (newest > 0) ", last answer ${ago(System.currentTimeMillis() - newest)} ago" else ""
        return when (val link = shift.link.value) {
            is ErpLink.Paired -> Check(SHIFT, "Shift status", "Paired as ${link.displayName}$answered", Health.Good)
            is ErpLink.Pairing -> Check(SHIFT, "Shift status", "Pairing…", Health.Running)
            ErpLink.Rejected -> Check(SHIFT, "Shift status", "The ERP no longer accepts this phone: pair it again", Health.Bad)
            ErpLink.NotPaired -> if (container.fixedErp != null) Check(SHIFT, "Shift status", "Not paired", Health.Warn) else null
        }
    }

    private suspend fun callChecks(session: TalkSession, put: (String, Check) -> Unit) {
        if (!session.capabilities.callsEnabled) {
            put("calls", Check(CALLS, "Calls", "Switched off on the server", Health.Warn))
            return
        }
        put("calls", Check(CALLS, "Signaling server", "Checking…", Health.Running))
        val settings = try {
            withContext(Dispatchers.IO) { session.client.signalingSettings() }
        } catch (e: TalkError) {
            put("calls", Check(CALLS, "Signaling server", "Could not read the call settings: ${e.userMessage()}", Health.Bad))
            return
        }
        if (!settings.hasHpb) {
            put("calls", Check(CALLS, "Signaling server", "The server has none: calls are not offered", Health.Warn))
            return
        }
        if (container.calls.busy) {
            put("calls", Check(CALLS, "Signaling server", "${hostOf(settings.server)}: not tested during a call", Health.Info))
            return
        }
        put("calls", hpbCheck(session, settings))
        if (hpbCandidates(settings).size > 1) {
            put("nodes", Check(CALLS, "Signaling nodes", "Measuring…", Health.Running))
            put("nodes", nodesCheck(settings))
        }
        // One line per STUN/TURN address; TURN is what gets a call through a strict firewall.
        // A few at a time: one after another, 14 addresses could take a minute and a half.
        val urls = settings.iceServers.flatMap { server -> server.urls.map { server to it } }.distinctBy { it.second }.take(MAX_ICE_URLS)
        urls.forEachIndexed { i, (_, url) -> put("ice$i", Check(CALLS, iceName(url), "Checking…", Health.Running)) }
        val gate = Semaphore(PARALLEL_PROBES)
        coroutineScope {
            urls.forEachIndexed { i, (server, url) ->
                launch {
                    gate.withPermit {
                        val check = iceCheck(server, url)
                        // put() rebuilds the list for the screen: one writer at a time.
                        synchronized(this@Diagnostics) { put("ice$i", check) }
                    }
                }
            }
        }
    }

    private suspend fun iceCheck(server: IceServer, url: String): Check {
        val name = iceName(url)
        val turn = url.startsWith("turn")
        return when (val r = TurnProbe.probe(context, server, url)) {
            is TurnProbe.Result.Answered ->
                Check(CALLS, name, if (turn) "Relay granted in ${r.millis} ms" else "Answered in ${r.millis} ms", Health.Good)
            TurnProbe.Result.NoAnswer ->
                Check(CALLS, name, if (turn) "No relay from this address" else "No answer", if (turn) Health.Bad else Health.Warn)
            is TurnProbe.Result.Failed -> Check(CALLS, name, "Could not test (${r.reason})", Health.Warn)
        }
    }

    /** Which of the server's signaling nodes a call would use from here, and how far each is. */
    private suspend fun nodesCheck(settings: talq.core.call.SignalingSettings): Check {
        val (reading, skipped) = container.calls.nodes.measure(settings)
        val named = hostOf(settings.server)
        return when {
            skipped == NearestHpb.Skipped.Proxy -> Check(CALLS, "Signaling nodes", "Not measured behind a proxy: calls use $named", Health.Info)
            reading == null -> Check(CALLS, "Signaling nodes", "Not measured: no network", Health.Warn)
            reading.pick.index < 0 -> Check(CALLS, "Signaling nodes", "None answered, calls use $named · ${reading.line()}", Health.Warn)
            else -> Check(CALLS, "Signaling nodes", "Calls use ${hpbHost(reading.chosen)} · ${reading.line()}", Health.Good)
        }
    }

    /** Signs in on the signaling server as a call would, then closes the session again. */
    private suspend fun hpbCheck(session: TalkSession, settings: talq.core.call.SignalingSettings): Check {
        val host = hostOf(settings.server)
        val http = newHpbHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connection = HpbConnection(http, settings, session.client.serverUrl, scope)
        val started = SystemClock.elapsedRealtime()
        return try {
            connection.connect()
            val ms = SystemClock.elapsedRealtime() - started
            val mcu = (connection.state.value as? HpbState.Ready)?.features?.contains("mcu") == true
            Check(CALLS, "Signaling server $host", "Signed in in $ms ms" + if (mcu) "" else " · no media server (mcu) offered", if (mcu) Health.Good else Health.Bad)
        } catch (e: TalkError) {
            val ms = SystemClock.elapsedRealtime() - started
            Check(CALLS, "Signaling server $host", "Could not sign in after $ms ms: ${e.technical()}", Health.Bad)
        } finally {
            runCatching { connection.close(sayBye = true) }
            scope.cancel()
            http.close()
        }
    }

    companion object {
        const val APP = "App"
        const val SERVER = "Server"
        const val PUSH = "Push and notifications"
        const val GMS_PACKAGE = "com.google.android.gms"

        /** SHA-1 of the certificate Google signs Google Play services with. */
        val GOOGLE_GMS_CERTS = setOf("38918a453d07199354f8b19af05ec6562ced5788")

        /** An app's own data folder: /data/user/<n>/<package>, /data/user_de/<n>/<package> or /data/data/<package>. */
        val HOST_IN_PATH = Regex("^/data/(?:user(?:_de)?/\\d+|data)/([^/]+)")

        fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        const val CALLS = "Calls"
        const val NETWORK = "Network"
        const val SHIFT = "Shift status"
        private const val MAX_ICE_URLS = 24
        private const val PARALLEL_PROBES = 4

        fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url.substringAfter("://").substringBefore('/')

        /** "turn:host:3478?transport=udp" -> "TURN host:3478 (udp)"; never the credentials. */
        fun iceName(url: String): String {
            val scheme = url.substringBefore(':')
            val rest = url.substringAfter(':').substringBefore('?')
            val transport = url.substringAfter("transport=", "").takeIf { it.isNotEmpty() }
            val kind = when (scheme) {
                "turns" -> "TURN (TLS)"
                "turn" -> "TURN"
                else -> "STUN"
            }
            return "$kind $rest" + (transport?.let { " ($it)" } ?: "")
        }

        fun ago(millis: Long): String {
            val s = millis / 1000
            return when {
                s < 90 -> "$s s"
                s < 90 * 60 -> "${s / 60} min"
                s < 48 * 3600 -> "${s / 3600} h"
                else -> "${s / 86400} days"
            }
        }
    }
}
