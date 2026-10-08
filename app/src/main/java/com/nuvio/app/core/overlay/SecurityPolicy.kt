package com.nuvio.app.core.overlay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The security policy bsm records on its Security page, as this client understands it.
 *
 * ⚠️ **Nothing here may import `android.*`.** The predicate built on this object sits on the routing
 * path a connection takes, and the file is tested in `androidHostTest` without Robolectric — a single
 * `android.util.Log` here would cost every test in the file a Robolectric runner. Persistence lives in
 * [SecurityPolicyStore]; the wire decode lives in [SecurityPolicyRefresh]'s api.
 *
 * ── The four knobs, and what `false` means ────────────────────────────────────
 * `directLanPlayback` / `directWanPlayback` are **permissions, not preferences**. `true` means "this
 * plane may still be dialled directly, exactly as the app does today"; `false` means "this plane's
 * traffic is forced through the WireGuard tunnel". They are consulted only ever to *forbid* a direct
 * route — the client never promotes a direct route over the tunnel because of this object, which is
 * what keeps the default (below) equal to today's behaviour.
 *
 * The two `mtlsEnforced*` flags are carried for completeness and are **not consumed here**: the edge
 * enforces mTLS, and the client's job is to *present* a certificate when it has one, which
 * `MtlsSsl.withClientCertificate` already does unconditionally. They are parsed and cached so a
 * later change that does consult them finds the value already travelling.
 *
 * ── The defaults are the server's, and that is deliberate ─────────────────────
 * These are `routes/security.js`'s `DEFAULT_POLICY`, key for key. The server documents them as the
 * direction that is *safe for existing clients* because the live edge already matches them: the LAN
 * plane answers directly, the WAN plane's deny-by-default answers `404`. A client that never hears
 * from the server therefore behaves as it does today at the level that is observable — the one place
 * the client is stricter than a literal reading of "today" is the tunnel-down fallback on the WAN
 * plane (see [mayDialDirectly]), and that direct dial answers `404` at today's edge anyway.
 */
internal data class SecurityPolicy(
    val directLanPlayback: Boolean = true,
    val directWanPlayback: Boolean = false,
    val mtlsEnforcedOnLan: Boolean = false,
    val mtlsEnforcedOnWan: Boolean = false,
) {

    /** Whether a direct dial on [plane] is permitted under this policy. */
    fun mayDialDirectly(plane: DirectPlane): Boolean = when (plane) {
        DirectPlane.LAN -> directLanPlayback
        DirectPlane.WAN -> directWanPlayback
    }

    companion object {
        /** The server's own defaults. See the class doc for why these and not "allow everything". */
        val DEFAULT = SecurityPolicy()
    }
}

/**
 * The two direct routes a policy can forbid.
 *
 * ⚠️ **A plane is a *way of reaching the server*, not an address family.** [LAN] is the direct dial
 * to the address mDNS published on the server's own network (the LAN pin); [WAN] is the direct dial
 * to the public edge over the system resolver. Neither names the tunnel, because the tunnel is never
 * a *direct* route and no policy can forbid it.
 */
internal enum class DirectPlane { LAN, WAN }

/**
 * The policy currently in force.
 *
 * ⚠️ **One `@Volatile` value, read live at every routing decision.** The routing seams are consulted
 * per connect and per route on OkHttp's own threads, and a policy that only reached a client on the
 * next process start would make the server's "loosen it and every client recovers" promise a lie.
 * There is no lock because a reader needs one consistent value and a writer replaces the whole
 * object — the same shape [OverlayPinRegistry]'s pin map uses, for the same reason.
 */
internal object SecurityPolicyState {

    @Volatile
    var current: SecurityPolicy = SecurityPolicy.DEFAULT
        private set

    /** Applies a policy that has been fetched (or read from the cache). Called by platform code. */
    fun apply(policy: SecurityPolicy) {
        current = policy
    }

    /** Tests only. Production always moves to another policy. */
    fun reset() {
        current = SecurityPolicy.DEFAULT
    }
}

/**
 * The single seam predicate: **may a dial on [plane] be made directly?**
 *
 * Every routing decision that is about to take a direct route consults this, and nothing else about
 * the policy. Keeping it to one function is the point: `directLanPlayback=false` and
 * `directWanPlayback=false` then have exactly one meaning each, and a new direct route added later
 * has one obvious place to ask.
 *
 * ⚠️ **This is a *permission*, and the answer `false` must never be read as "tear the tunnel down"
 * or "the server is unreachable".** The WireGuard tunnel's lifecycle is owned by `OverlaySession`
 * and `OverlayTunnel`, and nothing in this file may touch it — a toggled policy changes *routing*
 * only. See `SecurityPolicyRoutingTest`.
 */
internal fun mayDialDirectly(plane: DirectPlane): Boolean =
    SecurityPolicyState.current.mayDialDirectly(plane)

