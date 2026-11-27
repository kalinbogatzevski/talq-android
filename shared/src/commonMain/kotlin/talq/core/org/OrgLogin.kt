package talq.core.org

import talq.core.TalkClient

/**
 * Signing in on an organisation's fixed server (spec: "The fixed server is enforced"). The Login
 * Flow names its own addresses (the page the browser opens, the address the phone polls) and the
 * canonical server it answers with: in organisation mode all of them must stay on the fixed server,
 * or a server could send the person's password entry somewhere else.
 */

/** `https` + lower-case host + port (443 when none is written), or null when [url] is not https or has a user part. */
private fun httpsAuthority(url: String): Pair<String, Int>? {
    if (!url.startsWith("https://", ignoreCase = true)) return null
    val authority = url.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty() || '@' in authority || '\\' in authority) return null
    val host = authority.substringBefore(':').lowercase()
    val port = if (':' in authority) authority.substringAfter(':').toIntOrNull() ?: return null else 443
    if (host.isEmpty() || port !in 1..65535) return null
    return host to port
}

/** Whether a Login Flow's [loginUrl] and [pollEndpoint] are both https on [fixed]'s host and port. */
fun orgFlowAllowed(fixed: String, loginUrl: String, pollEndpoint: String): Boolean {
    val want = httpsAuthority(fixed) ?: return false
    return httpsAuthority(loginUrl) == want && httpsAuthority(pollEndpoint) == want
}

/** Whether two server addresses name the same server: https, the same host and port, the same path. */
fun sameServer(a: String, b: String): Boolean {
    val na = TalkClient.normalizeServerUrl(a)
    val nb = TalkClient.normalizeServerUrl(b)
    val aa = httpsAuthority(na) ?: return false
    val ab = httpsAuthority(nb) ?: return false
    fun path(n: String) = n.substring(8).let { rest -> rest.substring(rest.indexOf('/').takeIf { it >= 0 } ?: rest.length) }
    return aa == ab && path(na) == path(nb)
}
