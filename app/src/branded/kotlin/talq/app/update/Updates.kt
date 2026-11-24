package talq.app.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import talq.app.BuildConfig
import java.util.concurrent.TimeUnit

private const val TAG = "TalqUpdate"

/** The branded build: its organisation's channel when the brand store names one, else none. */
internal fun newAppUpdates(context: Context): AppUpdates {
    val channel = UpdateChannel.fromBuild() ?: return NoAppUpdates
    return BrandedAppUpdates(context.applicationContext, channel)
}

/**
 * The organisation's update channel, from the brand store (BuildConfig): a JSON manifest on a
 * share of its own server. [authorization] opens a password-protected public share (the share
 * token as the user name); empty for an open one.
 */
internal class UpdateChannel(val manifestUrl: String, val authorization: String) {
    /** The manifest's host: a download link must be on it. */
    val host: String? = Uri.parse(manifestUrl).host?.lowercase()

    /** The share's folder (the manifest's, with its slash): only a download in it gets [authorization]. */
    val folder: String = manifestUrl.substringBeforeLast('/') + "/"

    companion object {
        fun fromBuild(): UpdateChannel? {
            val url = BuildConfig.BRAND_UPDATE_MANIFEST_URL.trim()
            // https only, and a file, not a folder.
            if (!url.startsWith("https://") || url.endsWith("/") || url.length <= "https://".length) return null
            val token = BuildConfig.BRAND_UPDATE_SHARE_TOKEN
            val auth = if (token.isEmpty()) "" else Credentials.basic(token, BuildConfig.BRAND_UPDATE_SHARE_PASSWORD)
            return UpdateChannel(url, auth)
        }
    }
}

/**
 * What the channel offers, as `scripts/publish-update.sh` writes it:
 * `{"version", "versionCode", "download", "asset", "sha256", "size", "releaseDate", "notes"}`.
 * Only [download] (an https link on the channel's own server; since 0.6.4 the APK next to the
 * manifest in the same password-protected share) and the version are used.
 */
