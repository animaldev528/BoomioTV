package com.nuvio.tv.core.boomio

import com.nuvio.app.core.mtls.withClientCertificate
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * The gate that keeps the device's client certificate off third-party hosts.
 *
 * [resolveHttpClientFor] is one line, and it is worth a test because of what it protects rather than
 * what it computes. `bsf` identifies a device by the TLS client certificate the edge stamps onto the
 * request, and it falls back to *permissive* behaviour for a request that arrives without one — so
 * the failure mode when this gate is wrong in the "certless" direction is completely silent: the
 * device is simply served as a stranger. The failure mode in the other direction is worse and also
 * silent: a certificate offered to a third-party addon hands that host a stable device identifier.
 *
 * Two properties are pinned, and they pull in opposite directions:
 *
 * 1. **A host the user's own server answers for gets the certificate** — the configured host, and
 *    anything on its registrable domain, including the media-plane names (`bss-*`, `nzbdav`) that
 *    appear in no configuration and arrive inside stream URLs.
 * 2. **Everything else does not** — a public addon host, a look-alike domain, and, when the
 *    configured set is empty, *everything*. The empty case is not hypothetical: it is the state of a
 *    build that has published no server configuration yet, and the safe answer there is "not ours".
 *
 * ⚠️ **The predicate under test is `hostIsOurs` — host identity — and not
 * `OverlayPinRegistry.isPinnedHost`.** The two are deliberately different sets: the pin is absent on
 * the LAN by construction, so a pin-gated client would stop presenting the certificate exactly when
 * the device is at home. The `duckdns` case below is the one where they visibly diverge on the
 * reference deployment, and it is asserted rather than assumed.
 *
 * No device and no network: the hosts are supplied explicitly here precisely so the rule can be
 * asserted without reading `localServerHosts()`, which would resolve against whatever
 * `BOOMIO_BASE_URL` the box's `local.properties` happens to name.
 */
class BoomioStreamResolverHostScopeTest {

    // Two clients, told apart by identity. The second is built by the same one-line construction the
    // DI provider uses, so what is asserted is that the *real* certificate-bearing client is the one
    // selected — not merely that "some other client" is.
    private val plain: OkHttpClient = OkHttpClient.Builder().build()
    private val certificate: OkHttpClient = OkHttpClient.Builder().withClientCertificate().build()

    private val tracemonkey = setOf("bsc.tracemonkey.org")

    // ── ours gets the certificate ────────────────────────────────────────────

    @Test
    fun `the configured server host gets the certificate`() {
        assertSame(certificate, resolveHttpClientFor("bsc.tracemonkey.org", tracemonkey, plain, certificate))
    }

    @Test
    fun `the media plane on the server's own domain gets the certificate`() {
        // `bsf` is what this resolver dials; `bss-tor` is where the returned stream URLs live.
        assertSame(certificate, resolveHttpClientFor("bsf.tracemonkey.org", tracemonkey, plain, certificate))
        assertSame(certificate, resolveHttpClientFor("bss-tor.tracemonkey.org", tracemonkey, plain, certificate))
    }

    @Test
    fun `a shared dynamic-dns name does not widen to the whole suffix`() {
        // ⚠️ `duckdns.org` is the reference deployment's origin and it is in SHARED_SUFFIXES: anyone
        // can sign up under it, so `foo.duckdns.org` is a stranger and must not be handed the
        // certificate on the strength of the origin sharing two labels with it.
        val duckdns = setOf("boomio.duckdns.org")
        assertSame(certificate, resolveHttpClientFor("boomio.duckdns.org", duckdns, plain, certificate))
        assertSame(certificate, resolveHttpClientFor("bsf.boomio.duckdns.org", duckdns, plain, certificate))
        assertSame(plain, resolveHttpClientFor("somebody-else.duckdns.org", duckdns, plain, certificate))
    }

    // ── not ours does not ────────────────────────────────────────────────────

    @Test
    fun `a third-party addon host never gets the certificate`() {
        assertSame(plain, resolveHttpClientFor("image.tmdb.org", tracemonkey, plain, certificate))
        assertSame(plain, resolveHttpClientFor("catalog.nuvio.tv", tracemonkey, plain, certificate))
        assertSame(plain, resolveHttpClientFor("opensubtitles-v3.strem.io", tracemonkey, plain, certificate))
    }

    @Test
    fun `a look-alike domain is not ours`() {
        // The suffix match is anchored with a leading dot, so a name that merely *ends with* the
        // server's domain as a substring must not match.
        assertSame(plain, resolveHttpClientFor("eviltracemonkey.org", tracemonkey, plain, certificate))
    }

    @Test
    fun `no configured hosts means no certificate for anyone`() {
        // The pre-configuration state. Every host is a stranger until one is named, so the
        // certificate stays in the keystore rather than being offered to whatever is dialled first.
        assertSame(plain, resolveHttpClientFor("bsc.tracemonkey.org", emptySet(), plain, certificate))
    }
}
