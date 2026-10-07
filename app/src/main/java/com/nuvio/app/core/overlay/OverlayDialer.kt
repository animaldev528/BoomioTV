package com.nuvio.app.core.overlay

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A bidirectional byte stream to one `host:port`.
 *
 * ⚠️ **The implementation plan specifies this seam as `java.net.Socket`, and it is
 * deliberately not one.** That plan was written before anyone had tried to feed it from
 * the tunnel, and the plan itself invites the correction: *"if integrating the tunnel
 * requires changing the relay's logic, the seam was drawn in the wrong place and that is
 * worth fixing before proceeding."*
 *
 * The reason is concrete. [OverlayDialer] has exactly two implementations: a plain
 * `Socket` for hosts dialled direct, and a gomobile-backed one that returns a Go
 * `net.Conn` from `tnet.DialContextTCPAddrPort`. `net.Conn` crosses the gomobile boundary
 * as an interface with `read`/`write`/`close` — it is not, and cannot be made, a
 * `java.net.Socket`. Keeping `Socket` in the signature would have forced the tunnel behind
 * a fake `Socket` subclass overriding `getInputStream`/`getOutputStream`, which is a
 * fragile trick that depends on Android's `Socket` internals staying overridable.
 *
 * The relay only ever needs two streams, so this is the honest shape of what it uses.
 */
internal interface OverlayConnection : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * Resolves a `host:port` into something the relay can pump bytes through.
 *
 * ⚠️ **Implementations connect eagerly and throw on failure.** The relay replies
 * `200 Connection Established` only after `dial` returns, because a client that is told
 * the tunnel is up and then gets silence has no way to distinguish that from a slow
 * server — which is the difference between a retry and a hang.
 */
internal interface OverlayDialer {
    fun dial(host: String, port: Int): OverlayConnection
}

/**
 * The direct dial: an ordinary socket to the public internet.
 *
 * This is the route for every host *not* in the overlay's allow-set, and for loopback.
 * The allow-set selects **routing, not reachability** (decided 2026-10-05) — a non-boomio
 * host is still dialled, just not through the tunnel.
 */
internal object DirectDialer : OverlayDialer {

    private const val CONNECT_TIMEOUT_MS = 10_000

    override fun dial(host: String, port: Int): OverlayConnection {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        return SocketConnection(socket)
    }
}

/** Wraps a connected [Socket] as an [OverlayConnection]. */
private class SocketConnection(private val socket: Socket) : OverlayConnection {
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
    override fun close() = socket.close()
}
