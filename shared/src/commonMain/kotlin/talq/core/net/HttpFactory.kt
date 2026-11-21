package talq.core.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import talq.core.TalqCore

/** Platform engine (OkHttp on Android and on the JVM). */
internal expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * The one HTTP client configuration used everywhere. Non-2xx answers are NOT exceptions here:
 * [talq.core.TalkClient] maps status codes to [talq.core.TalkError] itself.
 */
fun newTalkHttpClient(userAgent: String = TalqCore.USER_AGENT): HttpClient = platformHttpClient {
    expectSuccess = false
    followRedirects = true
    install(UserAgent) { agent = userAgent }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
}
