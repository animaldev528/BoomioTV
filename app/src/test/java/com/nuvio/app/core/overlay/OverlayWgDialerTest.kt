package com.nuvio.app.core.overlay

import android.app.Application
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tunnel dialler and its stream adapters, against a fake binding.
 *
 * **What is being tested here, and what is not.** The real binding is a JNI library that
 * loads a `.so` and needs a device, so a host test cannot call it — which is the entire
 * reason [OverlayWgBinding] is an interface. What is left on this side of that seam is
 * every piece of logic that can be wrong: the offset window handed to Go, the loop that
 * absorbs a short write, the `-1` end-of-stream sentinel, the argument checks, and close
 * semantics. None of those are exercised by calling the AAR even once — they are exercised
 * by the fake recording exactly what it was asked to do.
 *
 * ⚠️ **The buffer-identity assertions are the load-bearing ones.** gobind copies a Java
 * `byte[]` into a fresh Go slice on every call, so re-packaging the array here to express
 * an offset would be a second 32 KB allocation per read on the media path. `assertSame`
 * proves the caller's array reached the binding untouched — a `assertEquals` on contents
 * would pass just as happily against a copy, which is the bug worth preventing.
 *
 * Robolectric because [PreferTunnelDialer] logs and `android.util.Log` throws unmocked.
 * Nothing here needs a device or a network: the fake *is* the tunnel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayWgDialerTest {

    private val binding = FakeWgBinding()

    /** A dialled connection, with the tunnel's handle pinned so close tests can name it. */
    private fun connection(handle: Long = 1L): OverlayConnection {
        binding.nextHandle = handle
        return OverlayWgDialer(binding).dial("bss-tor.tracemonkey.org", 443)
    }

    // ---------------------------------------------------------------- dialling

    @Test
    fun `a dial names the host and port it was asked for`() {
        OverlayWgDialer(binding).dial("bss-tor.tracemonkey.org", 443)

        assertEquals(listOf("bss-tor.tracemonkey.org" to 443), binding.dialled)
    }

    @Test
    fun `a refused dial propagates so the relay can answer 502`() {
        // The relay replies `200 Connection Established` only after `dial` returns, so a
        // swallowed failure here would be a client told the tunnel is up and then given
        // silence — indistinguishable from a slow server, and the difference between a
        // retry and a hang.
        binding.dialFailure = IOException("no route through the tunnel")

        assertFailsWith<IOException> { OverlayWgDialer(binding).dial("bss-tor.tracemonkey.org", 443) }
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun `read hands the caller's own buffer and window to the tunnel`() {
        binding.incoming = ByteArray(64) { it.toByte() }
        val connection = connection()
        val buffer = ByteArray(32)

        val read = connection.input.read(buffer, 4, 20)

        assertEquals(20, read)
        val window = binding.reads.single()
        assertSame(buffer, window.buffer)
        assertEquals(4, window.offset)
        assertEquals(20, window.length)
    }

    @Test
    fun `read reports the byte count straight through`() {
        binding.incoming = ByteArray(7)
        val connection = connection()

        assertEquals(7, connection.input.read(ByteArray(32), 0, 32))
    }

    @Test
    fun `a finished stream reads as minus one`() {
        // The Go side returns -1 for end of stream, which is `java.io.InputStream`'s own
        // sentinel — so this asserts the translation really is a no-op, which is what the
        // no-error-return design in overlaywg.go bought.
        binding.incoming = ByteArray(0)
        val connection = connection()

        assertEquals(-1, connection.input.read(ByteArray(32), 0, 32))
    }

    @Test
    fun `a single byte read is unsigned`() {
        // 0xFF through a signed `byte` is -128, which is also a negative value `read` would
        // treat as a finished stream — so a missing `and 0xFF` ends a connection on the
        // first high byte of a header, a long way from the bug.
        binding.incoming = byteArrayOf(0xFF.toByte())
        val connection = connection()

        assertEquals(255, connection.input.read())
    }

    @Test
    fun `a single byte read at end of stream is minus one`() {
        binding.incoming = ByteArray(0)
        val connection = connection()

        assertEquals(-1, connection.input.read())
    }

    @Test
    fun `a zero length read returns zero without touching the tunnel`() {
        // The relay's copy loop treats a non-positive return as end-of-stream, so a -1 here
        // would end a healthy connection on a call that asked for nothing.
        val connection = connection()

        assertEquals(0, connection.input.read(ByteArray(16), 8, 0))
        assertTrue(binding.reads.isEmpty())
    }

    @Test
    fun `a read whose window does not fit the buffer is rejected before the tunnel`() {
        val connection = connection()

        assertFailsWith<IndexOutOfBoundsException> { connection.input.read(ByteArray(16), 8, 9) }
        assertTrue(binding.reads.isEmpty())
    }

    @Test
    fun `a read with a negative offset is rejected before the tunnel`() {
        val connection = connection()

        assertFailsWith<IndexOutOfBoundsException> { connection.input.read(ByteArray(16), -1, 4) }
        assertTrue(binding.reads.isEmpty())
    }

    // ------------------------------------------------------------------ writing

    @Test
    fun `write hands the caller's own buffer and window to the tunnel`() {
        val connection = connection()
        val buffer = ByteArray(64) { it.toByte() }

        connection.output.write(buffer, 8, 20)

        val window = binding.writes.single()
        assertSame(buffer, window.buffer)
        assertEquals(8, window.offset)
        assertEquals(20, window.length)
    }

    @Test
    fun `a short write is retried until every byte is accepted`() {
        // A Go `net.Conn` may accept part of a buffer and report no error. Treating that as
        // a completed write drops bytes out of the middle of a media stream, which surfaces
        // as a corrupt frame nowhere near this code.
        binding.writeChunk = 1
        val connection = connection()
        val buffer = ByteArray(5) { (it + 1).toByte() }

        connection.output.write(buffer, 0, 5)

        assertEquals(5, binding.writes.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), binding.received.toList())
        // Each retry must advance the offset, or the loop would resend the first byte forever.
        assertEquals(listOf(0, 1, 2, 3, 4), binding.writes.map { it.offset })
    }

    @Test
    fun `a write the tunnel refuses throws IOException`() {
        binding.writeChunk = 0
        binding.lastErrorValue = "write: broken pipe"
        val connection = connection()

        val failure = assertFailsWith<IOException> { connection.output.write(ByteArray(4), 0, 4) }

        assertTrue(failure.message.orEmpty().contains("broken pipe"))
    }

    @Test
    fun `writing a single byte`() {
        val connection = connection()

        connection.output.write(0x41)

        assertEquals(1, binding.writes.single().length)
        assertEquals(listOf<Byte>(0x41), binding.received.toList())
    }

    @Test
    fun `a zero length write does not touch the tunnel`() {
        val connection = connection()

        connection.output.write(ByteArray(16), 4, 0)

        assertTrue(binding.writes.isEmpty())
    }

    @Test
    fun `a write whose window does not fit the buffer is rejected before the tunnel`() {
        val connection = connection()

        assertFailsWith<IndexOutOfBoundsException> { connection.output.write(ByteArray(16), 8, 9) }
        assertTrue(binding.writes.isEmpty())
    }

    @Test
    fun `flush does not close the connection`() {
        // The relay's copy loop flushes at the end of every direction, so a flush that tore
        // anything down would break a transfer that had just completed correctly.
        val connection = connection()

        connection.output.flush()

        assertTrue(binding.closed.isEmpty())
    }

    // -------------------------------------------------------------------- close

    @Test
    fun `closing the connection closes its tunnel handle`() {
        val connection = connection(handle = 7L)

        connection.close()

        assertEquals(listOf(7L), binding.closed)
    }

    @Test
    fun `closing either stream closes the one shared handle`() {
        // Both streams wrap a single Go `net.Conn`, so a close from either end has to reach
        // the same handle. Closing each independently would leave the other half of the
        // relay's pump parked on a read nothing will satisfy.
        val connection = connection(handle = 3L)

        connection.input.close()

        assertEquals(listOf(3L), binding.closed)
    }

    // ------------------------------------------------------- tunnel-first dial

    @Test
    fun `a carried host goes through the tunnel when it can`() {
        val dialer = PreferTunnelDialer(tunnel = OverlayWgDialer(binding), direct = FakeRecordingDialer())

        dialer.dial("bss-tor.tracemonkey.org", 443)

        assertEquals(listOf("bss-tor.tracemonkey.org" to 443), binding.dialled)
    }

    @Test
    fun `a carried host falls back to direct when the tunnel refuses`() {
        // ⚠️ *Not* a LAN safety net — the tunnel carries the LAN too since 2026-10-05, and
        // hairpin is off, so a direct dial from the LAN reaches nothing. What this covers is
        // the window before the ladder has converged, and the off-LAN case where the public
        // edge does answer. Recorded because the comment here used to claim the opposite.
        val direct = FakeRecordingDialer()
        binding.dialFailure = IOException("tunnel is not up")

        PreferTunnelDialer(tunnel = OverlayWgDialer(binding), direct = direct)
            .dial("bss-tor.tracemonkey.org", 443)

        assertEquals(listOf("bss-tor.tracemonkey.org" to 443), direct.dialled)
    }

    @Test
    fun `the fallback is decided per connection and does not latch`() {
        // A dialler that remembered "the tunnel failed once" and stopped trying would keep
        // reaching the public edge for the rest of the session, long after the handshake
        // completed — which is exactly the window a tunnel comes up in.
        val direct = FakeRecordingDialer()
        val dialer = PreferTunnelDialer(tunnel = OverlayWgDialer(binding), direct = direct)

        binding.dialFailure = IOException("tunnel is not up")
        dialer.dial("bss-tor.tracemonkey.org", 443)
        binding.dialFailure = null
        dialer.dial("bss-tor.tracemonkey.org", 443)

        assertEquals(1, direct.dialled.size)
        assertEquals(2, binding.dialled.size)
    }

    @Test
    fun `a direct dial failure still propagates`() {
        binding.dialFailure = IOException("tunnel is not up")
        val direct = FakeRecordingDialer().apply { failure = IOException("connection refused") }

        assertFailsWith<IOException> {
            PreferTunnelDialer(tunnel = OverlayWgDialer(binding), direct = direct)
                .dial("bss-tor.tracemonkey.org", 443)
        }
    }

    // ------------------------------------------------------ deferred tunnel lookup

    @Test
    fun `a deferred dialler with no tunnel throws rather than reaching the network`() {
        // ⚠️ The throw is the contract, and a silent fallback here would hide the composed
        // policy one layer up — see the class doc. Reaching the network instead would be
        // worse still: it would bypass the route the allow-set just chose.
        assertFailsWith<IOException> {
            DeferredTunnelDialer { null }.dial("bss-tor.tracemonkey.org", 443)
        }
    }

    @Test
    fun `a deferred dialler dials once the tunnel appears`() {
        // The production shape: the relay binds at start-up, the tunnel comes up seconds
        // later, and the same dialler instance has to work in both worlds. A dialler that
        // captured `null` at start-up would keep falling back for the process's life.
        var available: OverlayDialer? = null
        val dialer = DeferredTunnelDialer { available }

        assertFailsWith<IOException> { dialer.dial("bsf.tracemonkey.org", 443) }

        available = OverlayWgDialer(binding)
        dialer.dial("bsf.tracemonkey.org", 443)

        assertEquals(listOf("bsf.tracemonkey.org" to 443), binding.dialled)
    }

    @Test
    fun `the production pair falls back before the tunnel exists and carries after`() {
        // The wiring the relay actually gets, exercised across the transition it exists for:
        // `PreferTunnelDialer(DeferredTunnelDialer { … }, DirectDialer)`. Both halves are
        // tested alone above, and this is the seam between them — the one place where a
        // deferred throw has to be caught by the fallback rather than reaching the relay.
        var available: OverlayDialer? = null
        val direct = FakeRecordingDialer()
        val dialer = PreferTunnelDialer(tunnel = DeferredTunnelDialer { available }, direct = direct)

        dialer.dial("bss-tor.tracemonkey.org", 443)
        available = OverlayWgDialer(binding)
        dialer.dial("bss-tor.tracemonkey.org", 443)

        assertEquals(1, direct.dialled.size, "the pre-tunnel dial must have gone direct")
        assertEquals(1, binding.dialled.size, "the post-tunnel dial must have been carried")
    }
}

