package talq.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import talq.app.AppContainer
import talq.app.Brand
import talq.app.CredentialStorageException
import talq.core.TalkClient
import talq.core.TalkError
import talq.core.auth.LoginFlow

enum class LoginPhase {
    /** Waiting for the user to type an address. */
    Idle,

    /** Asking the server to start a sign-in. */
    Contacting,

    /** The browser is open; polling until the user finishes there. */
    WaitingForBrowser,

    /** The server handed over an app password; opening the session. */
    Finishing,
}

data class LoginUiState(
    val serverUrl: String = "",
    val phase: LoginPhase = LoginPhase.Idle,
    val error: String? = null,
    /** Set when the sign-in page should be opened; the screen opens it and calls `browserOpened`. */
    val browserUrl: String? = null,
) {
    val busy: Boolean get() = phase != LoginPhase.Idle
}

/** Login Flow v2 from the phone's side: start, open the browser, poll, open the session. */
class LoginViewModel(private val container: AppContainer, private val appName: String) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var loginUrl: String? = null

    init {
        // A branded build signs in to its organisation's server only: there is no address to type
        // or pick, and the remembered one is not read.
        if (Brand.isBranded) _state.update { it.copy(serverUrl = Brand.serverUrl) } else viewModelScope.launch {
            val last = try {
                container.credentialStore.lastServerUrl()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            _state.update { if (it.serverUrl.isEmpty()) it.copy(serverUrl = last) else it }
        }
    }

    fun onServerUrlChange(value: String) {
        if (Brand.isBranded) return
        _state.update { it.copy(serverUrl = value, error = null) }
    }

    fun browserOpened() {
        _state.update { it.copy(browserUrl = null) }
    }

    /** The phone has no browser that could show the sign-in page. */
    fun browserUnavailable() {
        cancel()
        _state.update { it.copy(error = "No browser app found to sign in with. Install one and try again.") }
    }

    /** The user closed the browser tab without finishing: show the sign-in page again. */
    fun reopenBrowser() {
        if (_state.value.phase == LoginPhase.WaitingForBrowser) _state.update { it.copy(browserUrl = loginUrl) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(phase = LoginPhase.Idle, browserUrl = null) }
    }

    fun login() {
        if (_state.value.busy) return
        val url = normalize(if (Brand.isBranded) Brand.serverUrl else _state.value.serverUrl)
        if (url == null) {
            val empty = _state.value.serverUrl.isBlank()
            _state.update {
                it.copy(
                    error = when {
                        Brand.isBranded -> "This app has no valid server address built in."
                        empty -> "Enter the address of your Nextcloud server."
                        else -> "That isn't a valid server address."
                    },
                )
            }
            return
        }
        _state.update { it.copy(serverUrl = url, phase = LoginPhase.Contacting, error = null) }
        job = viewModelScope.launch {
            if (!Brand.isBranded) {
                try {
                    container.credentialStore.rememberServerUrl(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Only a convenience for the next sign-in.
                }
            }
            // The server lists the app password under this name ("Devices & sessions").
            val flow = LoginFlow(url, deviceName = "$appName for Android")
            try {
                signIn(flow)
            } finally {
                flow.close()
            }
        }
    }

    private suspend fun signIn(flow: LoginFlow) {
        run {
            val session = try {
                flow.start()
            } catch (e: TalkError) {
                fail(startError(e))
                return
            }
            loginUrl = session.loginUrl
            _state.update { it.copy(phase = LoginPhase.WaitingForBrowser, browserUrl = session.loginUrl) }
            val answered = try {
                flow.await(session)
            } catch (e: TalkError) {
                fail(
                    if (e is TalkError.Http && e.status == 408) "The sign-in wasn't finished in time. Try again."
                    else "The sign-in was interrupted. Try again.",
                    browserOnTop = true,
                )
                return
            }
            // The server names its own canonical address in the answer, and the generic app
            // follows it. A branded build stays on its built-in server whatever the answer says.
            val credentials = if (Brand.isBranded) answered.copy(server = flow.serverUrl) else answered
            _state.update { it.copy(phase = LoginPhase.Finishing, browserUrl = null) }
            try {
                container.signIn(credentials)
            } catch (e: TalkError) {
                fail(
                    when (e) {
                        is TalkError.NotFound -> "Signed in, but Talk isn't installed on this server."
                        is TalkError.Network -> "Signed in, but the server stopped answering. Try again."
                        else -> "Signed in, but the session couldn't be opened. Try again."
                    },
                    browserOnTop = true,
                )
                return
            } catch (e: CredentialStorageException) {
                fail("Signed in, but this phone couldn't store the account. Try again.", browserOnTop = true)
                return
            }
            // The browser tab is still on top of the app: come back to the front, which closes it.
            container.bringToFront()
            _state.update { it.copy(phase = LoginPhase.Idle) }
        }
    }

    private fun fail(message: String, browserOnTop: Boolean = false) {
        _state.update { it.copy(phase = LoginPhase.Idle, browserUrl = null, error = message) }
        // Don't leave the user in the browser wondering: come back so the message is seen.
        if (browserOnTop) container.bringToFront()
    }

    private fun startError(e: TalkError): String = if (Brand.isBranded) brandedStartError(e) else when (e) {
        is TalkError.Network -> when (e.cause) {
            is UnknownHostException -> "Can't find that server. Check the address and your connection."
            is SSLException -> "Couldn't make a secure connection to that server."
            else -> "Can't reach that server. Check the address and your connection."
        }
        is TalkError.Server -> "The server reported an error (${e.status}). Try again in a moment."
        // 404, 403, HTML instead of JSON, other statuses: something answers there, but not Nextcloud.
        else -> "That address doesn't look like a Nextcloud server."
    }

    /** The branded build has no address the user could check: say what is left to try. */
    private fun brandedStartError(e: TalkError): String = when (e) {
        is TalkError.Network -> when (e.cause) {
            is SSLException -> "Couldn't make a secure connection to the server."
            else -> "Can't reach the server. Check your connection."
        }
        is TalkError.Server -> "The server reported an error (${e.status}). Try again in a moment."
        else -> "The server can't start a sign-in right now. Try again later."
    }

    private companion object {
        /** "cloud.example.com " -> "https://cloud.example.com"; null when it can't be a server address. */
        fun normalize(input: String): String? {
            val typed = input.trim()
            if (typed.isEmpty() || typed.any { it.isWhitespace() }) return null
            val url = TalkClient.normalizeServerUrl(typed)
            val host = try {
                URI(url).host
            } catch (e: Exception) {
                null
            }
            return if (host.isNullOrEmpty()) null else url
        }
    }
}
