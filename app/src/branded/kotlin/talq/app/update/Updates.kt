package talq.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * Only [download] (an https link on the channel's own server that a browser can open without a
 * password) and the version are used.
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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(m.download))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
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
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_OFFER = "offer"
        const val KEY_DISMISSED = "dismissed"
        const val KEY_LAST_FAILURE = "last_failure"
        const val KEY_FAILURE_PAUSE = "failure_pause"
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
