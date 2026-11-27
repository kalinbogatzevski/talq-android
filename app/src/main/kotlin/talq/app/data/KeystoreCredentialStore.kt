package talq.app.data

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import talq.core.model.CredentialStore
import talq.core.model.Credentials

// A preferences file that can no longer be parsed is replaced by an empty one (= signed out)
// instead of failing every read from then on.
private val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "account",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The signed-in account on disk. Server URL and login name are plain preferences; the app
 * password is AES-256-GCM encrypted with a key that never leaves the Android Keystore.
 * Also remembers the last server URL typed at login, which survives a logout.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val store = context.applicationContext.accountDataStore

    override suspend fun load(): Credentials? {
        val prefs = store.data.first()
        val server = prefs[SERVER] ?: return null
        val login = prefs[LOGIN] ?: return null
        val sealed = prefs[PASSWORD] ?: return null
        val password = try {
            withContext(Dispatchers.Default) { decrypt(sealed) }
        } catch (e: UnreadableSecretException) {
            // The Keystore key is gone or is another one (restored backup, wiped keystore):
            // the blob is useless for good. Any other failure (a busy Keystore, say) is thrown
            // on and leaves the stored account alone.
            clear()
            return null
        }
        return Credentials(server, login, password)
    }

    override suspend fun save(credentials: Credentials) {
        val sealed = withContext(Dispatchers.Default) { encrypt(credentials.appPassword) }
        store.edit {
            it[SERVER] = credentials.server
            it[LOGIN] = credentials.loginName
            it[PASSWORD] = sealed
            it[LAST_SERVER] = credentials.server
        }
    }

    override suspend fun clear() {
        store.edit {
            it.remove(SERVER)
            it.remove(LOGIN)
            it.remove(PASSWORD)
        }
    }

    /** The server URL to prefill on the login screen, or empty. */
    suspend fun lastServerUrl(): String = store.data.first()[LAST_SERVER] ?: ""

    suspend fun rememberServerUrl(url: String) {
        store.edit { it[LAST_SERVER] = url }
    }

    private fun key() = keystoreKey(KEY_ALIAS)

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
            // Encrypted with a key that no longer exists (key() made a new one).
            throw UnreadableSecretException(e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw UnreadableSecretException(e)
        } catch (e: UnrecoverableKeyException) {
            throw UnreadableSecretException(e)
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "talq.account.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val SERVER = stringPreferencesKey("server")
        val LOGIN = stringPreferencesKey("login_name")
        val PASSWORD = stringPreferencesKey("app_password_sealed")
        val LAST_SERVER = stringPreferencesKey("last_server")
    }
}
