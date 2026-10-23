package com.nuvio.app.core.network

/**
 * One deployment, two names: the private literal it answers on, and the name its edge serves.
 *
 * ## Why a table at all
 *
 * The edge issues one certificate per **named** site block, so `https://192.168.1.65` cannot
 * complete TLS: the ClientHello carries no SNI the server has a site for, and Caddy answers
 * `alert internal error` (measured 2026-10-23 against both boxes). A person who types the address
 * their router shows them therefore gets "could not reach the server" from a box that is two hops
 * away and healthy. The fix is not to trust an address-shaped certificate; it is to know which
 * name that address *is*, and dial the name.
 *
 * That is also routable at home: both DuckDNS names resolve to their private addresses on the
 * reference LAN (`getent hosts` on 2026-10-23), so the named form works on the LAN and off it.
 * The name is therefore the canonical form everywhere, and the literal is a spelling the entry
 * field accepts rather than a transport.
 *
 * ## Extending
 *
 * One `literal=name` pair per box, comma-separated. A host the table does not name is passed
 * through untouched — it may still work if it has a certificate of its own for that address.
 */
internal object ServerAliases {
    private const val TABLE =
        "192.168.1.65=boomiopi.duckdns.org," +
            "192.168.68.65=boomio.duckdns.org"

    private val canonicalByHost: Map<String, String> by lazy {
        buildMap {
            for (entry in TABLE.split(',')) {
                val literal = entry.substringBefore('=').trim().lowercase()
                val name = entry.substringAfter('=', "").trim().lowercase()
                if (literal.isEmpty() || name.isEmpty()) continue
                put(literal, name)
                put(name, name)
            }
        }
    }

    /** The canonical name for [host]; the host itself (lowercased) when the table does not know it. */
    fun canonicalHost(host: String): String {
        val normalised = host.trim().trimEnd('.').lowercase()
        return canonicalByHost[normalised] ?: normalised
    }

    /** Whether two hosts name the same deployment, through the table in either direction. */
    fun sameHost(a: String, b: String): Boolean =
        canonicalHost(a).equals(canonicalHost(b), ignoreCase = true)

    /**
     * Rewrites a person's typed address to the canonical name, keeping the shape they typed.
     *
     * `192.168.1.65` becomes `boomiopi.duckdns.org`; `https://192.168.68.65:8443/setup` becomes
     * `https://boomio.duckdns.org:8443/setup`. Anything not in the table — including a URL with
     * userinfo, IPv6, or a name the table does not list — is returned exactly as typed.
     */
    fun canonicalInput(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return trimmed
        val schemeEnd = trimmed.indexOf("://")
        val prefix = if (schemeEnd >= 0) trimmed.substring(0, schemeEnd + 3) else ""
        val rest = trimmed.substring(prefix.length)
        if ('@' in rest.substringBefore('/')) return trimmed
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val suffix = rest.substring(authorityEnd)
        val host = hostOfAuthority(authority) ?: return trimmed
        val canonical = canonicalHost(host)
        if (canonical == host.lowercase()) return trimmed
        return prefix + canonical + authority.substring(host.length) + suffix
    }

    private fun hostOfAuthority(authority: String): String? {
        if (authority.isEmpty()) return null
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            return if (end < 0) null else authority.substring(0, end + 1)
        }
        val colon = authority.indexOf(':')
        return if (colon < 0) authority else authority.substring(0, colon)
    }
}