/** One call's arguments, recorded so a test can assert what actually crossed the seam. */
private data class Window(
    val id: Long,
    val buffer: ByteArray,
    val offset: Int,
    val length: Int,
)

/**
 * A scripted stand-in for the AAR.
 *
 * It records rather than simulates: the assertions that matter are about *what the dialler
 * asked for*, and a fake that helpfully re-implemented the tunnel's behaviour would let a
 * wrong window pass unnoticed.
 */
private class FakeWgBinding : OverlayWgBinding {

    val dialled = mutableListOf<Pair<String, Int>>()
    val reads = mutableListOf<Window>()
    val writes = mutableListOf<Window>()
    val closed = mutableListOf<Long>()

    /** Bytes a read serves, in order, before reporting end of stream. */
    var incoming: ByteArray = ByteArray(0)

    /** How many bytes a write call accepts. The default is all of them. */
    var writeChunk: Int = Int.MAX_VALUE

    var dialFailure: Throwable? = null
    var lastErrorValue: String = ""

    /** Everything the tunnel was actually handed, so a short write cannot lose a byte. */
    val received = mutableListOf<Byte>()

    /** The handle the next [dial] returns, so a close test can name the one it expects. */
    var nextHandle = 1L

    private var incomingAt = 0

    override fun dial(host: String, port: Int): Long {
        dialled += host to port
        dialFailure?.let { throw it }
        return nextHandle++
    }