/**
 * The fallback rule for a **carried** host: may the relay reach it directly when the tunnel could
 * not take it?
 *
 * This is [mayDialDirectly] with one addition, and the addition is the whole point: **while the
 * tunnel is still climbing, the policy is not yet enforced.** Nothing is bypassed in that window,
 * because there is nothing to bypass — the direct dial is the exact route the app would have taken
 * with no overlay at all, so refusing it makes a cold launch *worse* than having no overlay: every
 * boomio host answers `502` until the tunnel converges. Measured on the demo Pi 2026-10-08 — the
 * first ~10 s of every launch logged
 * `Tunnel could not reach boomio.tracemonkey.org:443 and a direct dial is not permitted`.
 *
 * Once the tunnel is up it has proven it can carry, and the toggle becomes final. A tunnel that
 * comes up and *then* cannot reach a host is exactly the case `directWanPlayback=false` exists to
 * refuse, so the relay answers `502` rather than quietly putting boomio traffic on the public edge.
 *
 * ⚠️ **[tunnelIsUp] is passed in rather than read here.** Liveness is a property of the tunnel and
 * this file may not import `android.*`; the decisive reason is that a predicate reading it directly
 * could not be tested without a running Go device. [OverlayRelay] supplies it from
 * `TunnelState.Up`, the same signal [OverlayPinRegistry.ownTunnelCarriesTraffic] uses.
 *
 * ⚠️ **`TunnelState.Up` means the device came up, not that a handshake has completed** — see
 * [OverlayWgTunnel.up]. A dial landing in the seconds between the device coming up and the peer
 * answering is therefore still refused. That is deliberate: it is a window of one handshake, and
 * reaching it at all takes a tunnel that is genuinely broken, whereas the alternative — treating
 * "not yet proven" as a state a client can sit in — is a rule that keeps falling back forever and
 * never enforces the toggle.
 */
internal fun mayFallBackToDirect(plane: DirectPlane, tunnelIsUp: Boolean): Boolean =
    !tunnelIsUp || mayDialDirectly(plane)

private val policyJson = Json { ignoreUnknownKeys = true }

/**
 * Decodes a policy from a JSON object, or null when the object carries no policy at all.
 *
 * ⚠️ **Merged key-by-key over the defaults, mirroring the server's `readPolicy`.** A blob written by
 * an older server (fewer knobs) or one written by hand must not produce an undefined flag a caller
 * reads as `false`. The difference from the server is the `seen` latch: an object that carries none
 * of the four keys is *not a policy* and decodes to null, so a reply that merely happens to be a
 * JSON object cannot silently reset a device to the defaults.
 *
 * A key present but not a boolean is ignored (falls back to the default) rather than failing the
 * whole decode: the server validates its own writes, so a non-boolean here means a hand-edited blob,
 * and answering with the safe value for that one key is better than discarding the other three.
 */
internal fun securityPolicyFrom(element: JsonElement?): SecurityPolicy? {
    val obj = element as? JsonObject ?: return null
    var seen = false
    var policy = SecurityPolicy.DEFAULT

    fun read(key: String, apply: SecurityPolicy.(Boolean) -> SecurityPolicy) {
        val value = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: return
        seen = true
        policy = policy.apply(value)
    }

    read("directLanPlayback") { copy(directLanPlayback = it) }
    read("directWanPlayback") { copy(directWanPlayback = it) }
    read("mtlsEnforcedOnLan") { copy(mtlsEnforcedOnLan = it) }
    read("mtlsEnforcedOnWan") { copy(mtlsEnforcedOnWan = it) }

    return if (seen) policy else null
}

/** Decodes a policy from a JSON text, or null when it is not one. */
internal fun parseSecurityPolicy(text: String?): SecurityPolicy? {
    if (text.isNullOrBlank()) return null
    val element = runCatching { policyJson.parseToJsonElement(text) }.getOrNull() ?: return null
    return securityPolicyFrom(element)
}

/**
 * Decodes a policy **strictly**: all four keys must be present as booleans, or this answers null.
 *
 * ⚠️ **The opposite of [securityPolicyFrom] on purpose, and the difference is the caller.**
 * [securityPolicyFrom] decodes the local cache and older blobs, where a short object is a *value* to
 * merge over the defaults. This one decodes a live `200` from `GET /api/overlay/policy`, and the
 * server has already validated its own record before it answers (`lib/security-policy.js`'s
 * `accept`). So an object that is not exactly four booleans is not a policy with a hole in it — it is
 * a body that went wrong on the way (a proxy, a hand-edit, a version skew), and the honest reading of
 * that is "no answer". Applying a partial object here would silently reset a knob the admin set;
 * refusing leaves the cached policy in force, which is the direction that cannot loosen routing.
 */
internal fun strictSecurityPolicyFrom(obj: JsonObject): SecurityPolicy? {
    fun bool(key: String): Boolean? = (obj[key] as? JsonPrimitive)?.booleanOrNull
    return SecurityPolicy(
        directLanPlayback = bool("directLanPlayback") ?: return null,
        directWanPlayback = bool("directWanPlayback") ?: return null,
        mtlsEnforcedOnLan = bool("mtlsEnforcedOnLan") ?: return null,
        mtlsEnforcedOnWan = bool("mtlsEnforcedOnWan") ?: return null,
    )
}

/** The policy as a JSON object, for the persistent cache. */
internal fun SecurityPolicy.toJsonObject(): JsonObject = JsonObject(
    mapOf(
        "directLanPlayback" to JsonPrimitive(directLanPlayback),
        "directWanPlayback" to JsonPrimitive(directWanPlayback),
        "mtlsEnforcedOnLan" to JsonPrimitive(mtlsEnforcedOnLan),
        "mtlsEnforcedOnWan" to JsonPrimitive(mtlsEnforcedOnWan),
    ),
)
