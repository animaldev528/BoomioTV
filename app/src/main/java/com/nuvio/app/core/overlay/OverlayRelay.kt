package com.nuvio.app.core.overlay

import android.util.Log
import com.nuvio.app.features.boomio.BoomioConfig
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "OverlayRelay"

/**
 * What the relay is doing.
 *
 * [Up] carries the port because it is **assigned by the kernel**, not chosen: the relay
 * binds port 0 so it can never collide with something else on the device, which means the
 * port is unknown until the bind returns and has to be handed to whoever wires a client to
 * it.
 */
internal sealed interface RelayState {
    /** Not started, or stopped. The initial state. */
    data object Down : RelayState

    /** Listening on `127.0.0.1:[port]`, ready to accept. */
    data class Up(val port: Int) : RelayState

    /**
     * The relay could not be started. [reason] is for the log and the diagnostic row, not
     * for the user's decision-making — nothing here is actionable by them.
     */
    data class Failed(val reason: String) : RelayState
}

/**
 * The two places a CONNECT can be sent.
 *
 * ⚠️ **They are separate diallers rather than one, because the choice is the whole point.**
 * [relayRouteFor] decides which, per connection, from the live allow-set — so routing stays
 * a property of the *destination*, decided in one place, and neither dialler has to know
 * the policy.
 */
internal class OverlayDialers(
    /**
     * Hosts in the allow-set. In production this is a [PreferTunnelDialer], so "carried"
     * names the *intent* — the route the allow-set selected — rather than a guarantee about
     * which socket the bytes took. It falls back to direct while the tunnel is down.
     */
    val carried: OverlayDialer,
    /** Everything else, and every loopback address. */
    val direct: OverlayDialer,
)

/**
 * The loopback HTTP CONNECT relay.
 *
 * **Why this exists at all.** A userspace WireGuard tunnel captures nothing by itself.
 * There is no interface for the kernel to route through and no DNS hook to install, so
 * every engine that should use the tunnel has to be *told* to, one at a time. libmpv is
 * the one that cannot be told any other way: it resolves inside libcurl's `getaddrinfo`,
 * which no application can intercept, and the shipped mpv AAR has no libcurl of its own
 * to swap. A CONNECT proxy is the single seam all of them already understand.
 *
 * **CONNECT and not SOCKS**, because the mpv AAR ships without libcurl — S-U2 measured
 * that FFmpeg's own HTTP stack honours `http-proxy`, and that is the only proxy protocol
 * it speaks.
 *
 * **The relay is a routing decision, not a gate.** A host outside the allow-set is still
 * dialled, just directly (decided 2026-10-05). Making the allow-set a gate would break
 * every third-party addon, and the app's own catalogue plane is assembled from addons the
 * user installed.
 *
 * **Fail-open.** [start] binds and publishes [RelayState.Up] *before* the accept loop
 * begins, so a caller can read the port and point a client at it with no window in which
 * the port is advertised but unanswered. Nothing here ever blocks a request: if the relay
 * is not up, the caller has no proxy URL and every client behaves as it does today.
 *
 * **Process-scoped, deliberately.** There is no foreground service on this flavor to own a
 * listening socket, so the relay lives and dies with the app process. That is the correct
 * lifetime: the tunnel it serves is also process-scoped, and a proxy outliving its tunnel
 * would only route traffic it can no longer carry.
 */
internal object OverlayRelay {

    /**
     * Loopback by literal address, never `0.0.0.0` and never `getLoopbackAddress()`.
     *
     * `0.0.0.0` would expose an authenticated but *general-purpose* proxy to the whole
     * LAN. `getLoopbackAddress()` is a coin-flip between `127.0.0.1` and `::1` depending on
     * the stack's preference, and a client handed the wrong one connects to nothing —
     * so the address is pinned to the v4 literal that every client here already writes.
     */
    private const val LOOPBACK = "127.0.0.1"

    private const val HEAD_TIMEOUT_MS = 10_000
    private const val COPY_BUFFER_BYTES = 32 * 1024

