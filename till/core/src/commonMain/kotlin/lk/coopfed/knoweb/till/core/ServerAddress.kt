package lk.coopfed.knoweb.till.core

/**
 * The rule for the addresses a till talks to (TWK-02, decision D-1; doc 32 section 9, "TLS 1.2 or
 * later to the edge"): https, except on this PC itself (localhost, 127.0.0.1, [::1], a name under
 * .localhost), where the local stack and the tests run without a certificate.
 */
object ServerAddress {

    /** The host of [url], lower case, with the brackets of an IPv6 literal; null when there is none. */
    fun host(url: String): String? {
        val rest = url.trim().substringAfter("://", missingDelimiterValue = "")
        if (rest.isEmpty()) return null
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.lowercase().takeIf { it.isNotEmpty() }
    }

    fun scheme(url: String): String = url.trim().substringBefore("://", missingDelimiterValue = "").lowercase()

    fun isLocal(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host.endsWith(".localhost")

    /**
     * Refuses [url] unless it is https, or http to this PC.
     *
     * @param what how the cashier knows the address, for the message
     */
    fun requireTrusted(url: String, what: String) {
        val host = host(url) ?: throw TillRefusal("$what \"$url\" is not an address (https://...)")
        when (scheme(url)) {
            "https" -> Unit
            "http" -> if (!isLocal(host)) {
                throw TillRefusal("$what must start with https:// (plain http is refused except on this PC): $url")
            }
            else -> throw TillRefusal("$what must start with https://: $url")
        }
    }
}
