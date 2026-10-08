package com.nuvio.app.core.mtls

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P2.6's trigger — when a refused handshake becomes a re-registration.
 *
 * Four things are being pinned here, and they are in order of how expensive they would be to get
 * wrong:
 *
 * 1. **The gates.** A re-registration is the only thing in this flow that spends the device's
 *    rate-limit budget and writes a ledger row, so each gate is a test: revoked (terminal — asking
 *    again earns another `403` forever), no certificate held (there is nothing to re-send, and
 *    forcing would send the registrar down the *mint* path — the very thing P2.6's parenthesis
 *    forbids), a third-party host (not ours to repair), and the cooldown.
 * 2. **The cooldown's ordering.** The stamp is written *before* the ask, because the ask travels
 *    over a client that carries this same interceptor. A re-entrant refusal must be absorbed, and
 *    that is what `the ask is recorded before it is made` asserts.
 * 3. **The exception family.** `SSLHandshakeException` on TLS 1.2, a plain `SSLException` on TLS
 *    1.3 where the alert lands after the client's Finished. Catching only the narrow class would
 *    leave the trigger silently dead on 1.3 — so both are asserted, and
 *    `SSLPeerUnverifiedException` is asserted *against*, because it means the client did not trust
 *    the server, which re-registering cannot repair.
 * 4. **The host rule**, which is `derivePinnableAddonHosts`'s: enumerated hosts, plus anything on
 *    the server's registrable domain. It is deliberately duplicated from the overlay package rather
 *    than shared, so the agreement between the two is asserted rather than assumed.
 *
 * No Robolectric: everything under test is plain JVM. The one Android-bound piece,
 * [MtlsHandshakeWatch.instance], is wiring and is not exercised — `attach` only checks it is
 * installed.
 */
class MtlsHandshakeWatchTest {

    // ── the gates ────────────────────────────────────────────────────────────

    @Test
    fun `a refused handshake on one of our hosts asks for a re-registration`() {
        val fake = FakeWatch()
        fake.refused("bsc.tracemonkey.org")
        assertEquals(listOf("bsc.tracemonkey.org"), fake.asked)
    }

    @Test
    fun `a device holding no certificate does not ask`() {
        val fake = FakeWatch(holdsCertificate = false)
        fake.refused("bsc.tracemonkey.org")
        assertTrue(
            fake.asked.isEmpty(),
            "forcing here would reach the registrar with nothing to re-send and mint a new key",
        )
    }

    @Test
    fun `a revoked device does not ask`() {
        val fake = FakeWatch(revoked = true)
        fake.refused("bsc.tracemonkey.org")
        assertTrue(fake.asked.isEmpty(), "a revoked device earns another 403 on every ask, forever")
    }

    @Test
    fun `a revoked device stops asking even after the cooldown`() {
        val fake = FakeWatch()
        fake.refused("bsc.tracemonkey.org", at = 0L)
        fake.revoked = true
        fake.refused("bsc.tracemonkey.org", at = MtlsHandshakeWatch.COOLDOWN_MILLIS * 10)
        assertEquals(1, fake.asked.size)
    }

    @Test
    fun `a refused handshake on a third-party host does not ask`() {
        val fake = FakeWatch(ours = false)
        fake.refused("catalog.nuvio.tv")
        assertTrue(fake.asked.isEmpty(), "a public addon's TLS is not ours to repair")
    }

    // ── the cooldown ─────────────────────────────────────────────────────────

    @Test
    fun `two refusals inside the cooldown ask once`() {
        val fake = FakeWatch()
        fake.refused("bsc.tracemonkey.org", at = 0L)
        fake.refused("tmdb.tracemonkey.org", at = MtlsHandshakeWatch.COOLDOWN_MILLIS - 1)
        assertEquals(1, fake.asked.size)
    }

    @Test
    fun `a refusal after the cooldown asks again`() {
        val fake = FakeWatch()
        fake.refused("bsc.tracemonkey.org", at = 0L)
        fake.refused("bsc.tracemonkey.org", at = MtlsHandshakeWatch.COOLDOWN_MILLIS)
        assertEquals(2, fake.asked.size)
    }

