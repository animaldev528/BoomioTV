package com.nuvio.app.core.overlay

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "OverlayWgDialer"

/**
 * The **carried** route: [OverlayDialer] backed by the userspace WireGuard tunnel.
 *
 * This is the other half of [DirectDialer], and which of the two a given CONNECT uses is
 * decided in one place — [relayRouteFor], from the live allow-set. Neither dialler knows
 * the policy, and neither needs to.
 *
 * ⚠️ **This dialler throws when the tunnel is down; it does not fall back.** That is
 * deliberate and it is the correct split of responsibility: the tunnel's state is a
 * property of the tunnel, and the decision to reach a host another way is a routing
 * decision that belongs with the routing. [PreferTunnelDialer] is where those two meet,
 * and putting the fallback here instead would make it impossible to test "the tunnel
 * refused this" separately from "we chose not to use the tunnel".
 */
internal class OverlayWgDialer(private val binding: OverlayWgBinding) : OverlayDialer {

    /**
     * ⚠️ The failure propagates by design. [OverlayRelay] replies `200 Connection
     * Established` only after `dial` returns, precisely so that a tunnel that cannot
     * connect is a `502` rather than a client left waiting on a socket nothing will ever
     * answer.
     */
    override fun dial(host: String, port: Int): OverlayConnection =
        WgConnection(binding, binding.dial(host, port))
}

/**
 * Tries the tunnel, and reaches the host directly if it cannot.
 *
 * **Why this exists rather than being folded into [OverlayWgDialer].** The relay's
 * allow-set selects *routing, not reachability* — so a boomio host is always dialled, and
 * the only question is by which route. A carried host has to be dialled even when the
 * tunnel cannot take it, or the relay would break the one case it currently serves: it is
 * only useful once something is pointed at it, and the first thing pointed at it is a debug
 * build whose tunnel may not be up at all.
 *
 * ⚠️ **The LAN caveat that used to be here is gone, and the correction matters.** This doc
 * said the tunnel "is not brought up at all" on the LAN, so a carried host there *must* go
 * direct. That was true of the tier split and was superseded on 2026-10-05 — *"everything
 * will be vpn, even on lan"*. The tunnel now carries the LAN too, so a direct fallback on
 * the LAN reaches **nothing** (hairpin is off, so the public address does not come back in).
 * The fallback is therefore not a LAN safety net; it is what keeps a host reachable from
 * off-LAN, where the public edge does answer.
 *
 * ⚠️ **Open question, deliberately not decided here:** §10.7 chose "no LAN fallback — a LAN
 * tunnel failure is a hard failure", and a direct fallback is in tension with that. It is
 * left as-is because changing it changes *routing policy*, which is the owner's call, not a
 * thing to slip into a wiring commit. Off-LAN the fallback is a real degradation to the
 * public path (privacy, not function); on-LAN it fails visibly anyway.
 */
internal class PreferTunnelDialer(
    private val tunnel: OverlayDialer,
    private val direct: OverlayDialer,
    /**
     * Whether the direct fallback below the tunnel is permitted *right now*.
     *
     * ⚠️ **This is where the security policy's WAN toggle is consulted**, and it is the only WAN
     * seam there is: a carried host that the tunnel cannot take is exactly the "direct WAN playback"
     * the toggle forbids. The production relay passes
     * `{ mayDialDirectly(DirectPlane.WAN) }`; the default here is permissive so a test of the
     * *mechanism* (does a refusal fall back at all?) is not silently rewritten by whatever policy
     * happens to be in force. Read per dial, so a policy that lands mid-session is honoured by the
     * next connection — the same rule [DeferredTunnelDialer] follows for the tunnel itself.
     *
     * ⚠️ **A `false` here is a hard failure, not a torn-down tunnel.** It decides one dial; nothing
     * in this file starts, stops, or reconfigures the tunnel, and a WebSocket already up is never
     * dropped by it. The owner's toggles change *routing*, never tunnel lifecycle.
     */
    private val fallbackAllowed: () -> Boolean = { true },
) : OverlayDialer {

    override fun dial(host: String, port: Int): OverlayConnection = try {
        tunnel.dial(host, port)
    } catch (t: Throwable) {
        if (!fallbackAllowed()) {
            // The policy forbids a direct dial on this plane. Rethrow rather than reach the public
            // edge: the relay turns this into a 502, which is honest — the route simply is not
            // allowed right now, and the tunnel is what must come up.
            Log.d(TAG, "Tunnel could not reach $host:$port and a direct dial is not permitted", t)
            throw t
        }
        Log.d(TAG, "Tunnel could not reach $host:$port; dialling direct", t)
        direct.dial(host, port)
    }
}

