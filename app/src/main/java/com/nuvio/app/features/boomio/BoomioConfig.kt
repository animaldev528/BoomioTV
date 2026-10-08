package com.nuvio.app.features.boomio

import com.nuvio.tv.BuildConfig

/**
 * TV compat layer — **not** part of the overlay port.
 *
 * The overlay package was transplanted from the mobile fork byte-for-byte, and it reads its
 * configuration from `com.nuvio.app.features.boomio.BoomioConfig`. The TV has no such object, so
 * this file re-declares the subset the overlay actually uses and backs it with the TV's own
 * `BuildConfig` fields.
 *
 * It exists only to keep the overlay files byte-identical to mobile's — a rename mixed into a port
 * makes the port unreviewable. Delete this package when the overlay is deliberately renamed.
 *
 * These are mutable `var`s rather than constants because the overlay **writes** them at runtime:
 * `OverlayEndpointDiscovery` records the discovered endpoint and server public key, and
 * `OverlayEnrollment.writeConfig()` writes back the assigned local CIDR and server address.
 *
 * Every value is blank in a normal build, and a blank [overlayServerAddress] is the single switch
 * that keeps the whole overlay subsystem inert.
 */
object BoomioConfig {
    /** Base URL of the boomio media plane (bsf), e.g. `https://bsf.example.com`. */
    var boomioBaseUrl: String = BuildConfig.BOOMIO_BASE_URL

    /** Base URL of the BSM rating service, e.g. `https://bsm.example.com`. */
    var bsmBaseUrl: String = BuildConfig.BSM_BASE_URL

    /**
     * Base URL of the bsc companion hub, e.g. `wss://bsc.example.com`. From `BOOMIO_COMPANION_URL`
     * in `local.properties`. Inert when blank.
     */
    var companionBaseUrl: String = BuildConfig.BOOMIO_COMPANION_URL

    /**
     * Base URL of the bss-iptv live edge. A DIFFERENT host from [companionBaseUrl]: the channel
     * catalogue is served by the IPTV role edge, while the companion socket and the party REST
     * live on bsc. From `BOOMIO_IPTV_URL` in `local.properties`.
     */
    var iptvBaseUrl: String = BuildConfig.BOOMIO_IPTV_URL

    /**
     * The server's address on the WireGuard overlay, e.g. `10.77.0.1`. Blank disables the overlay
     * resolver entirely.
     *
     * ⚠️ **An address, not a URL and not a hostname.** The app never *dials* it: every request
     * keeps naming the configured FQDN and Caddy picks the site block from that name, so a bare
     * address has nothing to match and fails TLS. All this value does is tell the DNS seam what
     * those names should resolve to while the tunnel is up.
     *
     * **Learned, not configured.** Enrollment writes it from the assignment it is given, so a
     * build that bakes nothing here still works against any deployment. `BOOMIO_OVERLAY_ADDR` is
     * a fallback covering only the window before a device has ever enrolled.
     */
    var overlayServerAddress: String = BuildConfig.BOOMIO_OVERLAY_ADDR

    /**
     * **This device's own** address inside the overlay, CIDR form — `10.77.0.2/32`.
     *
     * ⚠️ The third overlay address, and the one most easily confused with the other two.
     * [overlayServerAddress] is where the app's traffic is *aimed*; [overlayEndpoint] is where the
     * tunnel's *UDP* goes; this is the address the device *holds* once the tunnel is up. It must
     * equal the `allowed-ips` the operator enrolled this client's public key with, or the tunnel
     * comes up, completes a handshake, and then silently drops every return packet.
     *
     * The baked default is a pre-enrollment fallback only — **enrollment is the source**, and on a
     * TV it matters: the phone already owns `10.77.0.2`, so a TV that never enrolls would claim
     * the phone's address.
     */
    var overlayLocalCidr: String = BuildConfig.BOOMIO_OVERLAY_LOCAL_CIDR

    /**
     * The WireGuard endpoint the tunnel dials, `host:port` — e.g. `192.168.68.65:51820` on the
     * LAN, `153.68.210.49:51820` off it. Blank disables the tunnel, which is the default.
     *
     * ⚠️ **Not the same as [overlayServerAddress].** That one is the address the app's *traffic*
     * takes on the overlay; this one is where the *tunnel's UDP* goes, in the clear.
     *
     * In the end state this is **discovered**, not configured — the ladder tries mDNS, then the
     * published `boomio-local` name, then asks the user.
     */
    var overlayEndpoint: String = BuildConfig.BOOMIO_OVERLAY_ENDPOINT

    /**
     * The **server's** WireGuard public key, base64. Public, not secret: the mDNS advert and the
     * DuckDNS TXT record publish the identical value as `pk=…`.
     */
    var overlayServerPubKey: String = BuildConfig.BOOMIO_OVERLAY_PUBKEY

    /**
     * The name the **server** gave this device, e.g. `device-pixel-7-pro-430f9ca3`. Blank until
     * a device has enrolled, and blank means "we have not learned it yet" — never a placeholder.
     *
     * ⚠️ **This is not a label, and it is not [overlayLocalCidr].** It is the *identity* half of an
     * enrollment: the server derives it from the device id (`peerNameFor` in `bsc/lib/overlay-store.js`)
     * and files everything it later learns about this device — its WireGuard peer record, its entry
     * in the edge's mTLS allow-list — under exactly this string. A certificate minted for any other
     * CN is refused `cn_mismatch` by `POST /api/overlay/cert` and by the channel alike, so a wrong
     * value here does not degrade: it fails closed, and the device never gets a certificate.
     *
     * ⚠️ **The server is the only source, and it must stay that way.** A name this app invented for
     * itself would be a name the allow-list has never heard of. It arrives on both enrollment
     * transports — `GET /api/overlay/enroll/status`'s `name`, and the provisioning channel's
     * `enroll.ready.name` — and [com.nuvio.app.core.overlay.OverlayEnrollment] writes it from
     * whichever one answered, alongside the address, so the two can never disagree.
     *
     * It is emphatically **not** [overlayServerAddress] (the server's overlay address) and not the
     * user's device name from Settings — the three coincide in neither shape nor origin.
     *
     * Blank-inert: `MtlsRegistrar.planCertificate` treats an unknown name as `Unavailable`, so a
     * device that has not enrolled registers nothing rather than minting for a name it guessed.
     *
     * No `BOOMIO_*` build-time default, unlike its siblings above: every other overlay value here
     * has a meaningful pre-enrollment fallback, and this one does not. There is no name a build
     * could bake that the server would recognise.
     */
    var overlayDeviceName: String = ""
}

/** REST (`https://`) variant of [BoomioConfig.companionBaseUrl] for the bsc companion API. */
val BoomioConfig.companionRestBaseUrl: String
    get() = companionBaseUrl.trimEnd('/')
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")
