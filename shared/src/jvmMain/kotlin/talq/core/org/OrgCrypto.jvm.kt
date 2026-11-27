package talq.core.org

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

internal actual fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int, length: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, length * 8)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

internal actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
        doFinal(sealed)
    }
} catch (e: Exception) {
    null
}

internal actual fun ed25519Verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean = try {
    Ed25519Verify(publicKey).verify(signature, message)
    true
} catch (e: Exception) {
    false
}

// ------------------------------------------------------------------ the issuing side (JVM only)

/** AES-256-GCM sealing: ciphertext then the 128-bit tag. [nonce] must never repeat under one key. */
internal fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
        doFinal(plain)
    }

/** A new random Ed25519 private key seed (32 bytes). */
internal fun ed25519NewSeed(): ByteArray = ByteArray(32).also { SecureRandom.getInstanceStrong().nextBytes(it) }

/** The raw 32-byte public key of [seed]. */
internal fun ed25519PublicKey(seed: ByteArray): ByteArray = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey

/** The 64-byte Ed25519 signature of [message] under [seed]. */
internal fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray = Ed25519Sign(seed).sign(message)

/** [n] bytes from the system's strong random source. */
internal fun strongRandom(n: Int): ByteArray = ByteArray(n).also { SecureRandom.getInstanceStrong().nextBytes(it) }
