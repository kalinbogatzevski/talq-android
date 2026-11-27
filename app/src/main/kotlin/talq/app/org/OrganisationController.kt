package talq.app.org

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import talq.core.org.AddressChange
import talq.core.org.BrandPayload
import talq.core.org.CodeInput
import talq.core.org.Derived
import talq.core.org.Opened
import talq.core.org.RefreshOutcome
import talq.core.org.deriveKeys
import talq.core.org.openBrandFile
import talq.core.org.parseInviteCode
import talq.core.org.refreshOutcome
import talq.core.org.verifyEnvelope
import kotlin.random.Random

/** A brand file that checked out, shown on the card before anything is applied. */
class OrgCard internal constructor(val payload: BrandPayload, internal val keys: Derived, internal val envelope: ByteArray) {
    override fun toString(): String = "OrgCard(${payload.id})"
}

/** What looking up a code gave: the card, or one line to show. */
sealed interface LookUp {
    class Found(val card: OrgCard) : LookUp
    data class Refused(val line: String) : LookUp
}

/**
 * Organisation mode on the phone (`appContainer.organisation`; spec: "In the app"). Main thread for
 * the state; the slow parts (deriving, fetching, the Keystore, files) run on their own dispatchers.
 *
 * Rules:
 * - Nothing is applied before the person taps "Use this organisation" ([use]).
 * - One derivation at a time ([derive]): the caller cancels an older look-up before a newer one.
 * - The code, the typed text, the file id and the fetch address are never logged or stored.
 * - At start the stored envelope is verified again against the key list of this version; one no
 *   key vouches for any more ends organisation mode. A Keystore that cannot open the stored key
 *   leaves the look as it is (from the preferences) and only skips this run's refresh.
 * - A refresh that brings other addresses is only held ([addressChange]); it never moves a session.
 */
class OrganisationController(context: Context, private val scope: CoroutineScope) {
    private val store = OrganisationStore(context)
    private val fetch by lazy { BrandFetch() }
    private val derive = Mutex()

    /**
     * One change of what is stored at a time (use, leave, a refresh's write, taking a held change).
     * [generation] moves with use and leave: a refresh that started before either writes nothing.
     */
    private val storeLock = Mutex()
    @Volatile private var generation = 0
    private var refreshBlocked = false

    private val _state = MutableStateFlow(store.quick())

    /** The organisation the phone is set up for, or null (the public app as it always was). */
    val state: StateFlow<Organisation?> = _state.asStateFlow()

    /** A code from a `talq://organisation` link, for the screen that offers it; never saved. */
    val pendingCode = MutableStateFlow<String?>(null)

    private val _addressChange = MutableStateFlow(store.pendingChange())

    /** A newer brand file with other addresses, waiting for the person (signed out) to take it. */
    val addressChange: StateFlow<AddressChange?> = _addressChange.asStateFlow()

    init {
        if (_state.value != null) scope.launch { checkAtStart() }
    }

    private suspend fun checkAtStart() {
        val verdict = withContext(Dispatchers.IO) {
            try {
                val keys = store.keys() ?: return@withContext Verdict.Drop
                val envelope = store.envelope() ?: return@withContext Verdict.Drop
                if (verifyEnvelope(envelope, keys.fileId) is Opened.Ok) Verdict.Keep else Verdict.Drop
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the organisation's key could not be read (${e::class.simpleName}); no refresh this run")
                Verdict.KeystoreBusy
            }
        }
        when (verdict) {
            Verdict.Keep -> Unit
            Verdict.KeystoreBusy -> refreshBlocked = true
            Verdict.Drop -> {
                Log.i(TAG, "the stored organisation no longer verifies; organisation mode ends")
                leave()
            }
        }
    }

    private enum class Verdict { Keep, Drop, KeystoreBusy }

    /** Looks up a typed or linked code. Cancellable between its steps; the derivation itself runs to its end. */
    suspend fun lookUp(input: String): LookUp {
        val symbols = when (val parsed = parseInviteCode(input)) {
            is CodeInput.Valid -> parsed.symbols
            else -> return LookUp.Refused(CHECK_CODE)
        }
        val keys = derive.withLock { withContext(Dispatchers.Default) { deriveKeys(symbols) } }
        val fetched = withContext(Dispatchers.IO) { fetch.get(keys.fileIdHex) }
        return when (fetched) {
            is BrandFetch.Fetched.Body -> when (val opened = withContext(Dispatchers.Default) { openBrandFile(fetched.bytes, keys) }) {
                is Opened.Ok -> LookUp.Found(OrgCard(opened.payload, keys, opened.envelope))
                Opened.Bad -> LookUp.Refused(CANT_CHECK)
            }
            BrandFetch.Fetched.NotFound -> LookUp.Refused(NO_SUCH)
            BrandFetch.Fetched.NoConnection -> LookUp.Refused(NO_CONNECTION)
            BrandFetch.Fetched.Bad, BrandFetch.Fetched.NotModified -> LookUp.Refused(CANT_CHECK)
        }
    }