    override fun read(id: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        reads += Window(id, buffer, offset, length)
        if (incomingAt >= incoming.size) return -1
        val taken = minOf(length, incoming.size - incomingAt)
        incoming.copyInto(buffer, offset, incomingAt, incomingAt + taken)
        incomingAt += taken
        return taken
    }

    override fun write(id: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        writes += Window(id, buffer, offset, length)
        val accepted = minOf(length, writeChunk)
        repeat(accepted) { received += buffer[offset + it] }
        return accepted
    }

    override fun close(id: Long) {
        closed += id
    }

    override fun lastError(): String = lastErrorValue

    // Unused by these tests; present because the seam mirrors the AAR's whole surface.

    override fun up(
        privateKeyHex: String,
        peerPublicKeyHex: String,
        endpoint: String,
        localCidr: String,
        allowedIps: String,
        dns: String,
        mtu: Int,
        keepalive: Int,
    ) = error("not used")

    override fun down() = error("not used")

    override fun status(): String = error("not used")

    override fun generateKeypair(): OverlayWgKeypair = error("not used")
}

/** Records what the fallback was asked to dial, so the tunnel-first choice is observable. */
private class FakeRecordingDialer : OverlayDialer {

    val dialled = mutableListOf<Pair<String, Int>>()
    var failure: Throwable? = null

    override fun dial(host: String, port: Int): OverlayConnection {
        dialled += host to port
        failure?.let { throw it }
        return object : OverlayConnection {
            override val input = java.io.ByteArrayInputStream(ByteArray(0))
            override val output = java.io.ByteArrayOutputStream()
            override fun close() = Unit
        }
    }
}
