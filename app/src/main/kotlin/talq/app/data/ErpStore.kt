package talq.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.erpDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "erp",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** A pairing with the organisation's ERP as it is kept on the phone. */
class StoredPairing(
    /** The ERP address the key was issued by. The key is only ever sent there. */
    val base: String,
    val apiKey: String,
    val displayName: String,
)

/**
 * The ERP link on disk, per signed-in account (`server|user`): the ERP address typed in the
 * generic build, and the pairing. The key is AES-256-GCM encrypted with a key of its own in
 * the Android Keystore, the same way as the account's app password
 * ([KeystoreCredentialStore]). Nothing of this is ever sent to the Nextcloud server.
 */
class ErpStore(context: Context) {
    private val store = context.applicationContext.erpDataStore

    /** The ERP address typed for [account], or empty. */
    suspend fun address(account: String): String = store.data.first()[addressKey(account)] ?: ""

    suspend fun setAddress(account: String, text: String) {
        store.edit { if (text.isEmpty()) it.remove(addressKey(account)) else it[addressKey(account)] = text }
    }

    /** Null when [account] is not paired, or its key can never be read again (then it is removed). */
    suspend fun pairing(account: String): StoredPairing? {
        val prefs = store.data.first()
        val sealed = prefs[sealedKey(account)] ?: return null
        val base = prefs[baseKey(account)] ?: return null
        val key = try {
            withContext(Dispatchers.Default) { decrypt(sealed) }
        } catch (e: UnreadableSecretException) {
            clearPairing(account)
            return null
        }
        return StoredPairing(base, key, prefs[nameKey(account)] ?: "")
    }

    suspend fun savePairing(account: String, pairing: StoredPairing) {
        val sealed = withContext(Dispatchers.Default) { encrypt(pairing.apiKey) }
        store.edit {
            it[sealedKey(account)] = sealed
            it[baseKey(account)] = pairing.base
            it[nameKey(account)] = pairing.displayName
        }
    }

    suspend fun clearPairing(account: String) {
        store.edit {
            it.remove(sealedKey(account))
            it.remove(baseKey(account))
            it.remove(nameKey(account))
        }
    }

    /** Sign-out: every pairing goes. The typed ERP address stays, it is no secret. */
    suspend fun clearAllPairings() {
        store.edit { prefs ->
            prefs.asMap().keys.filter { !it.name.startsWith(ADDRESS) }.forEach { prefs.remove(it) }
        }
    }

    private fun addressKey(account: String) = stringPreferencesKey(ADDRESS + account)
    private fun sealedKey(account: String) = stringPreferencesKey("key|$account")
    private fun baseKey(account: String) = stringPreferencesKey("keybase|$account")
    private fun nameKey(account: String) = stringPreferencesKey("name|$account")

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** base64(iv) ":" base64(ciphertext + tag). The Keystore picks the IV. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(data)
    }

    /** The stored blob can never be read again, as opposed to "not right now". */
    private class UnreadableSecretException(cause: Throwable) : Exception(cause)

    private fun decrypt(sealed: String): String {
        val (iv, data) = try {
            val parts = sealed.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            parts[0] to parts[1]
        } catch (e: RuntimeException) {
            throw UnreadableSecretException(e)
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw UnreadableSecretException(e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw UnreadableSecretException(e)
        } catch (e: UnrecoverableKeyException) {
            throw UnreadableSecretException(e)
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "talq.erp.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ADDRESS = "address|"
    }
}
