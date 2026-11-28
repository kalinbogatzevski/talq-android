package talq.app.diag

import talq.core.org.hideInviteCodes
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import talq.app.Brand
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small facts the diagnostics need from elsewhere in the app. */
object DiagStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    /** Called for every push that reaches the app (readable or not). */
    fun pushReceived(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_PUSH, System.currentTimeMillis()).apply()
    }

    fun lastPushAt(context: Context): Long = prefs(context).getLong(KEY_LAST_PUSH, 0L)

    private const val KEY_LAST_PUSH = "last_push_at"
}

/**
 * The text a tester sends: every check, then the app's own recent log (Android lets an app read
 * its own log, nothing else). Lines that look like they carry a secret are cut, as a second
 * line of defence: the app does not log passwords, tickets, keys or TURN credentials.
 */
object DiagReport {
    private val SECRET = Regex("""(?i)\b(password|passwd|ticket|token|credential|secret|authorization|api[-_]?key|apikey|key)\b(\s*[=:]\s*|\s+)\S+""")
    /** The query of any address, whatever its scheme (`talq://organisation?code=…` included). */
    private val QUERY = Regex("""([a-z][a-z0-9+.-]*)://([^\s/?#]*)([^\s?#]*)\?\S*""", RegexOption.IGNORE_CASE)

    fun text(checks: List<Check>, log: String, lastCall: String = ""): String = buildString {
        append("TalQ diagnostics\n================\n")
        append("Generated: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())).append('\n')
        var group = ""
        for (c in checks) {
            if (c.group != group) {
                group = c.group
                append("\n[").append(group).append("]\n")
            }
            append(mark(c.health)).append(' ').append(c.title).append(": ").append(c.detail).append('\n')
            c.reportNote?.let { append("     ").append(it).append('\n') }
        }
        if (lastCall.isNotBlank()) append("\n[Last call]\n").append(redact(lastCall)).append('\n')
        append("\n[Recent log]\n").append(log.ifBlank { "(empty)" }).append('\n')
    }

    private fun mark(h: Health) = when (h) {
        Health.Good -> "OK  "
        Health.Warn -> "WARN"
        Health.Bad -> "FAIL"
        Health.Info -> "INFO"
        Health.Running -> "... "
    }

    /** This process's recent log lines, with anything secret-looking hidden. */
    suspend fun recentLog(lines: Int = 600): String = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", lines.toString(), "--pid", Process.myPid().toString())
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            redact(text)
        } catch (e: Exception) {
            "(the log could not be read: ${e.javaClass.simpleName})"
        }
    }

    fun redact(text: String): String = text.lineSequence().joinToString("\n") { line ->
        line.replace(QUERY) { "${it.groupValues[1]}://${it.groupValues[2]}${it.groupValues[3]}?<hidden>" }
            .replace(SECRET) { "${it.groupValues[1]}${it.groupValues[2]}<hidden>" }
            // Real invite codes only (a matching check symbol): ordinary 17-letter words stay.
            .let(::hideInviteCodes)
    }

    /** Writes the report into the shared cache and opens the share sheet for it. */
    suspend fun share(context: Context, text: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared/diagnostics").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            File(dir, "TalQ-diagnostics-$stamp.txt").apply { writeText(text) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "${Brand.appName(context)} diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, "Send diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
