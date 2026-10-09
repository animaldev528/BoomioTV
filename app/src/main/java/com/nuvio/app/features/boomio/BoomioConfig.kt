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
 * `OverlayEndpointDiscovery` records the discovered endpoint and server public key and rewrites
 * [serviceOrigin] from the discovery record, and `OverlayEnrollment.writeConfig()` writes back the
 * assigned local CIDR and server address.
 *
 * Every value the `BuildConfig` fields back is blank in a normal build, and a blank
 * [overlayServerAddress] is the single switch that keeps the whole overlay subsystem inert.
 * [serviceOrigin] is the exception: it has a real default, because a client that has never read a
 * record has nothing to discover a name *from*.
 */
object BoomioConfig {
    /**
     * The **service origin** — one name, and every boomio URL in this object is built from it.
     *
     * ⚠️ **One seam, not four setters with four call sites.** The four base URLs below are the same
     * host with four path prefixes (`/bsc`, `/bsf`, `/bss-iptv`, `/bsm`); assigning them
     * individually would let a discovery result land on three of them and miss the fourth, and the
     * miss would be silent — the seam's consumers no-op on a blank base rather than failing. The
     * setter re-derives all four from one value, so they cannot disagree.
     *
     * ⚠️ **The `BuildConfig` values below are a floor, not the source.** Discovery overwrites this
     * field from the discovery record's `svc=` field the moment a publication is read, which is
     * what makes the DuckDNS name drive the client's URLs rather than a compile-time constant — the
     * point of the whole v2 record. The four bases are rebuilt by the setter at that moment, so a
     * TV built with no `BOOMIO_*` entry in `local.properties` still works, and a TV built with the
     * old per-service hosts stops using them as soon as a record is read.
     *
     * ⚠️ **Assigning the initializer does not run the setter**, so the four bases are *not* rebuilt
     * at construction: a build whose `local.properties` names a different host keeps it until
     * discovery learns otherwise. That ordering is deliberate — it means this field can be
     * introduced without changing the behaviour of any existing build.
     */
    var serviceOrigin: String = DEFAULT_SERVICE_ORIGIN
        set(value) {
            val normalised = value.trim().trimEnd('/')
            // A blank assignment would strip every base URL to a bare path and disable the seams
            // in a way that looks like "no server configured" rather than like a bug. Refuse it.
            if (normalised.isBlank()) return
            field = normalised
            applyServiceOrigin(normalised)
        }

    /** Base URL of the boomio media plane (bsf), e.g. `https://bsf.example.com`. */
    var boomioBaseUrl: String = BuildConfig.BOOMIO_BASE_URL

    /** Base URL of the BSM rating service, e.g. `https://bsm.example.com`. */
    var bsmBaseUrl: String = BuildConfig.BSM_BASE_URL

    /**
     * Base URL of the bsc companion hub, e.g. `wss://bsc.example.com`. From `BOOMIO_COMPANION_URL`
     * in `local.properties`. Inert when blank.
     *
     * Under the collapse this is **the same host as [iptvBaseUrl]** and only the path segment
     * differs; before it, the two were distinct hosts. Nothing may be keyed off host inequality —
     * the services are told apart by prefix. Both are derived from [serviceOrigin] the moment
     * discovery lands, so the `BuildConfig` pair is a pre-discovery floor rather than a statement
     * that the hosts differ.
     */
    var companionBaseUrl: String = BuildConfig.BOOMIO_COMPANION_URL

    /**
     * Base URL of the bss-iptv live edge, e.g. `https://bss-iptv.example.com`. From
     * `BOOMIO_IPTV_URL` in `local.properties`. Shares a host with [companionBaseUrl] under the
     * collapse — see there.
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

    /**
     * Rebuilds the four base URLs from [origin], each with the path prefix the collapsed edge
     * serves it under.
     *
     * ⚠️ **The prefixes are not decoration.** Every boomio service is a site block behind one
     * Caddy, told apart by path rather than by host, so the prefix is part of the address. The
     * companion one is *also* the scheme conversion: the bridge is a WebSocket and Ktor refuses an
     * `https://` scheme for it, while [companionRestBaseUrl] converts back for REST — so storing
     * `wss://…/bsc` here is what keeps both halves working from one value.
     */
    private fun applyServiceOrigin(origin: String) {
        companionBaseUrl = origin.toWebSocketScheme() + "/bsc"
        iptvBaseUrl = "$origin/bss-iptv"
        boomioBaseUrl = "$origin/bsf"
        bsmBaseUrl = "$origin/bsm"
    }
}

/** REST (`https://`) variant of [BoomioConfig.companionBaseUrl] for the bsc companion API. */
val BoomioConfig.companionRestBaseUrl: String
    get() = companionBaseUrl.trimEnd('/')
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")

/**
 * The host every boomio URL falls back to when no publication has been read.
 *
 * Public DNS, and deliberately so: a client that has never reached this server has never read a
 * record, so there is nothing to discover a name *from*. The discovery record is what supplies the
 * real one, including on a deployment that renames its service origin.
 */
private const val DEFAULT_SERVICE_ORIGIN = "https://boomio.duckdns.org"

/** `https://…` → `wss://…`, and `http://…` → `ws://…`; anything else is passed through unchanged. */
private fun String.toWebSocketScheme(): String = when {
    startsWith("https://") -> "wss://" + removePrefix("https://")
    startsWith("http://") -> "ws://" + removePrefix("http://")
    else -> this
}
