package talq.core.org

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.MessageDigest
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

// The raw-key constructor goes straight to Tink's pure-Java implementation: no JCE provider, no
// Conscrypt, so it behaves the same on every Android version from 26.
internal actual fun ed25519Verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean = try {
    Ed25519Verify(publicKey).verify(signature, message)
    true
} catch (e: Exception) {
    false
}
