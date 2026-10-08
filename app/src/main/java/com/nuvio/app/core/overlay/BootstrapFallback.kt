package com.nuvio.app.core.overlay

/**
 * The client's "just use plain HTTPS" bootstrap choice.
 *
 * The owner's requirement: *"client bootstrap should optionally fall through to just plain https if
 * enrollment is not possible or desired."* Two different reasons, one outcome — the client reaches
 * the server over plain HTTPS to the public edge instead of waiting on an overlay it will not get.
 *
 * ── Where the two reasons live ────────────────────────────────────────────────
 * * **"not desired"** is [plainHttpsPreferred], a client-side setting. It is deliberately a plain
 *   `var` rather than a settings-UI entry: there is no natural home for a per-install overlay
 *   preference in the existing Settings screens, and inventing one was outside this change. A
 *   follow-up can bind a toggle to it; the routing decision below does not depend on how the value
 *   is produced.
 * * **"not possible"** is *not* stored here — it is observed at the call site, where the overlay
 *   transport either returns an api or does not. See `OverlayEnrollment.apiOrNull`, which passes
 *   `overlayAvailable = false` when the provisioning channel is not this network's transport.
 *
 * ⚠️ **The default is "try the overlay, fall back on failure" and that is the point.** Plain-HTTPS
 * bootstrap is the safety net, not the preferred route: a build with this untouched behaves exactly
 * as today (channel first, then the companion base), and only a device that genuinely cannot reach
 * the channel, or has opted out, takes the direct edge. Nothing here makes the client *less* able to
 * reach the server — the fallback is what it reaches for when the overlay cannot answer.
 *
 * ⚠️ **The "public edge" is the companion base as the system resolver sees it.** There is no separate
 * public-edge URL in this client, and the enrollment client installs no overlay pin
 * (`OverlayEnrollment.enrollmentHttpClient` sets no `Dns`), so off-LAN the base already resolves to
 * the public address. That is what makes this fallback reach the public edge rather than the LAN
 * address a pin would have substituted. Flagged as a guess: a deployment that *does* want a distinct
 * public-edge hostname would need a new `BoomioConfig` field.
 */
internal object BootstrapFallback {

    /** Whether the plain-HTTPS bootstrap is permitted at all. Turning it off is the only way to
     *  return to "the overlay or nothing", which is what the pre-existing behaviour was. */
    @Volatile
    var enabled: Boolean = true

    /** The client-side "enrollment is not desired" switch. Default false = enrollment is desired. */
    @Volatile
    var plainHttpsPreferred: Boolean = false

    /**
     * Should bootstrap use plain HTTPS rather than wait on the overlay?
     *
     * [overlayAvailable] is the observed fact at the call site — false when the provisioning channel
     * is not this network's transport, or the client has opted out.
     */
    fun shouldUsePlainHttps(overlayAvailable: Boolean): Boolean =
        enabled && (plainHttpsPreferred || !overlayAvailable)

    /** Tests only. Restores the shipped defaults. */
    fun reset() {
        enabled = true
        plainHttpsPreferred = false
    }
}
