package com.nuvio.app.core.overlay

import com.nuvio.app.core.network.ServerConfigurationRepository

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
    return configuredHost.equals(svcHost, ignoreCase = true)
}