    @Test
    fun `a clock that moves backwards does not wedge the watch`() {
        val fake = FakeWatch()
        fake.refused("bsc.tracemonkey.org", at = 1_000L)
        fake.refused("bsc.tracemonkey.org", at = 500L)
        assertEquals(
            2,
            fake.asked.size,
            "suppressing here would leave the watch dead until wall-clock time caught up with its own stamp",
        )
    }

    @Test
    fun `the ask is recorded before it is made`() {
        val fake = FakeWatch()
        // The ask's own request travels over a client carrying this same interceptor, so it can fail
        // the same way and land back here. One ask must not become a chain.
        fake.onAsk = { fake.watch.onHandshakeRefused("bsc.tracemonkey.org") }
        fake.refused("bsc.tracemonkey.org")
        assertEquals(1, fake.asked.size)
    }

    // ── the host rule ────────────────────────────────────────────────────────

    @Test
    fun `an enumerated server host is ours`() {
        assertTrue(hostIsOurs("bsc.tracemonkey.org", setOf("bsc.tracemonkey.org")))
    }

    @Test
    fun `a host on the server's registrable domain is ours even though it is not enumerated`() {
        // The media plane arrives inside stream URLs and is in no configuration.
        assertTrue(hostIsOurs("bss-dav.tracemonkey.org", setOf("bsc.tracemonkey.org")))
    }

    @Test
    fun `a host that merely ends with the domain's letters is not ours`() {
        assertTrue(!hostIsOurs("evil-tracemonkey.org", setOf("bsc.tracemonkey.org")))
    }

    @Test
    fun `a single-label server host shares no domain with anything`() {
        // `serverDomainSuffixes` drops a host with fewer than two labels, so there is no domain for
        // a sibling to sit on: the enumerated host matches, and only the enumerated host does.
        assertTrue(hostIsOurs("bsc.local", setOf("bsc.local")))
        assertTrue(!hostIsOurs("tmdb.local", setOf("bsc.local")))
    }

    @Test
    fun `with no server hosts configured nothing is ours`() {
        assertTrue(!hostIsOurs("bsc.tracemonkey.org", emptySet()))
    }

    // ── the interceptor ──────────────────────────────────────────────────────

    @Test
    fun `a refused handshake tells the watch and the exception still reaches the caller`() {
        val fake = FakeWatch()
        val interceptor = MtlsHandshakeWatchInterceptor(fake.watch)
        val chain = FakeChain("https://bsc.tracemonkey.org/api/thing") {
            throw SSLHandshakeException("Received fatal alert: bad_certificate")
        }

        val thrown = assertFailsWith<SSLHandshakeException> { interceptor.intercept(chain) }

        assertEquals("Received fatal alert: bad_certificate", thrown.message)
        assertEquals(listOf("bsc.tracemonkey.org"), fake.asked)
    }

    @Test
    fun `a post-handshake refusal is a plain SSLException and is still seen`() {
        // ⚠️ The name carries no period on purpose: a backtick name becomes the JVM method name
        // verbatim, and `.` is one of the four characters the JVM forbids in one. "TLS 1.3" is what
        // this tests — on 1.3 the server's alert lands after the client's Finished, so it surfaces on
        // the first read as a plain SSLException rather than from startHandshake().
        val fake = FakeWatch()
        val interceptor = MtlsHandshakeWatchInterceptor(fake.watch)
        val chain = FakeChain("https://bsc.tracemonkey.org/api/thing") {
            throw SSLException("Read error")
        }

        assertFailsWith<SSLException> { interceptor.intercept(chain) }
        assertEquals(listOf("bsc.tracemonkey.org"), fake.asked)
    }

    @Test
    fun `a server we did not trust is not a reason to re-register`() {
        val fake = FakeWatch()
        val interceptor = MtlsHandshakeWatchInterceptor(fake.watch)
        val chain = FakeChain("https://bsc.tracemonkey.org/api/thing") {
            throw SSLPeerUnverifiedException("Hostname bsc.tracemonkey.org not verified")
        }

        assertFailsWith<SSLPeerUnverifiedException> { interceptor.intercept(chain) }
        assertTrue(fake.asked.isEmpty(), "re-registering our certificate cannot repair the server's")
    }

