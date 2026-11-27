package talq.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/** The AES-256 key [alias] of the Android Keystore, made on first use. It never leaves the Keystore. */
internal fun keystoreKey(alias: String): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build(),
    )
    return generator.generateKey()
}

/** Bytes sealed with AES-256-GCM under the Keystore key [alias]. Not the main thread: the Keystore can take its time. */
internal class KeystoreSeal(private val alias: String) {
    /** The length of the IV, the IV (the Keystore picks it), the sealed bytes with their tag. */
    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(alias))
        val data = cipher.doFinal(plain)
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + data
    }

    /** Throws for anything [seal] did not make under this key. */
    fun open(sealed: ByteArray): ByteArray {
        val iv = sealed.copyOfRange(1, 1 + sealed[0])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(alias), GCMParameterSpec(128, iv))
        return cipher.doFinal(sealed, 1 + iv.size, sealed.size - 1 - iv.size)
    }
}
