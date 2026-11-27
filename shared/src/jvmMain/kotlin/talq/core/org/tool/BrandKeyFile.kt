package talq.core.org.tool

import talq.core.org.aesGcmOpen
import talq.core.org.aesGcmSeal
import talq.core.org.strongRandom
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The owner's brand signing key on disk, sealed with a passphrase:
 * `"TQK1"` + 16-byte salt + 12-byte nonce + AES-256-GCM(PBKDF2-HMAC-SHA256(passphrase, salt,
 * 600 000 rounds), the 32-byte Ed25519 seed), additional data `"TQK1"`. The file alone is useless
 * without the passphrase; the owner keeps a backup of both, apart.
 */
private const val MAGIC = "TQK1"
private const val ROUNDS = 600_000

private fun passKey(passphrase: CharArray, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(passphrase, salt, ROUNDS, 256)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

/** Writes [seed] sealed under [passphrase] to [path]; refuses to overwrite an existing key. */
internal fun writeKeyFile(path: Path, seed: ByteArray, passphrase: CharArray) {
    require(seed.size == 32)
    require(passphrase.size >= 12) { "the passphrase must have at least 12 characters" }
    check(!Files.exists(path)) { "a signing key is already there: $path" }
    Files.createDirectories(path.parent)
    val salt = strongRandom(16)
    val nonce = strongRandom(12)
    val key = passKey(passphrase, salt)
    val sealed = aesGcmSeal(key, nonce, MAGIC.encodeToByteArray(), seed)
    key.fill(0)
    Files.write(path, MAGIC.encodeToByteArray() + salt + nonce + sealed)
    restrictToOwner(path)
}

/** The seed in [path], or null when the passphrase is wrong or the file is not a key file. */
internal fun readKeyFile(path: Path, passphrase: CharArray): ByteArray? {
    val bytes = Files.readAllBytes(path)
    if (bytes.size != 4 + 16 + 12 + 32 + 16 || bytes.decodeToString(0, 4) != MAGIC) return null
    val key = passKey(passphrase, bytes.copyOfRange(4, 20))
    val seed = aesGcmOpen(key, bytes.copyOfRange(20, 32), MAGIC.encodeToByteArray(), bytes.copyOfRange(32, bytes.size))
    key.fill(0)
    return seed
}
