package talq.core.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import talq.core.TalkClient
import talq.core.TalkError
import talq.core.TalqCore
import talq.core.model.Credentials
import talq.core.net.TalkJson
import talq.core.net.asObject
import talq.core.net.newTalkHttpClient
import talq.core.net.obj
import talq.core.net.str

/** Step one of Login Flow v2: open [loginUrl] in the browser, then poll with this object. */
data class LoginFlowSession(
    val loginUrl: String,
    val pollEndpoint: String,
    val pollToken: String,
)

/**
 * Nextcloud Login Flow v2: the user signs in in a browser, the app receives an app password.
 *
 * ```
 * val flow = LoginFlow("cloud.example.com")
 * val session = flow.start()            // open session.loginUrl in a browser tab
 * val credentials = flow.await(session) // suspends until the user finished (or 20 minutes passed)
 * ```
 */
class LoginFlow(
    serverUrl: String,
    /** Becomes the name of the app password in the user's "Devices & sessions" list. */
    deviceName: String = TalqCore.LOGIN_DEVICE_NAME,
    // The server names the app password after the User-Agent of this request.
    private val http: HttpClient = newTalkHttpClient(userAgent = deviceName),
) {
    val serverUrl: String = TalkClient.normalizeServerUrl(serverUrl)

    suspend fun start(): LoginFlowSession {
        val (status, text) = post("$serverUrl/index.php/login/v2", null)
        if (status !in 200..299) throw failure(status, text)
        val root = parse(text) ?: throw TalkError.Protocol("login flow: not JSON")
        val poll = root.obj("poll")
        val session = LoginFlowSession(
            loginUrl = root.str("login"),
            pollEndpoint = poll?.str("endpoint") ?: "",
            pollToken = poll?.str("token") ?: "",
        )
        if (session.loginUrl.isEmpty() || session.pollEndpoint.isEmpty() || session.pollToken.isEmpty()) {
            throw TalkError.Protocol("login flow: incomplete answer")
        }
        return session
    }

    /** One poll. Null while the user has not finished signing in yet. */
    suspend fun poll(session: LoginFlowSession): Credentials? {
        val (status, text) = post(session.pollEndpoint, session.pollToken)
        if (status == 404) return null
        if (status !in 200..299) throw failure(status, text)
        val root = parse(text) ?: throw TalkError.Protocol("login flow poll: not JSON")
        val credentials = Credentials(
            // The server tells us its canonical URL; trust that over what the user typed.
            server = TalkClient.normalizeServerUrl(root.str("server").ifEmpty { serverUrl }),
            loginName = root.str("loginName"),
            appPassword = root.str("appPassword"),
        )
        if (credentials.loginName.isEmpty() || credentials.appPassword.isEmpty()) {
            throw TalkError.Protocol("login flow poll: incomplete answer")
        }
        return credentials
    }

    /**
     * Poll until the user finished. Network hiccups are ignored (the phone is usually in the
     * browser at this point). Throws [TalkError.Http] 408 when [timeoutMillis] ran out: the
     * server's token is only valid for 20 minutes.
     */
    suspend fun await(
        session: LoginFlowSession,
        intervalMillis: Long = 2_000,
        timeoutMillis: Long = 20 * 60_000L,
    ): Credentials {
        var waited = 0L
        while (waited < timeoutMillis) {
            try {
                poll(session)?.let { return it }
            } catch (e: TalkError.Network) {
                // keep polling
            }
            delay(intervalMillis)
            waited += intervalMillis
        }
        throw TalkError.Http(408, "login was not completed in time")
    }

    /** Release the HTTP client. Call when the sign-in ended, whichever way. */
    fun close() {
        http.close()
    }

    private suspend fun post(url: String, token: String?): Pair<Int, String> = try {
        val response = http.post(url) {
            header("Accept", "application/json")
            if (token != null) setBody(FormDataContent(Parameters.build { append("token", token) }))
        }
        response.status.value to response.bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw TalkError.Network(e)
    }

    private fun parse(text: String) = try {
        TalkJson.parseToJsonElement(text).asObject()
    } catch (e: Exception) {
        null
    }

    private fun failure(status: Int, text: String): TalkError = when (status) {
        403 -> TalkError.Forbidden("login flow")
        404 -> TalkError.NotFound("this server has no Login Flow v2")
        in 500..599 -> TalkError.Server(status)
        else -> TalkError.Http(status, text.take(200))
    }
}
