package talq.core.org

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import talq.core.TalkClient
import talq.core.net.jsonDeeperThan
import talq.core.shift.ErpBase
import kotlin.io.encoding.Base64

/**
 * What an organisation's brand file says (payload v1; the spec's "The payload (v1)"). It is read only
 * after its signature checked out, and still strictly: the owner's tool is not the only thing that
 * could have made a file a phone is shown. Nothing here is secret on the phone; the code is.
 */
class BrandPayload(
    /** A random brand id, `[a-z0-9]{8,32}`: the storage key, never the name. */
    val id: String,
    /** Epoch seconds of issue: a version that only goes forward (see [acceptsRefresh]). */
    val issued: Long,
    val name: String,
    val welcome: String?,
    /** The fixed Nextcloud server (see [canonicalOrgServer]). */
    val server: String,
    /** The ERP address for shift status, when the organisation has one (see [orgErp]). */
    val erp: String?,
    /** `0xRRGGBB`. */
    val accent: Int,
    /** One of [THEMES], or null for none. */
    val theme: String?,
    /** A PNG of at most 1024 x 1024. */
    val logoPng: ByteArray,
) {
    fun copy(
        id: String = this.id, issued: Long = this.issued, name: String = this.name, welcome: String? = this.welcome,
        server: String = this.server, erp: String? = this.erp, accent: Int = this.accent, theme: String? = this.theme,
        logoPng: ByteArray = this.logoPng,
    ) = BrandPayload(id, issued, name, welcome, server, erp, accent, theme, logoPng)

    override fun toString(): String = "BrandPayload(id=$id, issued=$issued)"

    companion object {
        val THEMES: Set<String> = setOf("ember", "warm", "vivid", "paper", "system")
    }
}

/** The payload's fixed size: every brand file has the same length, so its size says nothing. */
const val PAYLOAD_SIZE: Int = 163_840

private const val NAME_MAX = 40
private const val WELCOME_MAX = 80
private const val LOGO_MAX_SIDE = 1024
private val ID = Regex("[a-z0-9]{8,32}")
private val ACCENT = Regex("#[0-9A-Fa-f]{6}")

/** The keys v1 understands; a `crit` entry outside them refuses the file. */
private val KNOWN_KEYS = setOf("v", "id", "issued", "name", "welcome", "server", "erp", "accent", "theme", "logo")

/** A payload, or null when anything about it is outside the v1 rules. */
fun parseBrandPayload(bytes: ByteArray): BrandPayload? {
    if (bytes.size != PAYLOAD_SIZE) return null
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: Exception) {
        return null
    }
    if (jsonDeeperThan(text, 4)) return null
    val o = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null

    if (o.number("v") != 1L) return null
    o["crit"]?.let { crit ->
        val list = crit as? JsonArray ?: return null
        for (e in list) {
            val key = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (key !in KNOWN_KEYS) return null
        }
    }
    val id = o.text("id")?.takeIf { ID.matches(it) } ?: return null
    val issued = o.number("issued")?.takeIf { it >= 0 } ?: return null
    val name = o.text("name")?.let { cleanOrgText(it, NAME_MAX) }?.takeIf { it.isNotBlank() } ?: return null
    val welcome = when (val w = o["welcome"]) {
        null -> null
        else -> (w as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { cleanOrgText(it, WELCOME_MAX) } ?: return null
    }
    val server = o.text("server")?.let { canonicalOrgServer(it) } ?: return null
    val erp = when (val e = o["erp"]) {
        null -> null
        else -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { orgErp(it) } ?: return null
    }
    val accent = o.text("accent")?.takeIf { ACCENT.matches(it) }?.substring(1)?.toInt(16) ?: return null
    val theme = when (val t = o["theme"]) {
        null -> null
        else -> (t as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in BrandPayload.THEMES } ?: return null
    }
    val logo = o.text("logo")?.let {
        try {
            Base64.Default.decode(it)
        } catch (e: Exception) {
            null
        }
    } ?: return null
    val (w, h) = pngSize(logo) ?: return null
    if (w > LOGO_MAX_SIDE || h > LOGO_MAX_SIDE) return null
    return BrandPayload(id, issued, name, welcome, server, erp, accent, theme, logo)
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number (never a string that looks like one) as a whole number. */
private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

/**
 * [text] when it is plain, single-line text of at most [max] code points: no control or format
 * characters, no bidi controls, zero-width characters or line breaks, no lone surrogates, no tag
 * characters. Null otherwise. Nothing is cleaned: a brand that needs cleaning is refused.
 */
fun cleanOrgText(text: String, max: Int): String? {
    var count = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate()) {
            val low = text.getOrNull(i + 1) ?: return null
            if (!low.isLowSurrogate()) return null
            val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (low.code - 0xDC00)
            if (cp in 0xE0000..0xE007F) return null // tag characters
            if (cp >= 0xF0000) return null // private use planes
            i += 2
        } else {
            if (c.isLowSurrogate()) return null
            when (c.category) {
                CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR,
                CharCategory.PARAGRAPH_SEPARATOR, CharCategory.PRIVATE_USE, CharCategory.UNASSIGNED -> return null
                else -> Unit
            }
            i++
        }
        count++
        if (count > max) return null
    }
    return text
}