    @Test
    fun `a non-TLS failure tells the watch nothing`() {
        val fake = FakeWatch()
        val interceptor = MtlsHandshakeWatchInterceptor(fake.watch)
        val chain = FakeChain("https://bsc.tracemonkey.org/api/thing") {
            throw IOException("unexpected end of stream")
        }

        assertFailsWith<IOException> { interceptor.intercept(chain) }
        assertTrue(fake.asked.isEmpty())
    }

    @Test
    fun `a successful response tells the watch nothing`() {
        val fake = FakeWatch()
        val interceptor = MtlsHandshakeWatchInterceptor(fake.watch)
        val chain = FakeChain("https://bsc.tracemonkey.org/api/thing") { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        assertEquals(200, interceptor.intercept(chain).code)
        assertTrue(fake.asked.isEmpty())
    }

    @Test
    fun `the host reported is the bare host, not the host and port`() {
        val fake = FakeWatch()
        val interceptor = MtlsHandshakeWatchInterceptor(fake.watch)
        val chain = FakeChain("https://bsc.tracemonkey.org:8443/api/thing") {
            throw SSLHandshakeException("Received fatal alert: certificate_unknown")
        }

        assertFailsWith<SSLHandshakeException> { interceptor.intercept(chain) }
        assertEquals(listOf("bsc.tracemonkey.org"), fake.asked)
    }

    // ── the attach ───────────────────────────────────────────────────────────

    @Test
    fun `attaching the certificate also installs the watch`() {
        val client = OkHttpClient.Builder().withClientCertificate().build()
        assertTrue(
            client.interceptors.any { it is MtlsHandshakeWatchInterceptor },
            "a client that carries the certificate must be a client that notices it was refused",
        )
    }

    @Test
    fun `attaching the certificate installs the watch once`() {
        val client = OkHttpClient.Builder().withClientCertificate().build()
        assertEquals(1, client.interceptors.count { it is MtlsHandshakeWatchInterceptor })
    }

    // ── doubles ──────────────────────────────────────────────────────────────

    /**
     * A [MtlsHandshakeWatch] with every input under the test's control, and the hosts it asked for.
     *
     * `asked` records the host rather than a count so a test can tell *which* host got through the
     * gate, and the clock is a field rather than the wall so the cooldown is exercised without
     * sleeping.
     */
    private class FakeWatch(
        private val holdsCertificate: Boolean = true,
        var revoked: Boolean = false,
        private val ours: Boolean = true,
    ) {
        var clock = 0L

        /** Invoked from inside the ask, so a test can make the ask re-enter the watch. */
        var onAsk: (() -> Unit)? = null

        val asked = mutableListOf<String>()

        val watch = MtlsHandshakeWatch(
            holdsCertificate = { holdsCertificate },
            isRevoked = { revoked },
            isOurHost = { ours },
            nowMillis = { clock },
            reRegister = { host ->
                asked += host
                onAsk?.invoke()
            },
        )

        /** Move the clock to [at] and report a refusal of [host] at that moment. */
        fun refused(host: String, at: Long = clock) {
            clock = at
            watch.onHandshakeRefused(host)
        }
    }

    /**
     * The smallest thing that satisfies `Interceptor.Chain` for the two calls the interceptor makes.
     *
     * OkHttp 4.12's `Chain` declares ten members and this implementation is exercised through two of
     * them, so the rest answer cheaply rather than throwing: a future change that starts reading one
     * should fail on the assertion it breaks, not on `UnsupportedOperationException` from here.
     */
    private class FakeChain(
        private val url: String,
        private val outcome: (Request) -> Response,
    ) : Interceptor.Chain {

        override fun request(): Request = Request.Builder().url(url).build()

        override fun proceed(request: Request): Response = outcome(request)

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException("no call in a fake chain")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