internal data class UpdateManifest(
    val version: String,
    val versionCode: Long,
    val download: String,
    val notes: String,
) {
    companion object {
        fun parse(text: String, host: String?): UpdateManifest? = try {
            val o = Json.parseToJsonElement(text).jsonObject
            val version = o["version"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val code = o["versionCode"]?.jsonPrimitive?.longOrNull ?: 0L
            val download = o["download"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val notes = o["notes"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val link = Uri.parse(download)
            val onChannel = link.scheme == "https" && host != null && link.host?.lowercase() == host && link.userInfo == null
            if (version.isEmpty() || version.length > 40 || code <= 0 || !onChannel || download.length > 2000) null
            else UpdateManifest(version, code, download, notes)
        } catch (e: IllegalArgumentException) {
            // Not JSON, or not an object (kotlinx.serialization throws subclasses of it).
            null
        }
    }
}

/** The check failed for a reason the user can be told; [status] is the HTTP status, if any. */
private class UpdateProblem(message: String, val status: Int = 0) : Exception(message)

/**
 * The branded build learning about its updates (see [AppUpdates]): the manifest is asked at most
 * every [CHECK_EVERY_MILLIS] when the app comes to the front (and whenever Settings asks); a
 * failed check waits an hour (two when the share refuses the credentials). The offer is
 * remembered across restarts, so the banner does not wait for the next check. Android itself
 * makes sure an update comes from the same developer: it refuses an APK signed with another key.
 *
 * "Update" hands the APK to Android's download manager (with the share's password: the server
 * allows no public link without one) and installs nothing: the finished download's notification,
 * or the Downloads app, opens Android's installer.
 */
internal class BrandedAppUpdates(private val app: Context, private val channel: UpdateChannel) : AppUpdates {
    override val enabled = true
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val _dismissed = MutableStateFlow(prefs.getString(KEY_DISMISSED, null))
    override val dismissed: StateFlow<String?> = _dismissed.asStateFlow()

    @Volatile
    private var offer: UpdateManifest? = null
    @Volatile
    private var job: Job? = null
    private val _downloading = MutableStateFlow(false)
    override val downloading: StateFlow<Boolean> = _downloading.asStateFlow()
    private var watch: Job? = null

    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        val kept = prefs.getString(KEY_OFFER, null)?.let { UpdateManifest.parse(it, channel.host) }
        if (kept != null && kept.versionCode > BuildConfig.VERSION_CODE) {
            offer = kept
            _state.value = UpdateState.Available(kept.version, kept.notes)
        } else if (kept != null) {
            // Installed meanwhile (this is that version or a later one).
            prefs.edit().remove(KEY_OFFER).apply()
        }
        // A download started before the app was closed: the buttons stay disabled until it ends.
        offer?.let { m -> knownDownload(m.version)?.let { id -> if (isRunning(statusOf(id))) watch(id) } }
    }

    override fun checkIfDue() {
        val clock = System.currentTimeMillis()
        if (clock - prefs.getLong(KEY_LAST_CHECK, 0L) in 0 until CHECK_EVERY_MILLIS) return
        // After a failed check, not again at every return to the app (a changed share password
        // would otherwise mean a failed sign-in at every start, from every phone).
        if (clock - prefs.getLong(KEY_LAST_FAILURE, 0L) in 0 until prefs.getLong(KEY_FAILURE_PAUSE, 0L)) return
        checkNow()
    }

    override fun checkNow() {
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch { check() }
        }
    }

    /** Reads the manifest. A known offer stays on screen meanwhile. */
    private fun check() {
        val before = offer
        if (before == null) _state.value = UpdateState.Checking
        try {
            val (text, manifest) = fetchManifest()
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).remove(KEY_LAST_FAILURE).apply()
            if (manifest != null && manifest.versionCode > BuildConfig.VERSION_CODE) {
                offer = manifest
                prefs.edit().putString(KEY_OFFER, text).apply()
                _state.value = UpdateState.Available(manifest.version, manifest.notes)
            } else {
                offer = null
                prefs.edit().remove(KEY_OFFER).apply()
                _state.value = UpdateState.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.javaClass.simpleName}")
            // Refused credentials do not get better by asking again soon.
            val refused = (e as? UpdateProblem)?.status.let { it == 401 || it == 403 }
            val pause = if (refused) CHECK_EVERY_MILLIS else FAILURE_PAUSE_MILLIS
            prefs.edit().putLong(KEY_LAST_FAILURE, System.currentTimeMillis()).putLong(KEY_FAILURE_PAUSE, pause).apply()
            // A known offer stays on offer; only a check with nothing to show says it failed.
            _state.value = before?.let { UpdateState.Available(it.version, it.notes) }
                ?: UpdateState.Failed((e as? UpdateProblem)?.message ?: "Couldn't reach the update channel.")
        }
    }

    override fun download(): Boolean {
        val m = offer ?: return false
        // One download per version: a tap while it runs starts nothing (three taps used to start three
        // downloads), and once it is done a tap opens Downloads, where the user taps it to install.
        knownDownload(m.version)?.let { id ->
            val status = statusOf(id)
            if (isRunning(status)) {
                watch(id)
                return true
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) return openDownloads()
        }
        val uri = Uri.parse(m.download)
        // A plain file name for Downloads (the link's own, else one made from the version).
        val name = uri.lastPathSegment?.takeIf { FILE_NAME.matches(it) } ?: "TalQ-${m.version.filter { it.isLetterOrDigit() || it == '.' }}.apk"
        val manager = app.getSystemService(DownloadManager::class.java) ?: return false
        val request = DownloadManager.Request(uri)
            .setTitle(name)
            .setDescription("When it is done, tap here to install.")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .addRequestHeader("User-Agent", talq.core.TalqCore.userAgent)
        // The share's password only goes to the share itself (the same one the manifest came from).
        if (channel.authorization.isNotEmpty() && m.download.startsWith(channel.folder)) {
            request.addRequestHeader("Authorization", channel.authorization)
        }
        return try {
            // Android 10 and later let any app put a download into Downloads; before that, the
            // app's own download folder needs no storage permission either.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            } else {
                request.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name)
            }
            val id = manager.enqueue(request)
            prefs.edit().putLong(KEY_DOWNLOAD_ID, id).putString(KEY_DOWNLOAD_VERSION, m.version).apply()
            watch(id)
            true
        } catch (e: Exception) {
            // The download manager switched off, no shared storage: say so on the banner.
            Log.w(TAG, "download not started: ${e.javaClass.simpleName}")
            false
        }
    }

    /** The download id started for [version], if any. */
    private fun knownDownload(version: String): Long? =
        prefs.getLong(KEY_DOWNLOAD_ID, -1L).takeIf { it >= 0 && prefs.getString(KEY_DOWNLOAD_VERSION, null) == version }

    /** The download manager's status for [id], or null when it no longer knows it. */
    private fun statusOf(id: Long): Int? = try {
        app.getSystemService(DownloadManager::class.java)?.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun isRunning(status: Int?) = status == DownloadManager.STATUS_PENDING ||
        status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED

    /** Keeps [downloading] true until the download [id] ends (done, failed or removed). */
    private fun watch(id: Long) {
        if (watch?.isActive == true) return
        _downloading.value = true
        watch = scope.launch {
            try {
                while (isRunning(statusOf(id))) delay(1_500)
            } finally {
                _downloading.value = false
            }
        }
    }

    /** The system's Downloads list (opening it needs no permission; the user taps the APK there). */
    private fun openDownloads(): Boolean = try {
        app.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    override fun dismiss() {
        val m = offer ?: return
        _dismissed.value = m.version
        prefs.edit().putString(KEY_DISMISSED, m.version).apply()
    }

    /** The manifest as text and parsed (null: the channel has none, which is not a failure). */
    private fun fetchManifest(): Pair<String, UpdateManifest?> {
        val request = Request.Builder().url(channel.manifestUrl).apply {
            if (channel.authorization.isNotEmpty()) header("Authorization", channel.authorization)
            header("User-Agent", talq.core.TalqCore.userAgent)
        }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return "" to null
            if (!response.isSuccessful) throw UpdateProblem("The update channel answered ${response.code}.", status = response.code)
            val body = checkNotNull(response.body)
            if (body.contentLength() > MANIFEST_MAX_BYTES) throw UpdateProblem("The update channel answered something unexpected.")
            val text = body.byteStream().use { it.readUpTo(MANIFEST_MAX_BYTES) }.toString(Charsets.UTF_8)
            val manifest = UpdateManifest.parse(text, channel.host) ?: throw UpdateProblem("The update channel answered something unexpected.")
            return text to manifest
        }
    }

    private companion object {
        // Every two hours while the app is under heavy development: new builds come often.
        const val CHECK_EVERY_MILLIS = 2L * 60 * 60 * 1000
        const val FAILURE_PAUSE_MILLIS = 60L * 60 * 1000
        const val MANIFEST_MAX_BYTES = 256 * 1024
        val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.apk")
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_OFFER = "offer"
        const val KEY_DISMISSED = "dismissed"
        const val KEY_LAST_FAILURE = "last_failure"
        const val KEY_FAILURE_PAUSE = "failure_pause"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_DOWNLOAD_VERSION = "download_version"
    }
}

/** Up to [max] bytes (readNBytes needs API 33). */
private fun java.io.InputStream.readUpTo(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
