package com.nuvio.app.core.overlay

import android.app.Application
import com.nuvio.app.features.boomio.BoomioConfig
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The relay, exercised over real loopback sockets.
 *
 * Robolectric rather than a plain host test, for one reason: `OverlayRelay` logs, and
 * `android.util.Log` throws when unmocked. The sockets themselves are real — Robolectric
 * does not sandbox them — so these tests are about the relay's actual behaviour on a real
 * TCP connection, not a mock of one.
 *
 * ⚠️ **Every wait is bounded.** A proxy test that hangs on failure is worse than useless:
 * it burns the build box until someone kills it, and it reports nothing. Every latch here
 * has a timeout and every socket has an `soTimeout`, so a regression fails in seconds with
 * a message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayRelayTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        OverlayRelay.stop()
        scope.cancel()
    }

    // ------------------------------------------------------------------ the lifecycle

    @Test
    fun `state is down until started and up on a bound port after`() {
        OverlayRelay.stop()
        assertEquals(RelayState.Down, OverlayRelay.state.value)

        val handle = assertNotNull(startRelay())

        // `assertIs` rather than `assertTrue(state is …)`: only the former narrows the
        // type, and the failure message names what was actually there.
        val state = assertIs<RelayState.Up>(OverlayRelay.state.value)
        assertEquals(handle.port, state.port)
        assertTrue(handle.port > 0, "the port must be the kernel's, not 0")
    }

    @Test
    fun `the advertised port is accepting before start returns`() {
        // ⚠️ The fail-open property, and the reason the accept loop is started with
        // LAZY: `Up` is published, and the socket is already bound, so a client wired the
        // instant `start` returns is served. If the port were advertised before the bind,
        // or the loop only began on a later trigger, this connect would be refused.
        val handle = assertNotNull(startRelay())

        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), handle.port), 2_000)
            assertTrue(socket.isConnected)
        }
    }

    @Test
    fun `starting twice returns the same handle rather than a second listener`() {
        val first = startRelay()
        val second = startRelay()

        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun `the proxy url carries the credentials libmpv needs`() {
        val handle = assertNotNull(startRelay())

        // libmpv takes a proxy only as a URL, so the secret has to ride in it — which is
        // why `newRelaySecret` emits hex: nothing here needs percent-escaping, and the
        // whole URL stays ASCII with no encoding step to get wrong.
        assertEquals("http://boomio:${handle.secret}@127.0.0.1:${handle.port}", handle.proxyUrl)
    }

    @Test
    fun `stop releases the port and refuses further connections`() {
        val handle = assertNotNull(startRelay())
        val port = handle.port

        OverlayRelay.stop()

        assertEquals(RelayState.Down, OverlayRelay.state.value)
        assertNull(connectOrNull(port), "the listener must be closed")
    }

    @Test
    fun `an unconfigured start is inert and a later address opens it`() {
        // ⚠️ The regression this guards. `initialize` runs *before* enrollment, and on a
        // fresh install the address does not exist yet — it is the thing enrollment is about
        // to learn. If the blank check were latched alongside the rest of `initialize`, a
        // device that had just enrolled would hold a perfectly good assignment and still have
        // no relay until it was restarted, which is exactly the shipped-build case the runtime
        // address change exists for.
        //
        // Only this test calls `initialize`, and it has to be that way: the latch is
        // process-wide, so a second caller would be a silent no-op rather than a second relay.
        val original = BoomioConfig.overlayServerAddress
        try {
            BoomioConfig.overlayServerAddress = ""
            OverlayRelay.initialize()

            assertEquals(
                RelayState.Down,
                OverlayRelay.state.value,
                "no address means no listener, and no socket opened either",
            )

            BoomioConfig.overlayServerAddress = "10.77.0.1"
            OverlayRelay.onServerAddressLearned()

            val state = assertIs<RelayState.Up>(OverlayRelay.state.value)
            assertTrue(state.port > 0, "the port must be the kernel's, not 0")
        } finally {
            // Both, or this test poisons every other one in the class: a bound handle would be
            // handed back by `start`, and a left-behind address would turn the blank-inert
            // assertions elsewhere into passes for the wrong reason.
            OverlayRelay.stop()
            BoomioConfig.overlayServerAddress = original
        }
    }

    @Test
    fun `the relay is not reachable on a non-loopback address`() {
        // ⚠️ The difference between a private proxy and an open one. Bound to `0.0.0.0`,
        // this port would be a general-purpose authenticated-bypass proxy for the whole
        // LAN — and the auth check would be the only thing between it and any device on
        // the network.
        val lan = firstLanAddress() ?: return
        val handle = assertNotNull(startRelay())

        assertNull(
            connectOrNull(handle.port, lan),
            "the relay answered on $lan — it is not bound to loopback",
        )
    }

    // ----------------------------------------------------------------- carrying bytes

    @Test
    fun `an authorised connect carries bytes in both directions`() {
        val handle = assertNotNull(startRelay())
        TcpTarget().use { target ->
            val request = "GET /stream HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
            target.expect(request.size, reply = "PONG".toByteArray())

            connectToRelay(handle).use { client ->
                client.getOutputStream().write(
                    connect(handle, "127.0.0.1:${target.port}", "boomio", handle.secret),
                )
                client.getOutputStream().flush()

                assertEquals(
                    "HTTP/1.1 200 Connection Established\r\n\r\n",
                    String(readExactly(client.getInputStream(), ESTABLISHED_RESPONSE.size)),
                )

                // Through the tunnel exactly as a real client would: the request goes in
                // after the tunnel is up, not before.
                client.getOutputStream().write(request)
                client.getOutputStream().flush()

                assertTrue(target.awaitExpected(), "the target never saw the request")
                assertEquals(String(request), String(target.received()))
                assertEquals("PONG", String(readExactly(client.getInputStream(), 4)))
            }
        }
    }

    @Test
    fun `bytes sent in the same packet as the connect head are not lost`() {
        // ⚠️ **The regression this whole test file exists for.** A CONNECT is followed
        // immediately by the client's TLS ClientHello or first request, and a client is
        // free to put both in one write. A head reader that consumed a *block* would
        // swallow the payload sitting behind `\r\n\r\n`, and the tunnel would come up and
        // then hang forever — with nothing logged, because nothing failed.
        val handle = assertNotNull(startRelay())
        TcpTarget().use { target ->
            val payload = "PIPELINED-PAYLOAD".toByteArray()
            target.expect(payload.size, reply = "OK".toByteArray())

            connectToRelay(handle).use { client ->
                val both = connect(handle, "127.0.0.1:${target.port}", "boomio", handle.secret) + payload
                client.getOutputStream().write(both)
                client.getOutputStream().flush()

                assertEquals(
                    "HTTP/1.1 200 Connection Established\r\n\r\n",
                    String(readExactly(client.getInputStream(), ESTABLISHED_RESPONSE.size)),
                )
                assertTrue(target.awaitExpected(), "the pipelined payload never reached the target")
                assertEquals(String(payload), String(target.received()))
            }
        }
    }

    @Test
    fun `closing the client closes the upstream`() {
        // Otherwise every tunnel a client abandons leaks a socket and a thread pair on a
        // device that cannot afford either.
        val handle = assertNotNull(startRelay())
        TcpTarget().use { target ->
            connectToRelay(handle).use { client ->
                client.getOutputStream().write(connect(handle, "127.0.0.1:${target.port}", "boomio", handle.secret))
                client.getOutputStream().flush()
                readExactly(client.getInputStream(), ESTABLISHED_RESPONSE.size)
                // use { } closes here; the relay must propagate that as EOF upstream.
            }

            assertTrue(target.awaitEof(), "the upstream was left open after the client closed")
        }
    }

    @Test
    fun `the route decides which dialler is used`() {
        val carried = SpyDialer()
        val direct = SpyDialer()
        val handle = assertNotNull(
            OverlayRelay.start(
                scope,
                OverlayDialers(carried = carried, direct = direct),
                allowSet = { setOf("bss-tor.tracemonkey.org") },
            ),
        )

        refuse(handle, "bss-tor.tracemonkey.org:443")   // carried, then fails to dial
        refuse(handle, "catalog.nuvio.tv:443")          // outside the set

        assertEquals(listOf("bss-tor.tracemonkey.org:443"), carried.dialled)
        assertEquals(listOf("catalog.nuvio.tv:443"), direct.dialled)
    }

    @Test
    fun `the allow set is read per connection, not once`() {
        // ⚠️ The addon catalogue loads seconds after launch and *widens* the set, so a set
        // captured at start would miss most of the app's server hosts for the whole
        // session. This asserts the lambda is consulted at dial time.
        val carried = SpyDialer()
        val direct = SpyDialer()
        var allow = setOf("a.example.com")
        val handle = assertNotNull(startRelay(carried = carried, direct = direct, allow = { allow }))

        // Not in the set yet: direct, and nothing carried.
        refuse(handle, "b.example.com:443")
        assertEquals(emptyList(), carried.dialled)
        assertEquals(listOf("b.example.com:443"), direct.dialled)

        // The catalogue lands and the set widens — the very next connection must see it.
        allow = allow + "b.example.com"
        refuse(handle, "b.example.com:443")

        assertEquals(listOf("b.example.com:443"), carried.dialled)
    }

    @Test
    fun `loopback dials direct even when the allow set contains it`() {
        val carried = SpyDialer()
        val direct = SpyDialer()
        val handle = assertNotNull(
            OverlayRelay.start(
                scope,
                OverlayDialers(carried = carried, direct = direct),
                allowSet = { setOf("127.0.0.1") },
            ),
        )

        refuse(handle, "127.0.0.1:8099")

        assertEquals(emptyList(), carried.dialled)
        assertEquals(listOf("127.0.0.1:8099"), direct.dialled)
    }

    // ----------------------------------------------------------------------- refusals

    @Test
    fun `a connect without credentials is refused with 407 and dials nothing`() {
        // ⚠️ **The load-bearing test of the whole file.** The allow-set is a routing rule,
        // not a gate, so an unauthenticated relay is a free general-purpose proxy for
        // every other app on the phone. The second assertion is the other half: a refusal
        // must happen *before* the dial, or an unauthenticated caller can still use the
        // relay to probe the network.
        val spy = SpyDialer()
        val handle = assertNotNull(startRelay(carried = spy, direct = spy))

        val response = request(
            handle,
            "CONNECT bss-tor.tracemonkey.org:443 HTTP/1.1\r\nHost: bss-tor.tracemonkey.org:443\r\n",
        )

        assertTrue(response.startsWith("HTTP/1.1 407"), "expected 407, got: $response")
        assertEquals(emptyList(), spy.dialled)
    }

    @Test
    fun `a connect with the wrong credentials is refused with 407 and dials nothing`() {
        val spy = SpyDialer()
        val handle = assertNotNull(startRelay(carried = spy, direct = spy))

        val response = request(
            handle,
            "CONNECT bss-tor.tracemonkey.org:443 HTTP/1.1\r\n" +
                "Proxy-Authorization: Basic ${base64("boomio:not-the-secret")}\r\n",
        )

        assertTrue(response.startsWith("HTTP/1.1 407"), "expected 407, got: $response")
        assertEquals(emptyList(), spy.dialled)
    }

    @Test
    fun `a non-connect method is refused`() {
        // An absolute-URI GET is the other thing a proxy is asked to do, and this relay
        // does not forward plain requests — so it must say so rather than treat the URL as
        // an authority.
        val spy = SpyDialer()
        val handle = assertNotNull(startRelay(carried = spy, direct = spy))

        val response = request(
            handle,
            "GET http://example.com/ HTTP/1.1\r\n" +
                "Proxy-Authorization: Bearer ${handle.secret}\r\n",
        )

        assertTrue(response.startsWith("HTTP/1.1 405"), "expected 405, got: $response")
        assertEquals(emptyList(), spy.dialled)
    }

    @Test
    fun `a malformed authority is refused`() {
        val spy = SpyDialer()
        val handle = assertNotNull(startRelay(carried = spy, direct = spy))

        val response = request(
            handle,
            "CONNECT no-port-here HTTP/1.1\r\nProxy-Authorization: Bearer ${handle.secret}\r\n",
        )

        assertTrue(response.startsWith("HTTP/1.1 400"), "expected 400, got: $response")
        assertEquals(emptyList(), spy.dialled)
    }

    @Test
    fun `a connect to the relay's own port is refused instead of recursing`() {
        // Without the guard this CONNECT is accepted, dialled back into the relay, and
        // accepted again — one connection becoming an unbounded chain of them.
        val spy = SpyDialer()
        val handle = assertNotNull(startRelay(carried = spy, direct = spy))

        val response = request(
            handle,
            "CONNECT 127.0.0.1:${handle.port} HTTP/1.1\r\n" +
                "Proxy-Authorization: Bearer ${handle.secret}\r\n",
        )

        assertTrue(response.startsWith("HTTP/1.1 421"), "expected 421, got: $response")
        assertEquals(emptyList(), spy.dialled)
    }

    @Test
    fun `an unreachable target is a 502 rather than a hang`() {
        // The client is told, so it can retry — instead of the relay holding the
        // connection open while the caller waits for a 200 that will never come.
        val handle = assertNotNull(startRelay())

        val response = request(
            handle,
            "CONNECT 127.0.0.1:1 HTTP/1.1\r\n" +           // nothing listens on port 1
                "Proxy-Authorization: Bearer ${handle.secret}\r\n",
        )

        assertTrue(response.startsWith("HTTP/1.1 502"), "expected 502, got: $response")
    }

    @Test
    fun `an oversized head is refused rather than buffered forever`() {
        val handle = assertNotNull(startRelay())

        val response = request(
            handle,
            "CONNECT example.com:443 HTTP/1.1\r\n" + "X-Filler: ${"a".repeat(20_000)}\r\n",
        )

        assertTrue(response.startsWith("HTTP/1.1 431"), "expected 431, got: ${response.take(40)}")
    }

    @Test
    fun `a client that connects and says nothing does not wedge the relay`() {
        // The head timeout exists so a silent connection cannot hold a thread for the
        // process's lifetime. The second half is the real assertion: the relay is still
        // serving afterwards.
        val handle = assertNotNull(startRelay())

        Socket().use { silent ->
            silent.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), handle.port), 2_000)
            // Close without writing; the relay's read sees EOF and gives up.
        }

        val response = request(
            handle,
            "CONNECT 127.0.0.1:1 HTTP/1.1\r\nProxy-Authorization: Bearer ${handle.secret}\r\n",
        )
        assertTrue(response.startsWith("HTTP/1.1 502"), "the relay stopped serving: $response")
    }

    // ------------------------------------------------------------------------ helpers

    private fun startRelay(
        carried: OverlayDialer = DirectDialer,
        direct: OverlayDialer = DirectDialer,
        allow: () -> Set<String> = { emptySet() },
    ): OverlayRelayHandle? =
        OverlayRelay.start(scope, OverlayDialers(carried = carried, direct = direct), allow)

    private fun connect(
        handle: OverlayRelayHandle,
        authority: String,
        user: String,
        password: String,
    ): ByteArray =
        ("CONNECT $authority HTTP/1.1\r\n" +
            "Host: $authority\r\n" +
            "Proxy-Authorization: Basic ${base64("$user:$password")}\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1)

    /**
     * Opens a connection, sends [head] as a complete request, and reads until the relay
     * closes.
     *
     * ⚠️ **The terminating blank line is appended here, not left to the caller.** `readHead`
     * waits for `\r\n\r\n`, so a head that stops after its last header is not a malformed
     * request — it is an *incomplete* one, and the relay correctly waits for the rest until
     * the head timeout expires. A caller that forgets it gets an empty response and a
     * five-second wait, which reads as "the relay never answered" and sends you hunting for
     * a bug in the relay that is not there.
     */
    private fun request(handle: OverlayRelayHandle, head: String): String {
        Socket().use { client ->
            client.soTimeout = 5_000
            client.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), handle.port), 2_000)
            // The write may lose the race with the relay's refusal and close — which is
            // the refusal working, not a test failure.
            runCatching {
                val wire = (head.trimEnd() + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                client.getOutputStream().write(wire)
                client.getOutputStream().flush()
            }
            return readAll(client.getInputStream())
        }
    }

    /**
     * A socket already connected to the relay.
     *
     * ⚠️ `Socket()` on its own is **unconnected**, and `getOutputStream()` on one throws
     * `SocketException("Socket is not connected")` — the same exception class a genuine
     * relay fault would raise, so using it by mistake produces a failure that looks like a
     * relay bug and is not one.
     */
    private fun connectToRelay(handle: OverlayRelayHandle): Socket =
        Socket().apply {
            soTimeout = 5_000
            connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), handle.port), 2_000)
        }

    /** Sends a CONNECT and discards everything the relay says. */
    private fun refuse(handle: OverlayRelayHandle, authority: String) {
        request(handle, "CONNECT $authority HTTP/1.1\r\nProxy-Authorization: Bearer ${handle.secret}\r\n")
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val read = runCatching { input.read(buffer) }.getOrElse { break }
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.ISO_8859_1)
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read < 0) throw AssertionError("stream ended after $offset of $count bytes")
            offset += read
        }
        return out
    }

    private fun connectOrNull(port: Int, address: InetAddress = InetAddress.getByName("127.0.0.1")): Socket? =
        runCatching {
            Socket().apply { connect(InetSocketAddress(address, port), 1_000) }
        }.getOrNull()

    private fun firstLanAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
    }.getOrNull()

    /**
     * A one-shot TCP server on loopback that records what it received.
     *
     * Deliberately a real socket rather than a fake [OverlayConnection]: the thing under
     * test is byte movement, and a fake that hands back a byte array would prove nothing
     * about whether the bytes actually crossed a socket.
     */
    private class TcpTarget : Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val receivedBytes = ByteArrayOutputStream()
        private val expected = CountDownLatch(1)
        private val eof = CountDownLatch(1)

        @Volatile private var expectedCount = -1
        @Volatile private var reply: ByteArray = ByteArray(0)

        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "overlay-relay-test-target") {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        val buffer = ByteArray(4096)
                        var replied = false
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            synchronized(receivedBytes) { receivedBytes.write(buffer, 0, read) }
                            val seen = synchronized(receivedBytes) { receivedBytes.size() }
                            if (!replied && expectedCount in 1..seen) {
                                replied = true
                                expected.countDown()
                                if (reply.isNotEmpty()) {
                                    output.write(reply)
                                    output.flush()
                                }
                            }
                        }
                        eof.countDown()
                    }
                }
            }
        }

        fun expect(count: Int, reply: ByteArray) {
            expectedCount = count
            this.reply = reply
        }

        fun awaitExpected(): Boolean = expected.await(5, TimeUnit.SECONDS)

        fun awaitEof(): Boolean = eof.await(5, TimeUnit.SECONDS)

        fun received(): ByteArray = synchronized(receivedBytes) { receivedBytes.toByteArray() }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private class SpyDialer : OverlayDialer {
        val dialled = CopyOnWriteArrayList<String>()

        override fun dial(host: String, port: Int): OverlayConnection {
            dialled += "$host:$port"
            // No real target: these tests are about *which* dialler was chosen and when,
            // and a successful dial would only add a socket to clean up.
            throw IllegalStateException("spy dialler has no target")
        }
    }
}

/**
 * Standard-alphabet Base64, hand-rolled.
 *
 * Not `java.util.Base64`: this module's `minSdk` is 24 and that class arrived in API 26.
 * The protocol tests pin the *decoder* against independently-produced literals, so this
 * encoder only has to be self-consistent — and if it were wrong, the relay would reject
 * the credential and the test would fail loudly rather than pass quietly.
 */
private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private fun base64(text: String): String {
    val bytes = text.toByteArray(Charsets.ISO_8859_1)
    val out = StringBuilder()
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index].toInt() and 0xFF
        val second = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else -1
        val third = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else -1

        out.append(B64_ALPHABET[first shr 2])
        out.append(B64_ALPHABET[((first and 0x03) shl 4) or (if (second >= 0) second shr 4 else 0)])
        if (second >= 0) {
            out.append(B64_ALPHABET[((second and 0x0F) shl 2) or (if (third >= 0) third shr 6 else 0)])
        } else {
            out.append('=')
        }
        if (third >= 0) out.append(B64_ALPHABET[third and 0x3F]) else out.append('=')
        index += 3
    }
    return out.toString()
}
