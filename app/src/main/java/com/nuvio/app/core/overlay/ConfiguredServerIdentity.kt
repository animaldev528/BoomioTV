package com.nuvio.app.core.overlay

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.features.boomio.BOOMIO_SERVICE_HOST

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
 * - A custom server configured → only an advert that **names the same host** may drive the app
 *   (compared through the advert's `svc=` label, the one published identity). Anything else is
 *   ignored: no origin rewrite, no pin, no tunnel endpoint, no pairing target.
 * - No custom server, **but the build names a deployment** (`BOOMIO_SERVICE_HOST`) → the same
 *   comparison runs against that name. This is the common case on a LAN with two boomio boxes:
 *   without it, a Firestick built for the Pi accepted the reference box's advert a few seconds
 *   after reading the Pi's own record, because "no custom server" was read as "trust anything".
 *   Measured 2026-11-08: `Endpoint accepted from MDNS: 192.168.68.65:51820` on a build whose
 *   baked name was `boomiopi.duckdns.org`.
 * - No custom server and **no build name** → unchanged behaviour; every advert may drive the app,
 *   because there is no identity to attribute one to.
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
    val configuredHost = if (configured.isCustom) {
        hostOf(configured.backendUrl) ?: return false
    } else {
        // A build that names a deployment is just as much an identity as a hand-picked server,
        // and the LAN may carry adverts from both boxes at once.
        BOOMIO_SERVICE_HOST.trim().takeIf { it.isNotBlank() } ?: return true
    }
    val svcHost = svcName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { hostOf(if (it.contains("://")) it else "https://$it") }
        ?: return false
    return configuredHost.equals(svcHost, ignoreCase = true)
}