    /** Backoff for a transient `accept` failure — file-descriptor exhaustion, typically. */
    private const val ACCEPT_RETRY_DELAY_MS = 250L

    private val _state = MutableStateFlow<RelayState>(RelayState.Down)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var handle: OverlayRelayHandle? = null

    /**
     * The running relay's dialable endpoint, or null when it is not up.
     *
     * ⚠️ **Read this per use; never capture it into a `val` at construction time.**
     * [start] is once-per-process and is called from `onCreate`, while the HTTP clients
     * that consume this are built lazily all over the app and some of them are built
     * *before* the discovery ladder has walked anything. A captured copy is a client
     * stuck at `null` for the life of the process — the relay comes up and nothing
     * notices. Same rule as [DeferredTunnelDialer]'s per-dial state lookup.
     */
    val proxy: OverlayProxyEndpoint?
        get() = handle?.let { OverlayProxyEndpoint(it.port, it.secret, it.proxyUrl) }

    @Volatile
    private var lifecycleStarted = false

    /**
     * U3's diallers: **an allow-set host prefers the tunnel, everything else goes direct**.
     *
     * ⚠️ **The tunnel is looked up per dial, not captured here.** [start] runs once per
     * process from `MainActivity.onCreate`, which is before the discovery ladder has
     * walked anything, so a dialler holding a tunnel reference would hold `Down` forever.
     * [DeferredTunnelDialer] reads the state when a connection actually arrives — see its
     * doc for the full reasoning.
     *
     * ⚠️ **This is still fail-open, and deliberately so.** A boomio host CONNECTed before
     * the tunnel is up falls back to the same public address the app would have used
     * anyway, so the relay is transparent rather than broken while the ladder converges.
     * Once it converges, the same host goes through the tunnel with no change to any
     * engine's configuration — which is the property that makes pointing clients at a
     * loopback port worth doing at all.
     */
    private val dialers = OverlayDialers(
        carried = PreferTunnelDialer(
            tunnel = DeferredTunnelDialer { OverlaySession.dialerOrNull() },
            direct = DirectDialer,
            // ⚠️ The policy's WAN toggle, at the only place a direct WAN dial can happen. Read per
            // dial (a lambda, not a captured boolean) so a policy that lands mid-session takes
            // effect on the next connection. A `false` here must never stop the tunnel — see
            // PreferTunnelDialer.
            fallbackAllowed = { mayDialDirectly(DirectPlane.WAN) },
        ),
        direct = DirectDialer,
    )

    /**
     * Starts the relay for this process, once.
     *
     * Inert unless an overlay server is configured, which is the same blank-inert pattern
     * [BoomioConfig] and [OverlayTunnel] follow: an install not running an overlay never
     * opens a listening socket at all.
     *
     * Takes no `Context`, because it needs none — and holding an unused one is how a
     * static object leaks an Activity's context.
     */
    fun initialize() {
        if (lifecycleStarted) return
        lifecycleStarted = true
        ensureStarted()
    }

    /**
     * Binds the listener once there is an overlay address to route for.
     *
     * ⚠️ **Re-checked on every call rather than latched with the rest of [initialize]**,
     * because the address is learned from enrollment and on a fresh install it is still blank
     * when this object initializes — `MainActivity` starts the relay *before*
     * [OverlayEnrollment] on purpose, since enrollment needs the keypair [OverlaySession]
     * creates. A latch would leave the relay unbound for the whole process on precisely the
     * build it exists for, so a device that had just enrolled would reach the overlay with
     * every engine still dialling directly until it was restarted.
     */
    @Synchronized
    private fun ensureStarted() {
        if (handle != null) return
        if (BoomioConfig.overlayServerAddress.isBlank()) return
        start(scope, dialers, ::localServerHosts)
    }

    /**
     * Announces that [BoomioConfig.overlayServerAddress] has just been written, possibly for
     * the first time on this install. See [ensureStarted] for why the check cannot be latched.
     */
    internal fun onServerAddressLearned() {
        ensureStarted()
    }

