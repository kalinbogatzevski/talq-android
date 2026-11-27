package talq.core.org

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

// The issuing side of the brand file format (BrandFile.kt), for the owner's tool and the offline
// checks only: the phone never writes a brand file.

/**
 * The payload's bytes, padded to exactly [PAYLOAD_SIZE] with a trailing `"pad"` of zeros, so every
 * brand's file has the same size. Throws when the payload is too large to fit (a logo too big).
 */
internal fun encodePayload(p: BrandPayload): ByteArray {
    val json = buildJsonObject {
        put("v", 1)
        put("id", p.id)
        put("issued", p.issued)
        put("name", p.name)
        p.welcome?.let { put("welcome", it) }
        put("server", p.server)
        p.erp?.let { put("erp", it) }
        put("accent", "#" + p.accent.toString(16).padStart(6, '0').uppercase())
        p.theme?.let { put("theme", it) }
        put("logo", Base64.Default.encode(p.logoPng))
    }.toString()
    val bytes = json.encodeToByteArray()
    val padding = PAYLOAD_SIZE - bytes.size - ""","pad":""""".length
    require(padding >= 0) { "the brand does not fit (${bytes.size} bytes before padding)" }
    val padded = json.dropLast(1) + ""","pad":"""" + "0".repeat(padding) + "\"}"
    return padded.encodeToByteArray().also { check(it.size == PAYLOAD_SIZE) }
}

/**
 * A brand file for the code behind [derived], signed with [seed]. [nonce] (12 bytes) must be fresh
 * for every file written under the same code: the tool draws it from a strong random source.
 */
internal fun writeBrandFile(payload: BrandPayload, derived: Derived, seed: ByteArray, nonce: ByteArray): ByteArray {
    require(nonce.size == 12)
    val payloadBytes = encodePayload(payload)
    val keyId = keyIdOf(ed25519PublicKey(seed))
    val sig = ed25519Sign(seed, signedMessage(keyId, derived.fileId, payloadBytes))
    val envelope = """{"v":1,"key":"$keyId","payload":"${Base64.Default.encode(payloadBytes)}","sig":"${Base64.Default.encode(sig)}"}"""
        .encodeToByteArray()
    check(envelope.size == ENVELOPE_SIZE)
    val sealed = aesGcmSeal(derived.aesKey, nonce, brandAad(derived.fileId), envelope)
    return (BRAND_MAGIC.encodeToByteArray() + nonce + sealed).also { check(it.size == BRAND_FILE_SIZE) }
}
