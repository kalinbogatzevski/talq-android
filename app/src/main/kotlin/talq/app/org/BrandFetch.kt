package talq.app.org

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches brand files from the brand host (spec: "The fetch"). A client of its own: no redirects (a
 * redirect is "No connection", never followed), no cookies, a fixed User-Agent without the version,
 * at most [MAX_BYTES] read, the Content-Type ignored (GitHub Pages answers a missing file with HTML).
 * Blocking: call it off the main thread. Nothing here is logged: the address holds the file id.
 */
internal class BrandFetch {
    sealed interface Fetched {
        class Body(val bytes: ByteArray, val etag: String?) : Fetched
        data object NotModified : Fetched
        data object NotFound : Fetched
        data object NoConnection : Fetched
        data object Bad : Fetched
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun get(fileIdHex: String, etag: String? = null): Fetched {
        val request = Request.Builder()
            .url(BRAND_HOST + fileIdHex + ".bin")
            .header("User-Agent", "TalQ")
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val source = response.body.source()
                        // Ask for one byte more than allowed: a longer body is refused, not cut.
                        source.request(MAX_BYTES + 1L)
                        if (source.buffer.size > MAX_BYTES) Fetched.Bad
                        else Fetched.Body(source.buffer.readByteArray(), response.header("ETag"))
                    }
                    304 -> Fetched.NotModified
                    404 -> Fetched.NotFound
                    else -> Fetched.NoConnection // 429, 5xx, a redirect: the host, not the code
                }
            }
        } catch (e: IOException) {
            Fetched.NoConnection
        }
    }

    companion object {
        /** The brand host (spec, owner's choice): a project site on GitHub Pages. Moving it needs an app update. */
        const val BRAND_HOST = "https://kalinbogatzevski.github.io/talq-brands/v1/"
        const val MAX_BYTES = 512 * 1024
    }
}
