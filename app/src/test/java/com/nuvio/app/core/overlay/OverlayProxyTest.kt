package com.nuvio.app.core.overlay

import android.app.Application
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The seam that points the app's HTTP engines at the relay.
 *
 * Two halves, tested two ways:
 *
 *  * `relayProxyAuthorization` and the selector are **pure decisions**, and they are where
 *    the security-relevant rules live — so they are asserted branch by branch, with no
 *    sockets involved.
 *  * The wiring is then proved **end to end through a real `OkHttpClient`**, because the
 *    interesting failures here are integration failures: a selector that is set but never
 *    consulted, a proxy that is selected but never authenticated, a client that captured
 *    "no relay" at construction and never noticed the relay come up.
 *
 * Robolectric rather than a plain host test for the same reason as [OverlayRelayTest]:
 * OkHttp on Android logs through `android.util.Log`, which throws when unmocked. The
 * sockets and the HTTP client are real.
 *
 * ⚠️ **Every wait is bounded** — latches and `soTimeout`s throughout, so a regression
 * fails in seconds with a message rather than parking the build box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayProxyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        OverlayRelay.stop()
        scope.cancel()
    }

    // ------------------------------------------------------- the authorization decision

    @Test
    fun `authorization is the secret only for our own relay port`() {
        val endpoint = endpoint(port = 43_123, secret = "deadbeef")
        val ours = InetSocketAddress("127.0.0.1", 43_123)

        assertEquals(
            "Bearer deadbeef",
            relayProxyAuthorization(Proxy.Type.HTTP, ours, alreadySent = false, endpoint = endpoint),
        )
    }

    @Test
    fun `authorization stays silent when the relay is not up`() {
        // The fail-open half: a 407 that arrives while the relay is down cannot be ours,
        // so there is nothing to answer with.
        assertNull(
            relayProxyAuthorization(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", 43_123),
                alreadySent = false,
                endpoint = null,
            ),
        )
    }

    @Test
    fun `authorization stays silent for a non-HTTP proxy`() {
        // Only an HTTP proxy can challenge for proxy credentials. A SOCKS route answering
        // 407 is not the relay, whatever it claims.
        assertNull(
            relayProxyAuthorization(
                Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", 43_123),
                alreadySent = false,
                endpoint = endpoint(43_123, "deadbeef"),
            ),
        )
        assertNull(
            relayProxyAuthorization(
                Proxy.Type.DIRECT,
                InetSocketAddress("127.0.0.1", 43_123),
                alreadySent = false,
                endpoint = endpoint(43_123, "deadbeef"),
            ),
        )
    }

    @Test
    fun `authorization stays silent for a non-loopback proxy`() {
        // ⚠️ **The load-bearing leak test.** The relay only ever listens on loopback, so a
        // challenge from a routable address is a remote server asking for a credential it
        // has no business holding. Sending it here would be a secret exfiltration that the
        // *remote* party triggers.
        assertNull(
            relayProxyAuthorization(
                Proxy.Type.HTTP,
                InetSocketAddress(InetAddress.getByName("10.0.0.1"), 43_123),
                alreadySent = false,
                endpoint = endpoint(43_123, "deadbeef"),
            ),
        )
    }

    @Test
    fun `authorization stays silent for a loopback proxy on the wrong port`() {
        // A stale challenge, or another app's loopback proxy. The relay's port is
        // kernel-assigned and changes on every start, so port equality is what makes
        // "loopback" mean "ours" rather than "local to this phone".
        assertNull(
            relayProxyAuthorization(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", 43_124),
                alreadySent = false,
                endpoint = endpoint(43_123, "deadbeef"),
            ),
        )
    }

    @Test
    fun `authorization stays silent when the address is unresolved or absent`() {
        // `InetSocketAddress.createUnresolved` leaves `address` null, and `isLoopbackAddress`
        // on a null address is exactly the kind of thing that NPEs or, worse, reads as
        // "not loopback" in one direction and "loopback" in the other. Both must say no.
        assertNull(
            relayProxyAuthorization(
                Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved("127.0.0.1", 43_123),
                alreadySent = false,
                endpoint = endpoint(43_123, "deadbeef"),
            ),
        )
        assertNull(
            relayProxyAuthorization(Proxy.Type.HTTP, null, alreadySent = false, endpoint = endpoint(43_123, "deadbeef")),
        )
        assertNull(relayProxyAuthorization(null, InetSocketAddress("127.0.0.1", 43_123), alreadySent = false, endpoint = endpoint(43_123, "deadbeef")))
    }

    @Test
    fun `authorization does not answer the same challenge twice`() {
        // The loop guard. Without it, a relay (or an impostor) that keeps answering 407
        // gets the secret replayed on every follow-up until OkHttp's limit trips.
        assertNull(
            relayProxyAuthorization(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", 43_123),
                alreadySent = true,
                endpoint = endpoint(43_123, "deadbeef"),
            ),
        )
    }

    // ------------------------------------------------------------------ the selector

    @Test
    fun `selector dials direct while the relay is down`() {
        OverlayRelay.stop()
        assertEquals(listOf(Proxy.NO_PROXY), OverlayProxy.selector.select(URI("https://bss-tor.tracemonkey.org/x")))
        assertNull(OverlayProxy.mpvProxyUrl())
    }

    @Test
    fun `selector hands over the relay once it is up`() {
        val handle = assertNotNull(startRelay())

        val proxies = OverlayProxy.selector.select(URI("https://bss-tor.tracemonkey.org/x"))

        val proxy = proxies.single()
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.address.hostAddress)
        assertEquals(handle.port, address.port)
    }

    @Test
    fun `selector never proxies the relay to itself`() {
        // ⚠️ Without this exemption a request aimed at the relay is handed back to the
        // relay as its own proxy and the first CONNECT deadlocks. It is a liveness rule,
        // not a filter — `127.0.0.2` is the same loopback interface and must also be
        // answered direct.
        assertNotNull(startRelay())

        assertEquals(listOf(Proxy.NO_PROXY), OverlayProxy.selector.select(URI("https://127.0.0.1/x")))
        assertEquals(listOf(Proxy.NO_PROXY), OverlayProxy.selector.select(URI("https://127.0.0.2/x")))
        assertEquals(listOf(Proxy.NO_PROXY), OverlayProxy.selector.select(URI("https://localhost:8080/x")))
        assertEquals(listOf(Proxy.NO_PROXY), OverlayProxy.selector.select(URI("https://[::1]/x")))
    }

    @Test
    fun `selector dials direct for cleartext even when the relay is up`() {
        // ⚠️ **The 405 guard.** OkHttp only CONNECTs for `https`; for `http` it sends
        // absolute-form, which the relay answers `405 Method Not Allowed`. Proxying a
        // cleartext request would therefore convert a working request into a hard failure
        // — the one way this seam could make things worse, so it is asserted directly.
        assertNotNull(startRelay())

        assertEquals(listOf(Proxy.NO_PROXY), OverlayProxy.selector.select(URI("http://bss-tor.tracemonkey.org/x")))
    }

    @Test
    fun `selector answers direct for a URI with no host`() {
        assertNotNull(startRelay())
        assertEquals(listOf(Proxy.NO_PROXY), OverlayProxy.selector.select(URI("mailto:someone@example.com")))
    }

    @Test
    fun `mpv is handed the credentialed url while the relay is up`() {
        val handle = assertNotNull(startRelay())

        val url = assertNotNull(OverlayProxy.mpvProxyUrl())

        // libmpv has no separate credential option, so the secret must ride in the URL —
        // this asserts the shape libmpv actually needs, not just that something came back.
        assertEquals("http://boomio:${handle.secret}@127.0.0.1:${handle.port}", url)
        assertEquals(url, handle.proxyUrl)
    }

    // ------------------------------------------------------------ the wiring, end to end

    @Test
    fun `a client built before the relay comes up still reaches it`() {
        // ⚠️ **The "never capture into a val" property, asserted at the client.**
        // `OverlayRelay.start` runs once from `onCreate`, but HTTP clients are built lazily
        // all over the app and some are built before the discovery ladder has walked
        // anything. If the selector read the relay once at construction, that client would
        // stay direct for the life of the process and the relay would come up unnoticed.
        OverlayRelay.stop()
        val client = proxiedClient()

        val spy = SpyDialer()
        assertNotNull(startRelay(carried = spy, direct = spy))

        runCatching { client.newCall(Request.Builder().url("https://example.invalid/").build()).execute() }

        // The oracle is the relay's own dialler: a CONNECT arrived, was authenticated, and
        // was dialled. TLS never happens (the spy has no upstream) — that is fine, the
        // tunnel is not what is under test.
        //
        // Asserted as "every dial was the target" rather than an exact list: OkHttp is free
        // to retry a failed tunnel, and the count is not the property under test — *what*
        // was dialled is.
        assertTrue(spy.dialled.isNotEmpty(), "no CONNECT reached the relay")
        assertTrue(spy.dialled.all { it == "example.invalid:443" }, "unexpected dials: ${spy.dialled}")
    }

    @Test
    fun `a cleartext request never reaches the relay and never carries the secret`() {
        // Fail-open, the 405 guard and the no-leak rule in one real request: the relay is
        // UP, the client is wired, and a plain `http://` origin must still be served —
        // directly, with no `Proxy-Authorization` anywhere near it.
        val spy = SpyDialer()
        assertNotNull(startRelay(carried = spy, direct = spy))

        OriginServer().use { origin ->
            val response = proxiedClient()
                .newCall(Request.Builder().url("http://127.0.0.1:${origin.port}/probe").build())
                .execute()

            assertEquals(200, response.code)
            assertEquals("ok", response.body.string())
            // The origin records the head on its own thread; without this the read below
            // races it and fails as "no request arrived" on a request that plainly did.
            assertTrue(origin.awaitServed(), "the origin never answered")
        }

        val head = assertNotNull(originLastHead, "the origin never received a request")
        assertTrue(head.startsWith("GET /probe"), "unexpected request line: $head")
        assertTrue(
            !head.contains("Proxy-Authorization", ignoreCase = true),
            "the relay's secret was replayed at a plain origin:\n$head",
        )
        assertTrue(spy.dialled.isEmpty(), "a cleartext request was tunnelled: ${spy.dialled}")
    }

    // ------------------------------------------------------------------------ helpers

    /** The last request head [OriginServer] received, for tests that assert on the wire. */
    private var originLastHead: String? = null

    private fun startRelay(
        carried: OverlayDialer = DirectDialer,
        direct: OverlayDialer = DirectDialer,
    ): OverlayRelayHandle? =
        OverlayRelay.start(scope, OverlayDialers(carried = carried, direct = direct), allowSet = { emptySet() })

    private fun proxiedClient(): OkHttpClient =
        OkHttpClient.Builder()
            .withOverlayProxy()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    private fun endpoint(port: Int, secret: String): OverlayProxyEndpoint =
        OverlayProxyEndpoint(port, secret, "http://boomio:$secret@127.0.0.1:$port")

    /**
     * A one-shot cleartext origin on loopback that records the request head it was sent.
     *
     * Hand-rolled rather than MockWebServer because the assertion is about the **raw
     * bytes** — specifically that a particular header is absent — and a server that parses
     * the request for us is a server that could hide what it saw.
     */
    private inner class OriginServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            // Bounds `accept`, so a test that never gets a connection fails on the latch
            // rather than leaving a thread parked on the accept queue.
            soTimeout = 5_000
        }
        private val served = CountDownLatch(1)

        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "origin-server") {
                runCatching {
                    server.accept().use { client ->
                        client.soTimeout = 5_000
                        originLastHead = readHead(client) ?: return@use
                        client.getOutputStream().write(OK_RESPONSE)
                        client.getOutputStream().flush()
                    }
                }
                served.countDown()
            }
        }

        /** Waits for the origin to have answered. Returns false when nothing arrived. */
        fun awaitServed(): Boolean = served.await(5, TimeUnit.SECONDS)

        override fun close() {
            runCatching { server.close() }
        }
    }

    private fun readHead(socket: Socket): String? {
        val out = ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (true) {
            val next = runCatching { input.read() }.getOrElse { return null }
            if (next < 0) return null
            out.write(next)
            val text = out.toString(Charsets.ISO_8859_1.name())
            if (text.endsWith("\r\n\r\n")) return text
            if (out.size() > MAX_HEAD_BYTES) return null
        }
    }

    private class SpyDialer : OverlayDialer {
        val dialled = CopyOnWriteArrayList<String>()

        override fun dial(host: String, port: Int): OverlayConnection {
            dialled += "$host:$port"
            // No real target: these tests are about *whether* the relay was reached, and a
            // successful dial would only add a socket to clean up.
            throw IllegalStateException("spy dialler has no target")
        }
    }

    private companion object {
        const val MAX_HEAD_BYTES = 8 * 1024

        val OK_RESPONSE: ByteArray =
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 2\r\n" +
                "Connection: close\r\n\r\nok").toByteArray(Charsets.ISO_8859_1)
    }
}