    /**
     * Binds the loopback listener and publishes [RelayState.Up].
     *
     * Idempotent: a second call returns the handle already running rather than starting a
     * second listener, because `onCreate` can run again after a configuration-forced
     * restart.
     *
     * Returns null when the bind fails, having published [RelayState.Failed]. A null return
     * is not an error the caller must handle — it is the app continuing without a relay.
     */
    @Synchronized
    fun start(
        scope: CoroutineScope,
        dialers: OverlayDialers,
        allowSet: () -> Set<String>,
    ): OverlayRelayHandle? {
        handle?.let { return it }

        val serverSocket = try {
            ServerSocket().apply {
                // No `reuseAddress`: this socket is bound and closed once per process, and
                // SO_REUSEADDR's only effect here would be to let a second relay bind a
                // port a previous one still holds.
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0))
            }
        } catch (t: Throwable) {
            _state.value = RelayState.Failed("Could not bind the loopback relay: ${t.message}")
            Log.w(TAG, "Relay bind failed", t)
            return null
        }

        val secret = newRelaySecret()
        // Per-connection work gets its own supervisor scope, so an unexpected throw while
        // handling one connection cannot take the accept loop down with it — which, for a
        // child of the accept coroutine, is exactly what it would otherwise do.
        val connections = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // ⚠️ `localPort`, not `port`. `ServerSocket` has no `getPort()` at all — that is
        // `Socket` — and this is the only place the kernel-assigned port can be read from.
        val port = serverSocket.localPort

        val acceptJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            acceptLoop(serverSocket, secret, dialers, allowSet, connections)
        }

        val created = OverlayRelayHandle(
            port = port,
            secret = secret,
            proxyUrl = "http://boomio:$secret@$LOOPBACK:$port",
            serverSocket = serverSocket,
            connections = connections,
            acceptJob = acceptJob,
        )
        handle = created

        // ⚠️ Published BEFORE the accept loop starts. A connection that arrives between
        // here and `acceptJob.start()` is not lost — the socket is already bound, so the
        // kernel completes the handshake into the listen backlog and the loop picks it up.
        // The reverse order would advertise a port that answers nothing.
        _state.value = RelayState.Up(port)
        acceptJob.start()

        Log.d(TAG, "Overlay relay up on $LOOPBACK:$port")
        return created
    }

    /** Stops the relay and closes every connection it is carrying. */
    @Synchronized
    fun stop() {
        handle?.stop()
        handle = null
        _state.value = RelayState.Down
    }

    private suspend fun acceptLoop(
        serverSocket: ServerSocket,
        secret: String,
        dialers: OverlayDialers,
        allowSet: () -> Set<String>,
        connections: CoroutineScope,
    ) {
        while (currentCoroutineContext().isActive) {
            val client = try {
                serverSocket.accept()
            } catch (t: Throwable) {
                // `stop()` closes the socket, which lands here — and is the normal exit,
                // not a fault, so it must not be logged as one.
                if (!currentCoroutineContext().isActive) return
                Log.w(TAG, "Relay accept failed; retrying", t)
                delay(ACCEPT_RETRY_DELAY_MS)
                continue
            }
            connections.launch(Dispatchers.IO) {
                handleConnection(client, secret, dialers, allowSet)
            }
        }
    }

    /**
     * Serves one CONNECT.
     *
     * ⚠️ **The order of the checks is the security property.** Authentication is verified
     * *before* anything is resolved or dialled, so an unauthenticated peer cannot use the
     * relay as a scanner, cannot make it open sockets, and cannot learn from response
     * timing whether a host exists. Everything after the auth check is only reachable by a
     * caller that already holds the secret.
     */
    private suspend fun handleConnection(
        client: Socket,
        secret: String,
        dialers: OverlayDialers,
        allowSet: () -> Set<String>,
    ) {
        var upstream: OverlayConnection? = null
        try {
            client.soTimeout = HEAD_TIMEOUT_MS
            // ⚠️ Created ONCE and handed to the pump. Re-wrapping the socket's stream
            // later would abandon whatever this buffer already read past the request head
            // — which for a CONNECT is where the client's TLS ClientHello sits, so the
            // tunnel would come up and then hang.
            val clientIn = BufferedInputStream(client.getInputStream(), 8 * 1024)
            val clientOut = client.getOutputStream()

            val head = readHead(clientIn)
                ?: return refuse(clientOut, "431 Request Header Fields Too Large")
            val request = parseHttpHead(head)
                ?: return refuse(clientOut, "400 Bad Request")

            // ⚠️ AUTH FIRST. See the method doc: this is the relay's only defence.
            if (!isProxyAuthorized(request.headers[PROXY_AUTH_HEADER], secret)) {
                return refuse(clientOut, "407 Proxy Authentication Required")
            }

            if (request.method != "CONNECT") {
                return refuse(clientOut, "405 Method Not Allowed")
            }

            val target = connectTargetOf(request.target)
                ?: return refuse(clientOut, "400 Bad Request")

            // `localPort` is the port the client reached us on, i.e. the relay's own.
            if (isSelfDial(target.host, target.port, client.localPort)) {
                return refuse(clientOut, "421 Misdirected Request")
            }

            val route = relayRouteFor(target.host, allowSet())
            val dialer = when (route) {
                RelayRoute.CARRIED -> dialers.carried
                RelayRoute.DIRECT -> dialers.direct
            }

            upstream = try {
                dialer.dial(target.host, target.port)
            } catch (t: Throwable) {
                Log.d(TAG, "Relay could not reach ${target.host}:${target.port}", t)
                return refuse(clientOut, "502 Bad Gateway")
            }

            // Only now — with a live upstream — is the tunnel established. A 200 followed
            // by silence is indistinguishable to the client from a slow server, so the
            // distinction between "retry" and "hang" is this line's placement.
            client.soTimeout = 0
            clientOut.write(ESTABLISHED_RESPONSE)
            clientOut.flush()

            pump(client, clientIn, clientOut, upstream)
        } catch (t: Throwable) {
            // A client that hangs up mid-stream is the ordinary case, not a fault.
            Log.d(TAG, "Relay connection ended", t)
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    /**
     * Copies bytes until either direction ends, then tears the pair down.
     *
     * Both directions run at once and the first to finish ends the tunnel. That is right
     * for CONNECT: the client owns the lifetime, and a half-open tunnel whose other
     * direction is parked on a read nothing will ever satisfy is a leaked socket and a
     * leaked thread, not a partial success.
     */
    private suspend fun pump(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        upstream: OverlayConnection,
    ) {
        // A block body, not `= coroutineScope { … }`: the last statement is a `runCatching`
        // whose `Result<Unit>` would otherwise become this function's inferred return type,
        // and callers would be handed a `Result` nobody reads.
        coroutineScope {
            val upstreamIn = BufferedInputStream(upstream.input, 8 * 1024)
            val upstreamOut = upstream.output
            val finished = CompletableDeferred<Unit>()

            launch(Dispatchers.IO) {
                runCatching { copyStream(clientIn, upstreamOut) }
                finished.complete(Unit)
            }
            launch(Dispatchers.IO) {
                runCatching { copyStream(upstreamIn, clientOut) }
                finished.complete(Unit)
            }

            finished.await()

            // Closing both ends is what unblocks the direction still parked on a read; it
            // throws into that read, which the `runCatching` above absorbs. Closing only
            // the upstream would leave the client-side read blocking forever.
            runCatching { client.close() }
            runCatching { upstream.close() }
        }
    }

    /**
     * Copies [input] to [output] until EOF.
     *
     * ⚠️ **Writes go straight through, with no output buffer.** A `BufferedOutputStream`
     * here would hold a small request until 8 KB accumulated or the stream closed — and on
     * a persistent connection carrying request/response traffic, neither ever happens, so
     * the request would simply never be sent. The read side is buffered by the caller
     * instead, where a full buffer costs latency measured in microseconds.
     */
    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            // `read` returning 0 with a non-empty buffer is a contract violation, and
            // treating it as "retry" would spin a core. Ending the direction is safe: the
            // peer is closed either way.
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    /** Sends a bare status line and closes; the caller's `finally` closes the socket. */
    private fun refuse(output: OutputStream, status: String) {
        runCatching {
            output.write(
                ("HTTP/1.1 $status\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: 0\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            output.flush()
        }
    }
}

/**
 * The relay as its clients see it: where to dial, and what to authenticate with.
 *
 * ⚠️ **Deliberately not [OverlayRelayHandle].** Handing the handle out would put `stop()`
 * in reach of every client that only wanted a proxy URL, and calling it there would close
 * the socket while [OverlayRelay]'s own `handle` stayed non-null — a relay that reports
 * `Up`, answers nothing, and can never be started again. This type is the whole of what a
 * client is entitled to.
 */
internal data class OverlayProxyEndpoint(
    /** The kernel-assigned loopback port. */
    val port: Int,

    /** The per-process secret, hex. */
    val secret: String,

    /**
     * `http://user:secret@127.0.0.1:port` — the shape libmpv's `http-proxy` needs.
     *
     * libmpv has no separate credential option, so the credentials must ride in the URL.
     * That is why [newRelaySecret] emits hex: nothing to percent-escape and no padding.
     */
    val url: String,
)

/**
 * A running relay.
 *
 * Holds everything needed to point a client at it ([proxyUrl]) and to take it back down
 * ([stop]). The secret is exposed here because it is not a secret *from the app* — it is
 * the app's own credential for its own relay, and the only thing it must not do is reach
 * another app.
 */
internal class OverlayRelayHandle internal constructor(
    /** The kernel-assigned loopback port. */
    val port: Int,

    /** The per-process secret, hex. */
    val secret: String,

    /**
     * `http://user:secret@127.0.0.1:port` — the shape libmpv's `http-proxy` needs.
     *
     * libmpv has no separate credential option, so the credentials must ride in the URL.
     * That is why [newRelaySecret] emits hex: nothing to percent-escape and no padding.
     */
    val proxyUrl: String,

    private val serverSocket: ServerSocket,
    private val connections: CoroutineScope,
    private val acceptJob: Job,
) {
    /** Stops accepting, drops every live connection, and releases the port. */
    fun stop() {
        // The close is what breaks the blocking `accept()`; cancelling the job alone
        // would leave that thread parked until the next connection arrived.
        runCatching { serverSocket.close() }
        acceptJob.cancel()
        connections.cancel()
    }
}

/**
 * Reads a request head, up to and including the terminating blank line.
 *
 * Returns the head **without** the terminator, or null when the client sent more than
 * [MAX_HEAD_BYTES] without ending it or closed first.
 *
 * ⚠️ **Byte-at-a-time, and it stops on the exact byte.** Reading in blocks would need
 * somewhere to stash whatever came after `\r\n\r\n`, and for a CONNECT that is the client's
 * TLS ClientHello — dropping it produces a tunnel that comes up and then hangs, which is
 * the worst possible failure to debug. Reading one byte at a time past a buffer that is
 * already in memory costs nothing and cannot lose a byte.
 */
private fun readHead(input: InputStream): String? {
    val collected = ByteArrayOutputStream(1024)
    var matched = 0
    while (collected.size() < MAX_HEAD_BYTES) {
        val byte = input.read()
        if (byte < 0) return null
        collected.write(byte)
        matched = when {
            byte == CR && matched <= 2 -> matched + 1
            byte == LF && (matched == 1 || matched == 3) -> matched + 1
            byte == CR -> 1
            else -> 0
        }
        if (matched == 4) {
            val bytes = collected.toByteArray()
            return String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1)
        }
    }
    return null
}

private const val CR = '\r'.code
private const val LF = '\n'.code

/**
 * Enough for a request line, a `Proxy-Authorization`, and the usual client headers.
 *
 * ⚠️ File scope, not a member of [OverlayRelay]: [readHead] is a top-level function, and a
 * `private const` declared inside the object is not in its scope — the compiler reports
 * that as an unresolved reference rather than as a visibility problem, which sends you
 * looking for a typo.
 */
private const val MAX_HEAD_BYTES = 16 * 1024
