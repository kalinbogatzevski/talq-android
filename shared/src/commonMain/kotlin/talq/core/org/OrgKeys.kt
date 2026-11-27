package talq.core.org

/**
 * From a code to its brand file's name and key (format v1; the spec's "From code to file").
 *
 * `K = PBKDF2-HMAC-SHA256(16 symbols, "talq-brand-v1", 200 000 rounds, 32 bytes)`, one chain so the
 * phone and an attacker pay the same. Then `file id = HMAC(K, "talq-brand-v1 id")[0..16)` and
 * `AES key = HMAC(K, "talq-brand-v1 key")`. With 80 random bits a search over the public files
 * stays out of reach. The count and the salt belong to v1: changing either means new codes for
 * everyone.
 */
const val ORG_ITERATIONS: Int = 200_000
const val ORG_SALT: String = "talq-brand-v1"

/** A code's brand file id (16 bytes) and the AES-256 key of its content. Never logged. */
class Derived(val fileId: ByteArray, val aesKey: ByteArray) {
    init {
        require(fileId.size == 16 && aesKey.size == 32)
    }

    /** The file's name on the host, without `.bin`. */
    val fileIdHex: String get() = fileId.toHex()

    override fun toString(): String = "Derived(…)"
}

/** Derives a code's file id and key from its 16 canonical [symbols] (slow on purpose: off the main thread). */
fun deriveKeys(symbols: String): Derived {
    require(symbols.length == CODE_SYMBOLS && symbols.all { it in CROCKFORD }) { "not a canonical code" }
    val k = pbkdf2Sha256(symbols, ORG_SALT.encodeToByteArray(), ORG_ITERATIONS, 32)
    val fileId = hmacSha256(k, "$ORG_SALT id".encodeToByteArray()).copyOf(16)
    val aesKey = hmacSha256(k, "$ORG_SALT key".encodeToByteArray())
    k.fill(0)
    return Derived(fileId, aesKey)
}

/** A public key's id: the first 4 bytes of its SHA-256, as 8 lowercase hex characters. */
fun keyIdOf(publicKey: ByteArray): String = sha256(publicKey).copyOf(4).toHex()

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(digits[v ushr 4]).append(digits[v and 15])
    }
    return out.toString()
}

/** Lowercase or uppercase hex to bytes; null when it is not hex of an even length. */
internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = this[2 * i].digitToIntOrNull(16) ?: return null
        val lo = this[2 * i + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
