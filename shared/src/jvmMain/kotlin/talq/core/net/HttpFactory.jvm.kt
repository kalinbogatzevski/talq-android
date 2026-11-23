package talq.core.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

internal actual fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) { config() }

internal actual fun platformWebSocketHttpClient(pingMillis: Long, configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        engine { config { pingInterval(pingMillis, TimeUnit.MILLISECONDS) } }
        configure()
    }
