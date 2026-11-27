package talq.core.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.Cookie
import io.ktor.http.Url
import talq.core.TalqCore

/** Platform engine (OkHttp on Android and on the JVM). */
internal expect fun platformHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * The same engine for a long-lived WebSocket that pings every [pingMillis] (a missing pong
 * fails the socket). OkHttp takes the ping from its own configuration, not from Ktor's
 * WebSockets plugin.
 */
internal expect fun platformWebSocketHttpClient(pingMillis: Long, configure: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * [failure] (of a request that got no answer) came before anything could be sent: no address for the
 * host, or no connection to it. Only what is certain counts; a broken connection or a timeout after
 * the connection stood may have reached the server.
 */
internal expect fun neverLeftThePhone(failure: Throwable): Boolean

/**
 * The client for the call backend's signaling socket: WebSockets, a 30 s ping, a 10 s connect
 * timeout and no request timeout (the socket lives for the whole call and beyond). OkHttp drops
 * the socket when a pong is not back by the next ping; the server answers pings only between
 * the messages it handles, which can take up to its media-server timeout (25 s), so the ping
 * must not be shorter than 30 s.
 */
fun newHpbHttpClient(userAgent: String = TalqCore.userAgent): HttpClient = platformWebSocketHttpClient(30_000) {
    expectSuccess = false
    install(UserAgent) { agent = userAgent }
    install(WebSockets)
    install(HttpTimeout) { connectTimeoutMillis = 10_000 }
}

/**
 * The one HTTP client configuration used everywhere. Non-2xx answers are NOT exceptions here:
 * [talq.core.TalkClient] maps status codes to [talq.core.TalkError] itself.
 *
 * Cookies are kept in memory for the life of the client: Talk ties joining a call to the PHP
 * session that joined the conversation (`participants/active`), so both requests must carry the
 * same session cookie, or joining the call answers 404.
 */
fun newTalkHttpClient(userAgent: String = TalqCore.userAgent): HttpClient = platformHttpClient {
    expectSuccess = false
    followRedirects = true
    install(UserAgent) { agent = userAgent }
    install(HttpCookies) { storage = OneSessionCookies() }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
}

/**
 * The server's cookies, of ONE of its sessions. Opening an account sends two requests at once, both without
 * a cookie, and the server starts a session for each: the cookies of one belong together (its id, and the key
 * its data is sealed with), and those of two answers must not mix. Those of the answer to who the user is are
 * left out.
 */
private class OneSessionCookies(private val kept: CookiesStorage = AcceptAllCookiesStorage()) : CookiesStorage by kept {
    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        if (!requestUrl.encodedPath.endsWith("/cloud/user")) kept.addCookie(requestUrl, cookie)
    }
}
