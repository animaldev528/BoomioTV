package com.nuvio.app.core.overlay

import com.nuvio.app.core.network.ServerAliases
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.features.boomio.BOOMIO_SERVICE_HOST
import com.nuvio.app.features.boomio.BoomioConfig

/**
 * Whether an advert read off the network may move the **configured** server's plane.
 *
 * ## What this is for
 *
 * Discovery is normally the authority: the app ships one compile-time service name, and the
 * advert/TXT record it reads is what supplies the real origin, the LAN/WAN literals and the
 * overlay key. That is correct for a build whose server is *the* deployment.
 *
 * It is wrong the moment the user has chosen a server by hand. Two servers on one LAN each
 * advertise an identical `_boomio-overlay._udp` service, and the advert carries no identity the
 * client can check against a *different* deployment's configuration — so a TV pointed at
 * `192.168.1.65` would have its `BoomioConfig.serviceOrigin` rewritten to whichever box answered
 * mDNS first (`applyServicePins`), and its service hosts pinned to that box's address
 * (`OverlayLocalDiscovery.pinCandidate`). Measured on the reference LAN with a second server:
 * sign-in and the whole catalogue/media plane ended up on different boxes.
 *
 * ## The rule
 *
 * - No custom server configured → unchanged behaviour; every advert may drive the app.
 * - A custom server configured → only an advert that **names the same host** may drive it
 *   (compared through the advert's `svc=` label, the one published identity). Anything else is
 *   ignored: no origin rewrite, no pin, no tunnel endpoint, no pairing target.
 *
 * ⚠️ **An advert with no `svc=` is refused while a custom server is active.** v1 adverts predate
 * the field, so they can be attributed to no deployment in particular; treating "unknown" as
 * "mine" is exactly the hijack this exists to prevent. A hand-configured server loses LAN pinning
 * in that case, which is the safe direction: the address the user typed still works, and a pin it
 * never needed is not applied.
 *
 * ⚠️ **A custom server configured as a bare IP is also refused a pin, and that is not a
 * regression.** `derivePinnableHosts` rejects RFC1918 literals anyway — there is nothing for a pin
 * to point at — so the only host set that could be pinned belongs to the *other* box.
 */
internal fun advertMatchesConfiguredServer(svcName: String?): Boolean {
    val configured = ServerConfigurationRepository.active.value
    if (!configured.isCustom) return true
    val configuredHost = hostOf(configured.backendUrl) ?: return false
    val svcHost = svcName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { hostOf(if (it.contains("://")) it else "https://$it") }
        ?: return false
    // ⚠️ **Compared through the alias table, not as strings.** A person may have typed the box's
    // private address (see `ServerAliases`), while the advert always names the deployment by its
    // published `svc=` — the same box under the other spelling. A string comparison would read
    // that as a foreign server and refuse the user's own advert.
    return ServerAliases.sameHost(configuredHost, svcHost)
}

/**
 * The name whose TXT tuple describes the server this client should talk to.
 *
 * **The compiled name is a bootstrap default, not this deployment's name.** With a server chosen
 * by hand, resolving [BOOMIO_SERVICE_HOST] asks DNS about somebody else's box and gets that box's
 * `lan=`/`wan=`/`ppk` back — which is how a TV told to use `192.168.1.65` still minted its pairing
 * code on the reference box: the code landed in the other deployment's Redis, so the Pi's TV page
 * never had it to accept.
 *
 * A bare literal cannot be resolved this way — there is no TXT record for an address — and
 * [ServerAliases] is what turns one into the name that does have a record.
 */
internal val provisioningRecordHost: String
    get() {
        val configured = ServerConfigurationRepository.active.value
        val host = configured
            .takeIf { it.isCustom }
            ?.backendUrl
            ?.let { hostOf(it) }
            ?.takeIf { it.isNotBlank() }
        return host?.let { ServerAliases.canonicalHost(it) } ?: BoomioConfig.serviceHostOrNull()
            ?: BOOMIO_SERVICE_HOST
    }

/**
 * The host of [BoomioConfig.serviceOrigin], when that origin is not the compiled floor.
 *
 * Covers the window where a server was applied to the service plane but the configuration store
 * has not been published yet (`NuvioApplication` publishes before the overlay starts, so this is
 * belt and braces rather than the normal path).
 */
private fun BoomioConfig.serviceHostOrNull(): String? =
    hostOf(serviceOrigin)?.takeIf { !ServerAliases.sameHost(it, BOOMIO_SERVICE_HOST) }

