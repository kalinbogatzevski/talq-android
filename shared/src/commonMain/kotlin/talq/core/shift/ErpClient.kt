package talq.core.shift

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import talq.core.TalqCore
import talq.core.net.TalkJson
import talq.core.net.asObject
import talq.core.net.int
import talq.core.net.obj
import talq.core.net.platformHttpClient
import talq.core.net.str

/**
 * The address of the organisation's ERP, checked: `https` (plain http only to this machine
 * itself), a host, no user part. Only an [ErpBase] can be asked anything, so an address that
 * fails the check sends nothing.
 */
class ErpBase private constructor(
    /** The address as given, without trailing slashes. It may carry a path (a locale prefix, say). */
    val url: String,
    /** Lower case. */
    val host: String,
    /** `scheme://host[:port]`, no path. */
    val origin: String,
    /** The port in force (443 when an https address names none). */
    val port: Int,
) {
    override fun equals(other: Any?): Boolean = other is ErpBase && other.url == url
    override fun hashCode(): Int = url.hashCode()
    override fun toString(): String = url

    companion object {
        fun parse(text: String): ErpBase? {
            val trimmed = text.trim().trimEnd('/')
            val parts = splitUrl(trimmed) ?: return null
            if (parts.scheme != "https" && !(parts.scheme == "http" && isLoopback(parts.host))) return null
            return ErpBase(trimmed, parts.host, parts.origin, parts.port)
        }
    }
}

/** The approval page: an https address on the ERP's own host and port. */
data class ApproveTarget(val url: String, val host: String)

/**
 * The approval page the ERP offered, as an absolute `https` address. A relative one is joined
 * onto the ERP's scheme and host. Null when it may not be opened: not https, or on another
 * host or port than the ERP's own (the person would sign in to their ERP account there).
 */
fun approveTarget(base: ErpBase, approveUrl: String): ApproveTarget? {
    val given = approveUrl.trim()
    if (given.isEmpty() || given.any { it.code < 0x20 || it == ' ' || it == '\\' }) return null
    val absolute = when {
        given.startsWith("//") -> "https:$given"
        given.startsWith("/") -> base.origin + given
        SCHEME.matchesAt(given, 0) -> given
        else -> base.origin + "/" + given
    }
    val parts = splitUrl(absolute) ?: return null
    if (parts.scheme != "https") return null
    if (parts.host != base.host || parts.port != base.port) return null
    return ApproveTarget(absolute, parts.host)
}

private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:")

private class UrlParts(val scheme: String, val host: String, val origin: String, val port: Int)

/** Scheme, host and origin of an absolute http(s) address; null for anything else. */
private fun splitUrl(text: String): UrlParts? {
    val scheme = when {
        text.startsWith("https://", ignoreCase = true) -> "https"
        text.startsWith("http://", ignoreCase = true) -> "http"
        else -> return null
    }
    val authority = text.substring(scheme.length + 3).substringBefore('/').substringBefore('?').substringBefore('#')
    // A user part ("https://known-host@other-host/") is how one host is dressed up as another.
    if (authority.isEmpty() || '@' in authority) return null
    val url = try {
        Url(text)
    } catch (e: Exception) {
        return null
    }
    val host = url.host.lowercase()
    if (host.isEmpty() || url.protocol.name != scheme) return null
    return UrlParts(scheme, host, "$scheme://${authority.lowercase()}", url.port)
}

private fun isLoopback(host: String): Boolean =
    host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

/** The answer to `pair/start`. */
data class PairOffer(val pairToken: String, val approveUrl: String, val pollIntervalSeconds: Int) {
    /** Never the token: this may end up in a log. */
    override fun toString(): String = "PairOffer(pollIntervalSeconds=$pollIntervalSeconds)"
}

sealed interface PairStartResult {
    data class Offered(val offer: PairOffer) : PairStartResult
    data class Failed(val reason: String) : PairStartResult
}

sealed interface PairPollResult {
    /** 202: the person has not answered in the browser yet. */
    data object Waiting : PairPollResult

    class Paired(val apiKey: String, val displayName: String) : PairPollResult

    /** 410 or any other refusal: declined or expired. */
    data class Gone(val reason: String) : PairPollResult

    /**
     * The ERP could not be reached this time (no network while the browser is in front, a
     * timeout). Not an answer: asking again is right.
     */
    data class NoAnswer(val reason: String) : PairPollResult
}

/** One colleague in the ERP's answer, already made safe to show. */
data class ShiftAnswer(val state: ShiftState, val label: String)

sealed interface ShiftStatusResult {
    /** Colleagues the ERP left out are simply not in [statuses]. */
    data class Ok(val statuses: Map<String, ShiftAnswer>) : ShiftStatusResult

    /** 401 / 403: the ERP no longer accepts the key. */
    data object Rejected : ShiftStatusResult

    data class Failed(val reason: String) : ShiftStatusResult
}

/** An HTTP client for the ERP only: nothing of the Nextcloud account is in it, no redirect is followed. */
fun newErpHttpClient(userAgent: String = TalqCore.userAgent): HttpClient = platformHttpClient {
    expectSuccess = false
    followRedirects = false
    install(UserAgent) { agent = userAgent }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}