/**
 * [text] when it is a server address an organisation may fix: `https://`, a lowercase ASCII host
 * with a dot (no `xn--` label, no IP literal, not `localhost` or `*.local`), an optional port and
 * path, no user part, query or fragment, already in the form [TalkClient.normalizeServerUrl] gives.
 */
fun canonicalOrgServer(text: String): String? {
    val rest = text.removePrefix("https://").takeIf { text.startsWith("https://") } ?: return null
    val authority = rest.substringBefore('/')
    val path = rest.substring(authority.length)
    if (!isOrgAuthority(authority)) return null
    if (path.isNotEmpty() && !path.all { it.isLetterOrDigit() && it.code < 128 || it in "-._~/%" }) return null
    if (path.endsWith('/') || "//" in path) return null
    return text.takeIf { TalkClient.normalizeServerUrl(text) == text }
}

/** [text] when it is an ERP address an organisation may set: [ErpBase.parse], https only, the same host rules. */
fun orgErp(text: String): String? {
    if (!text.startsWith("https://")) return null
    val base = ErpBase.parse(text) ?: return null
    if (base.url != text) return null
    val authority = text.removePrefix("https://").substringBefore('/')
    return text.takeIf { isOrgAuthority(authority) }
}

private fun isOrgAuthority(authority: String): Boolean {
    if (authority.isEmpty() || authority.any { it in "@\\?#[]" }) return false
    val host = authority.substringBefore(':')
    val port = authority.substringAfter(':', "")
    if (authority.contains(':')) {
        val p = port.toIntOrNull() ?: return false
        if (p !in 1..65535 || port.startsWith('0')) return false
    }
    if (host.length > 253 || '.' !in host) return false
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return false
    val labels = host.split('.')
    for (label in labels) {
        if (label.isEmpty() || label.length > 63) return false
        if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return false
        if (label.startsWith('-') || label.endsWith('-')) return false
        if (label.startsWith("xn--")) return false
    }
    // An IP literal: the last label all digits (IPv4); IPv6 needs brackets, refused above.
    if (labels.last().all { it in '0'..'9' }) return false
    return true
}

/** A PNG's width and height from its IHDR chunk, or null when [bytes] is not a PNG. */
fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
    val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (bytes.size < 24) return null
    for (i in sig.indices) if (bytes[i] != sig[i]) return null
    if (bytes.decodeToString(12, 16) != "IHDR") return null
    fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
        ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
    val w = int(16)
    val h = int(20)
    if (w <= 0 || h <= 0) return null
    return w to h
}