    /**
     * The person tapped "Use this organisation" (or "Apply this organisation's look"). [onResult] says
     * whether it was stored (a busy Keystore or a full disk: false, and nothing changed).
     */
    fun use(card: OrgCard, onResult: (Boolean) -> Unit = {}) {
        generation++   // at the tap: a refresh already under way shows nothing after this
        scope.launch {
            val saved = storeLock.withLock {
                withContext(Dispatchers.IO) {
                try {
                    store.save(card.payload, card.envelope, card.keys)
                    store.nextRefreshAt = nextRefresh()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "the organisation could not be stored (${e::class.simpleName})")
                    false
                }
                }
            }
            if (saved) {
                refreshBlocked = false
                _addressChange.value = null
                _state.value = store.quick()
            }
            onResult(saved)
        }
    }

    /** "Use without an organisation": back to the public app at once; the files go off the main thread. */
    fun leave() {
        generation++   // at the tap: a refresh already under way shows nothing after this
        _addressChange.value = null
        _state.value = null
        scope.launch {
            storeLock.withLock {
                withContext(Dispatchers.IO) { store.clear() }
            }
        }
    }

    /**
     * Fetches the stored brand file again when it is due (every 2 to 5 days, at random), the app in
     * front and the network usable. A newer file with the same addresses replaces the stored one; one
     * with other addresses is held; anything else changes nothing.
     */
    suspend fun refreshIfDue(online: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        if (!online || refreshBlocked || _state.value == null) return
        // Not due yet, unless the date is further away than any refresh is set (a clock that was ahead).
        val next = store.nextRefreshAt
        if (nowMillis < next && next - nowMillis <= DAY_MS * 5) return
        val started = generation
        val outcome = storeLock.withLock {
            if (generation != started) return@withLock null
            withContext(Dispatchers.IO) {
            try {
                val keys = store.keys() ?: return@withContext null
                val storedEnvelope = store.envelope() ?: return@withContext null
                val stored = verifyEnvelope(storedEnvelope, keys.fileId) as? Opened.Ok ?: return@withContext null
                when (val fetched = fetch.get(keys.fileIdHex, store.etag)) {
                    is BrandFetch.Fetched.Body -> {
                        store.nextRefreshAt = nextRefresh(nowMillis)
                        val fresh = openBrandFile(fetched.bytes, keys) as? Opened.Ok ?: return@withContext null
                        val held = store.pendingEnvelope()?.let { verifyEnvelope(it, keys.fileId) as? Opened.Ok }?.payload?.issued
                        val result = refreshOutcome(stored.payload, stored.payloadBytes, fresh, heldIssued = held)
                        when (result) {
                            RefreshOutcome.Replace -> store.save(fresh.payload, fresh.envelope, null)
                            is RefreshOutcome.Hold -> store.hold(fresh.envelope, result.change)
                            RefreshOutcome.Keep -> Unit
                        }
                        store.etag = fetched.etag
                        result
                    }
                    BrandFetch.Fetched.NotModified, BrandFetch.Fetched.NotFound -> {
                        // A deleted file only stops new phones: this one keeps its brand.
                        store.nextRefreshAt = nextRefresh(nowMillis)
                        null
                    }
                    else -> null // no connection: try again at the next start in front
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the organisation could not be refreshed (${e::class.simpleName})")
                null
            }
            }
        }
        // Left or replaced meanwhile: what this refresh wrote is cleared or overwritten by that, and nothing is shown.
        if (generation != started) return
        when (outcome) {
            RefreshOutcome.Replace -> {
                _state.value = store.quick()
                _addressChange.value = store.pendingChange()
            }
            is RefreshOutcome.Hold -> _addressChange.value = outcome.change
            else -> Unit
        }
    }

    /** The person (signed out) took the held addresses: the held file becomes the organisation's. */
    fun acceptAddressChange() {
        scope.launch {
            val ok = storeLock.withLock { withContext(Dispatchers.IO) {
                try {
                    val keys = store.keys() ?: return@withContext false
                    val pending = store.pendingEnvelope() ?: return@withContext false
                    val opened = verifyEnvelope(pending, keys.fileId) as? Opened.Ok ?: return@withContext false
                    store.save(opened.payload, opened.envelope, null)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "the new addresses could not be taken (${e::class.simpleName})")
                    false
                }
            } }
            if (ok) {
                _addressChange.value = null
                _state.value = store.quick()
            }
        }
    }

    private fun nextRefresh(now: Long = System.currentTimeMillis()): Long =
        now + DAY_MS * 2 + Random.nextLong(DAY_MS * 3)

    companion object {
        private const val TAG = "TalqOrg"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        const val CHECK_CODE = "Check the code"
        const val NO_SUCH = "No organisation has this code"
        const val CANT_CHECK = "This code could not be checked"
        const val NO_CONNECTION = "No connection"
    }
}