/**
 * The three requests the app makes to the organisation's ERP: pairing (start, poll) and the
 * shift lookup. It has its OWN HTTP client: the Nextcloud account never travels here and the
 * ERP key never travels to Nextcloud. No `Authorization` header is ever set (some deployments
 * strip it before the application sees it); the key goes in `X-API-Key`.
 *
 * Addresses are the base plus a path, glued as text: the base may carry a path of its own and
 * URL resolution would drop it. A redirect is an error (it means the base is wrong, and
 * following it would turn the POST into a GET). Reasons never contain the key or the pair token.
 */
class ErpClient(private val http: HttpClient = newErpHttpClient()) {
    fun close() = http.close()

    private class Answer(val status: Int, val data: JsonObject?)

    private class ErpFailure(val reason: String, val redirect: Boolean = false) : Exception(reason)

    /** POST [body] to [base] + [path]. Throws [ErpFailure] when there is no answer or it is a redirect. */
    private suspend fun post(base: ErpBase, path: String, body: JsonObject, apiKey: String? = null): Answer {
        val response = try {
            http.post(base.url + path) {
                header("Accept", "application/json")
                if (apiKey != null) header("X-API-Key", apiKey)
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ErpFailure("no answer (${e::class.simpleName})")
        }
        val status = response.status.value
        if (status in 300..399) throw ErpFailure("the ERP address redirects (HTTP $status); check the address", redirect = true)
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ErpFailure("no answer (${e::class.simpleName})")
        }
        val root = try {
            if (text.isBlank()) null else TalkJson.parseToJsonElement(text).asObject()
        } catch (e: Exception) {
            null
        }
        // Answers are wrapped in success / data / meta: read `data` when it is there.
        return Answer(status, if (root != null && "data" in root) root.obj("data") else root)
    }

    /** Ask the ERP for a pairing. [device] is the name the person sees on the approval page. */
    suspend fun pairStart(base: ErpBase, device: String): PairStartResult {
        val answer = try {
            post(base, "/api/v1/cti/pair/start", buildJsonObject { put("device", device) })
        } catch (e: ErpFailure) {
            return PairStartResult.Failed(e.reason)
        }
        if (answer.status !in 200..299) return PairStartResult.Failed("HTTP ${answer.status}")
        val token = answer.data?.str("pair_token").orEmpty()
        val approve = answer.data?.str("approve_url").orEmpty()
        if (token.isEmpty() || approve.isEmpty()) return PairStartResult.Failed("the ERP did not offer a pairing")
        val interval = answer.data?.int("poll_interval") ?: 0
        return PairStartResult.Offered(PairOffer(token, approve, if (interval > 0) interval else DEFAULT_POLL_SECONDS))
    }

    /** Has the person approved yet? */
    suspend fun pairPoll(base: ErpBase, pairToken: String): PairPollResult {
        val answer = try {
            post(base, "/api/v1/cti/pair/poll", buildJsonObject { put("pair_token", pairToken) })
        } catch (e: ErpFailure) {
            return if (e.redirect) PairPollResult.Gone(e.reason) else PairPollResult.NoAnswer(e.reason)
        }
        if (answer.status == 202) return PairPollResult.Waiting
        if (answer.status != 200) return PairPollResult.Gone("HTTP ${answer.status}")
        val key = answer.data?.str("api_key").orEmpty()
        if (key.isEmpty()) return PairPollResult.Gone("the ERP did not return a key")
        return PairPollResult.Paired(key, answer.data?.str("display_name").orEmpty())
    }

    /** Shift state of [userIds] (Nextcloud user ids; at most [MAX_SHIFT_BATCH] are sent). */
    suspend fun shiftStatus(base: ErpBase, apiKey: String, userIds: List<String>): ShiftStatusResult {
        val batch = buildShiftBatch(userIds)
        if (apiKey.isEmpty() || batch.isEmpty()) return ShiftStatusResult.Ok(emptyMap())
        val answer = try {
            post(
                base, "/api/v1/hr/shift-status",
                buildJsonObject { put("nc_usernames", JsonArray(batch.map { JsonPrimitive(it) })) },
                apiKey = apiKey,
            )
        } catch (e: ErpFailure) {
            return ShiftStatusResult.Failed(e.reason)
        }
        if (answer.status == 401 || answer.status == 403) return ShiftStatusResult.Rejected
        if (answer.status !in 200..299) return ShiftStatusResult.Failed("HTTP ${answer.status}")
        val statuses = answer.data?.obj("statuses") ?: return ShiftStatusResult.Ok(emptyMap())
        val out = HashMap<String, ShiftAnswer>()
        for (id in batch) {
            val o = statuses.obj(id) ?: continue
            val state = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            out[id] = ShiftAnswer(ShiftState.fromWire(state), sanitizeShiftLabel(o.str("label")))
        }
        return ShiftStatusResult.Ok(out)
    }

    companion object {
        const val DEFAULT_POLL_SECONDS = 2
    }
}