/**
 * The tunnel, looked up **at dial time** instead of being handed over at start-up.
 *
 * **Why the indirection is load-bearing.** [OverlayRelay.start] is once-per-process — it
 * returns the running handle rather than rebinding its diallers — and it runs from
 * `MainActivity.onCreate`, which is *before* the discovery ladder has walked anything. So a
 * dialler that captured the tunnel at start-up would capture [TunnelState.Down], and the
 * relay would dial direct for the rest of the process's life no matter how well the tunnel
 * came up afterwards. The supplier defers the question to the moment it has an answer.
 *
 * ⚠️ **It throws, rather than falling back.** That looks backwards next to
 * [PreferTunnelDialer], which does the opposite — but the two answer different questions.
 * This one says only "there is no tunnel right now"; *what to do about that* is routing, and
 * routing is [PreferTunnelDialer]'s job, one layer up. If this fell back on its own, the
 * composed policy would be unreachable and a test could no longer tell "the tunnel is down"
 * apart from "we chose direct".
 */
internal class DeferredTunnelDialer(
    private val tunnel: () -> OverlayDialer?,
) : OverlayDialer {

    override fun dial(host: String, port: Int): OverlayConnection =
        tunnel()?.dial(host, port)
            ?: throw IOException("The overlay tunnel is not up; $host:$port was not dialled")
}

/**
 * One connection carried by the tunnel, identified by the handle Go handed back.
 *
 * Both streams share the handle because they share the one underlying `net.Conn` — closing
 * either closes the connection, which is also how [SocketConnection] behaves.
 */
private class WgConnection(
    // `val`, not a bare constructor parameter: `close()` is a member function, and a plain
    // parameter is only in scope for property initializers and `init` — so the streams
    // would take it and the close would not.
    private val binding: OverlayWgBinding,
    private val id: Long,
) : OverlayConnection {

    override val input: InputStream = WgInputStream(binding, id)
    override val output: OutputStream = WgOutputStream(binding, id)

    override fun close() {
        binding.close(id)
    }
}

/**
 * [InputStream] over the tunnel.
 *
 * Near-transparent, and the one thing it does translate is worth naming: the Go side
 * returns **`-1` for a finished stream**, which is the same sentinel
 * `java.io.InputStream.read` already defines, so nothing is converted. That was the point
 * of giving the Go functions no `error` return — see [OverlayWgBinding].
 */
private class WgInputStream(
    private val binding: OverlayWgBinding,
    private val id: Long,
) : InputStream() {

    /** Reused, because [read] without a buffer is called per byte by [readHead]. */
    private val single = ByteArray(1)

    override fun read(): Int {
        val read = read(single, 0, 1)
        // `and 0xFF` because a Java `byte` is signed and `InputStream.read()` is specified
        // to return 0..255. Without it a byte of 0x80 would come back as -128 — which is
        // also `read`'s own end-of-stream sentinel, so the stream would end mid-header.
        return if (read <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkWindow(off, len, b.size)
        // ⚠️ A zero-length read must return 0, not -1. The relay's copy loop treats a
        // non-positive return as end-of-stream, so returning -1 here would end a healthy
        // connection on a call that asked for nothing.
        if (len == 0) return 0
        return binding.read(id, b, off, len)
    }

    override fun close() {
        binding.close(id)
    }
}

/**
 * [OutputStream] over the tunnel.
 *
 * ⚠️ **The loop is the point.** A Go `net.Conn` may accept part of a buffer and report no
 * error, so a single `write` returning a short count is a success, not a failure — and the
 * contract of `OutputStream.write` is that it either writes all of it or throws. Collapsing
 * those two would drop bytes out of the middle of a media stream, which shows up as a
 * corrupt frame a long way from the bug.
 */
private class WgOutputStream(
    private val binding: OverlayWgBinding,
    private val id: Long,
) : OutputStream() {

    private val single = ByteArray(1)

    override fun write(b: Int) {
        single[0] = b.toByte()
        write(single, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkWindow(off, len, b.size)
        if (len == 0) return
        var written = 0
        while (written < len) {
            val accepted = binding.write(id, b, off + written, len - written)
            if (accepted <= 0) {
                // The only place in this file that raises, and it has to: `OutputStream`
                // defines a failed write as an `IOException`, and the relay's copy loop
                // relies on the throw to end the direction rather than spinning.
                throw IOException("Overlay tunnel write failed: ${binding.lastError()}")
            }
            written += accepted
        }
    }

    /**
     * Does nothing, and must keep doing nothing.
     *
     * Bytes handed to [write] are already in Go. ⚠️ This is called by the relay's copy
     * loop at the end of every direction, so a `flush` that closed or reset anything would
     * tear down a connection that had just finished transferring correctly.
     */
    override fun flush() = Unit

    override fun close() {
        binding.close(id)
    }
}

/**
 * The argument contract `java.io.InputStream` and `java.io.OutputStream` both specify.
 *
 * Worth enforcing rather than clamping: the relay hands these methods a 32 KB buffer and a
 * count it read, and a caller with an off-by-one would otherwise have its window silently
 * nudged into a read or write of the wrong region — on a byte stream carrying media, that
 * is corruption rather than a crash.
 */
private fun checkWindow(off: Int, len: Int, size: Int) {
    if (off < 0 || len < 0 || len > size - off) {
        throw IndexOutOfBoundsException("offset=$off length=$len size=$size")
    }
}
