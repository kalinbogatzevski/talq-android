package talq.app.org

import android.content.Context
import android.util.Base64
import talq.app.data.KeystoreSeal
import talq.core.org.AddressChange
import talq.core.org.BrandPayload
import talq.core.org.Derived
import java.io.File

/** An organisation the phone is set up for: what the screens and the shift service read. */
data class Organisation(
    val brandId: String,
    val name: String,
    val welcome: String?,
    val server: String,
    val erp: String?,
    /** `0xRRGGBB`. */
    val accent: Int,
    val theme: String?,
    val logo: File,
    val issued: Long,
)

/**
 * What the phone keeps of its organisation (spec: "Storage"). The code itself is never kept.
 * - Sealed under the Keystore alias `talq.org.v1`: the brand file's id and its AES key (48 bytes),
 *   all a refresh needs.
 * - Plain, not secret: the verified envelope (`filesDir/org/envelope.json`, checked again at every
 *   start), the logo (`filesDir/org/logo.png`), and the preferences `organisation` (name, welcome,
 *   addresses, accent, theme, issued, brand id; the ETag and the next refresh), read on the main
 *   thread at start so the first frame already has the look.
 * - A held address change: `filesDir/org/pending.json` (the newer envelope) and its addresses.
 * Signing out keeps all of it; "Use without an organisation" removes all of it.
 */
internal class OrganisationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("organisation", Context.MODE_PRIVATE)
    private val dir = File(context.applicationContext.filesDir, "org")
    private val seal = KeystoreSeal("talq.org.v1")

    val logoFile: File get() = File(dir, "logo.png")
    private val envelopeFile get() = File(dir, "envelope.json")
    private val pendingFile get() = File(dir, "pending.json")

    /** The organisation as last stored, from the preferences only (main thread, quick). */
    fun quick(): Organisation? {
        val id = prefs.getString(K_ID, null) ?: return null
        return Organisation(
            brandId = id,
            name = prefs.getString(K_NAME, null) ?: return null,
            welcome = prefs.getString(K_WELCOME, null),
            server = prefs.getString(K_SERVER, null) ?: return null,
            erp = prefs.getString(K_ERP, null),
            accent = prefs.getInt(K_ACCENT, 0),
            theme = prefs.getString(K_THEME, null),
            logo = logoFile,
            issued = prefs.getLong(K_ISSUED, 0),
        )
    }

    fun envelope(): ByteArray? = envelopeFile.takeIf { it.isFile }?.readBytes()

    fun pendingEnvelope(): ByteArray? = pendingFile.takeIf { it.isFile }?.readBytes()

    fun pendingChange(): AddressChange? {
        val newServer = prefs.getString(K_P_SERVER, null) ?: return null
        val oldServer = prefs.getString(K_SERVER, null) ?: return null
        return AddressChange(oldServer, newServer, prefs.getString(K_ERP, null), prefs.getString(K_P_ERP, null))
    }

    /** The brand file's id and key. Off the main thread; throws when the Keystore cannot open them. */
    fun keys(): Derived? {
        val sealed = prefs.getString(K_SEALED, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val plain = seal.open(sealed)
        if (plain.size != 48) return null
        return Derived(plain.copyOfRange(0, 16), plain.copyOfRange(16, 48)).also { plain.fill(0) }
    }

    /** Stores a verified brand file's content (off the main thread). [keys] only when they change. */
    fun save(payload: BrandPayload, envelope: ByteArray, keys: Derived?) {
        dir.mkdirs()
        writeWhole(envelopeFile, envelope)
        writeWhole(logoFile, payload.logoPng)
        val editor = prefs.edit()
        if (keys != null) {
            val sealed = seal.seal(keys.fileId + keys.aesKey)
            editor.putString(K_SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP)).remove(K_ETAG)
        }
        editor
            .putString(K_ID, payload.id)
            .putString(K_NAME, payload.name)
            .putString(K_WELCOME, payload.welcome)
            .putString(K_SERVER, payload.server)
            .putString(K_ERP, payload.erp)
            .putInt(K_ACCENT, payload.accent)
            .putString(K_THEME, payload.theme)
            .putLong(K_ISSUED, payload.issued)
            .remove(K_P_SERVER).remove(K_P_ERP)
            .commit()
        pendingFile.delete()
    }

    /** Holds a newer file whose addresses differ, until the person takes it. */
    fun hold(envelope: ByteArray, change: AddressChange) {
        dir.mkdirs()
        writeWhole(pendingFile, envelope)
        prefs.edit().putString(K_P_SERVER, change.newServer).putString(K_P_ERP, change.newErp).commit()
    }

    var etag: String?
        get() = prefs.getString(K_ETAG, null)
        set(value) = prefs.edit().putString(K_ETAG, value).apply()

    var nextRefreshAt: Long
        get() = prefs.getLong(K_NEXT, 0)
        set(value) = prefs.edit().putLong(K_NEXT, value).apply()

    fun clear() {
        prefs.edit().clear().commit()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun writeWhole(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not store ${target.name}" }
        }
    }

    private companion object {
        const val K_ID = "brandId"
        const val K_NAME = "name"
        const val K_WELCOME = "welcome"
        const val K_SERVER = "server"
        const val K_ERP = "erp"
        const val K_ACCENT = "accent"
        const val K_THEME = "theme"
        const val K_ISSUED = "issued"
        const val K_SEALED = "sealed"
        const val K_ETAG = "etag"
        const val K_NEXT = "nextRefreshAt"
        const val K_P_SERVER = "pendingServer"
        const val K_P_ERP = "pendingErp"
    }
}
