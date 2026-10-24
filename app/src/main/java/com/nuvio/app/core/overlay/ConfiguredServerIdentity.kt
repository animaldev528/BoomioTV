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

/**
 * The name whose `TXT` record carries **this device's** overlay tuple.
 *
 * ## Why this is not `BOOMIO_SERVICE_HOST`
 *
 * [BOOMIO_SERVICE_HOST] is a compile-time literal: it names the deployment the APK was
 * *built* against. Both consumers of the discovery record — the endpoint ladder's rung 2
 * ([OverlayEndpointDiscovery]) and the provisioning walk ([OverlayProvisioning]) — used to
 * resolve it unconditionally, so a device pointed at another server still read the builder's
 * box.
 *
 * Measured on the reference LAN, 2026-10-24: with a device provisioned against
 * `boomiopi.duckdns.org`, rung 2 resolved the compiled-in `boomio.duckdns.org` — the *other*
 * deployment — and the TCP gate passed there, so the ladder accepted it. The typed server had
 * rewritten only the HTTP plane, so sign-in and the catalogue worked while tunnel provisioning
 * and enrolment aimed at the wrong box, even though the configured server was publishing a
 * perfectly good tuple the whole time.
 *
 * ## The rule
 *
 * - A custom server configured → its host is the record. That is the server the user chose,
 *   and the one whose tuple this device must enrol to.
 * - No custom server → [BOOMIO_SERVICE_HOST]. A stock build has chosen nothing, so the build's
 *   deployment is the only answer it can have.
 *
 * ⚠️ **A backend configured as a literal is returned as a literal, deliberately.** A server
 * typed as a bare address has no record to read; the lookup then finds no `TXT` and the
 * caller's miss path handles it. Falling back to the build's name instead would be the
 * cross-deployment dial this function exists to prevent.
 *
 * ⚠️ **A path never survives.** The record is a DNS name, so only the authority of the
 * configured URL is used — `https://boomiopi.duckdns.org/auth/v1` reads
 * `boomiopi.duckdns.org`.
 */
internal fun overlayDiscoveryRecordName(): String {
    val configured = ServerConfigurationRepository.active.value
    if (!configured.isCustom) return BOOMIO_SERVICE_HOST
    return hostOf(configured.backendUrl) ?: BOOMIO_SERVICE_HOST
}
