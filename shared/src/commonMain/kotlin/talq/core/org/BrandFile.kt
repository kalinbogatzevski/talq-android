package talq.core.org

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import talq.core.net.jsonDeeperThan
import kotlin.io.encoding.Base64

/**
 * The brand file (format v1; the spec's "From code to file").
 *
 * `"TQB1"` + a 12-byte nonce + AES-256-GCM(the code's key, additional data `"TQB1"` + the 16 id
 * bytes) of the envelope `{"v":1,"key":"<8 hex>","payload":"<base64>","sig":"<base64>"}`. The
 * payload is padded to [PAYLOAD_SIZE], so every file has the same length. The signature is Ed25519
 * over `"talq-brand-v1"` + the key id + the 16 id bytes + the payload bytes: it binds the format, the
 * key, the code and the content, so a signed payload copied under another code is refused.
 */
const val BRAND_MAGIC: String = "TQB1"

/** Base64 length of [n] bytes with padding. */
private fun b64Length(n: Int): Int = (n + 2) / 3 * 4

/** The envelope's fixed length: `{"v":1,"key":"xxxxxxxx","payload":"…","sig":"…"}`. */
val ENVELOPE_SIZE: Int = """{"v":1,"key":"","payload":"","sig":""}""".length + 8 + b64Length(PAYLOAD_SIZE) + b64Length(64)

/** Every brand file's length. */
val BRAND_FILE_SIZE: Int = BRAND_MAGIC.length + 12 + ENVELOPE_SIZE + 16

/** What opening a brand file gave. */
sealed interface Opened {
    /** A file the owner signed for this code: its payload, the payload's bytes and the (decrypted) envelope. */
    class Ok(val payload: BrandPayload, val payloadBytes: ByteArray, val envelope: ByteArray) : Opened {
        override fun toString(): String = "Ok($payload)"
    }

    /** Anything else. One answer for every reason, so the phone tells an attacker nothing. */
    data object Bad : Opened
}

/** The additional data of a code's file: the magic and its 16 id bytes. */
internal fun brandAad(fileId: ByteArray): ByteArray = BRAND_MAGIC.encodeToByteArray() + fileId

/** What the signature covers. */
internal fun signedMessage(keyId: String, fileId: ByteArray, payload: ByteArray): ByteArray =
    ORG_SALT.encodeToByteArray() + keyId.encodeToByteArray() + fileId + payload

/**
 * Opens [file] with a code's [keys] and checks the owner's signature against [trusted] (key id ->
 * public key hex). In this order: the size, the magic, the decryption, the envelope, the key id,
 * the signature, the payload rules.
 */
fun openBrandFile(file: ByteArray, keys: Derived, trusted: Map<String, String> = TRUSTED_BRAND_KEYS): Opened {
    if (file.size != BRAND_FILE_SIZE) return Opened.Bad
    if (file.decodeToString(0, 4) != BRAND_MAGIC) return Opened.Bad
    val nonce = file.copyOfRange(4, 16)
    val envelope = aesGcmOpen(keys.aesKey, nonce, brandAad(keys.fileId), file.copyOfRange(16, file.size)) ?: return Opened.Bad
    return verifyEnvelope(envelope, keys.fileId, trusted)
}

/**
 * Checks a decrypted [envelope] for the code whose file id is [fileId]: also what the phone does
 * again at every start with the stored one, against the key list of the version then running.
 */
fun verifyEnvelope(envelope: ByteArray, fileId: ByteArray, trusted: Map<String, String> = TRUSTED_BRAND_KEYS): Opened {
    if (envelope.size != ENVELOPE_SIZE || fileId.size != 16) return Opened.Bad
    val text = try {
        envelope.decodeToString(throwOnInvalidSequence = true)
    } catch (e: Exception) {
        return Opened.Bad
    }
    if (jsonDeeperThan(text, 2)) return Opened.Bad
    val o = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return Opened.Bad
    if (o.keys != setOf("v", "key", "payload", "sig")) return Opened.Bad
    val v = (o["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
    if (v != "1") return Opened.Bad
    val keyId = (o["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Opened.Bad
    if (keyId.length != 8 || !keyId.all { it in '0'..'9' || it in 'a'..'f' }) return Opened.Bad
    val publicKey = trusted[keyId]?.hexToBytesOrNull()?.takeIf { it.size == 32 } ?: return Opened.Bad
    fun b64(key: String): ByteArray? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
        try {
            Base64.Default.decode(it)
        } catch (e: Exception) {
            null
        }
    }
    val payload = b64("payload")?.takeIf { it.size == PAYLOAD_SIZE } ?: return Opened.Bad
    val sig = b64("sig")?.takeIf { it.size == 64 } ?: return Opened.Bad
    if (!ed25519Verify(publicKey, sig, signedMessage(keyId, fileId, payload))) return Opened.Bad
    val parsed = parseBrandPayload(payload) ?: return Opened.Bad
    return Opened.Ok(parsed, payload, envelope)
}

/**
 * Whether a refreshed file may take the stored one's place: a newer `issued`, or the same one with
 * the very same bytes. An older genuine file (a taken-over host putting back an old version) never
 * comes back.
 */
fun acceptsRefresh(storedIssued: Long, storedPayload: ByteArray, fresh: Opened.Ok): Boolean =
    fresh.payload.issued > storedIssued || (fresh.payload.issued == storedIssued && fresh.payloadBytes.contentEquals(storedPayload))

/** Whether the server or the ERP address differs between two versions of a brand. */
fun addressChanged(old: BrandPayload, new: BrandPayload): Boolean = old.server != new.server || old.erp != new.erp

/** A brand's new addresses, held until the person agrees to them (they never apply by themselves). */
data class AddressChange(val oldServer: String, val newServer: String, val oldErp: String?, val newErp: String?)

/** What a refresh does with a fetched file. */
sealed interface RefreshOutcome {
    /** Older, the same, or not acceptable: the stored brand stays as it is. */
    data object Keep : RefreshOutcome

    /** Newer, with the same addresses: it takes the stored one's place. */
    data object Replace : RefreshOutcome

    /** Newer, with another server or ERP address: only offered, see [AddressChange]. */
    data class Hold(val change: AddressChange) : RefreshOutcome
}

/**
 * [heldIssued]: the `issued` of a change already held for the person. Nothing older than it is taken,
 * so a host that re-serves an older genuine file can neither re-point nor cancel the offer.
 */
fun refreshOutcome(stored: BrandPayload, storedBytes: ByteArray, fresh: Opened.Ok, heldIssued: Long? = null): RefreshOutcome = when {
    !acceptsRefresh(stored.issued, storedBytes, fresh) -> RefreshOutcome.Keep
    heldIssued != null && fresh.payload.issued <= heldIssued -> RefreshOutcome.Keep
    fresh.payloadBytes.contentEquals(storedBytes) -> RefreshOutcome.Keep
    addressChanged(stored, fresh.payload) ->
        RefreshOutcome.Hold(AddressChange(stored.server, fresh.payload.server, stored.erp, fresh.payload.erp))
    else -> RefreshOutcome.Replace
}
