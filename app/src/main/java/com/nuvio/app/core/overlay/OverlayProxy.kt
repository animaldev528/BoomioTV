package com.nuvio.app.core.overlay

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * The app-side half of the relay: what every HTTP engine is handed so its traffic can reach
 * the tunnel.
 *
 * **Why a seam and not a global.** A userspace tunnel captures nothing — there is no kernel
 * route and no DNS hook — so "route the app through the tunnel" is not a thing that can be
 * switched on in one place. Each engine has to be told, and this object is the one thing
 * they are all told. See `OverlayRelay`'s doc for why the seam is CONNECT and not SOCKS.
 *
 * **Fail-open, and that is the whole safety story.** Every entry point here reads the relay
 * *live* and returns "dial direct" the moment it is not up. `BOOMIO_OVERLAY_ADDR` is blank
 * by default, the relay never starts, [selector] answers [Proxy.NO_PROXY] for everything,
 * and a build with the overlay unconfigured is byte-for-byte the build that had no overlay
 * at all. Nothing here can make a request fail that would otherwise have succeeded — the
 * worst it can do is fail to help.
 *
 * ⚠️ **No entry point here may be captured into a `val`.** The relay starts once, from
 * `onCreate`, but clients are built lazily throughout the app. Every method reads the relay
 * at the moment it is needed. See [OverlayRelay.proxy].
 */
internal object OverlayProxy {

    private const val TAG = "OverlayProxy"

    /** The header the relay reads, and the scheme it accepts from our own clients. */
    private const val PROXY_AUTH_HEADER = "Proxy-Authorization"
    private const val SCHEME = "Bearer"

    /**
     * Loopback by literal, never `InetAddress.getLoopbackAddress()` — the relay binds the
     * v4 literal (`OverlayRelay.LOOPBACK`), and asking the stack for "the" loopback address
     * is a coin-flip between that and `::1` that would silently hand a client a port
     * nothing is listening on.
     */
    private const val LOOPBACK = "127.0.0.1"

    private val DIRECT = listOf(Proxy.NO_PROXY)

    /**
     * Hand to `OkHttpClient.Builder.proxySelector`.
     *
     * OkHttp consults this once per **route**, not once per request, so a connection that is
     * already pooled keeps the route it was opened with. A relay that comes up mid-session
     * is therefore picked up by the next new connection rather than retroactively — which is
     * the right behaviour for a fail-open seam, and the reason a client that is already
     * mid-stream is never yanked off its socket.
     */
    val selector: ProxySelector = RelayProxySelector()

    /**
     * Hand to `OkHttpClient.Builder.proxyAuthenticator`.
     *
     * Thin on purpose: the decision lives in [relayProxyAuthorization], which is pure and
     * therefore testable without standing up a connection or hand-building a `Route`. See
     * that function for why the decision is not simply "a 407 arrived, so send the secret".
     */
    val authenticator: Authenticator = Authenticator { route, response ->
        val proxy = route?.proxy
        val value = relayProxyAuthorization(
            proxyType = proxy?.type(),
            proxyAddress = proxy?.address() as? InetSocketAddress,
            alreadySent = response.request.header(PROXY_AUTH_HEADER) != null,
            endpoint = OverlayRelay.proxy,
        )
        value?.let { response.request.newBuilder().header(PROXY_AUTH_HEADER, it).build() }
    }

    /**
     * The `http-proxy` value for libmpv, or null when the relay is not up.
     *
     * Unlike OkHttp, libmpv takes the credentials **in the URL** — there is no separate
     * option for them — so this is [OverlayProxyEndpoint.url] rather than a host and port.
     */
    fun mpvProxyUrl(): String? = OverlayRelay.proxy?.url

    /**
     * True only for the schemes OkHttp will carry through a `CONNECT`.
     *
     * ⚠️ **This is a correctness rule, not a filter.** OkHttp sends `CONNECT` for an
     * `https://` target and *absolute-form* (`GET http://host/path HTTP/1.1`) for an
     * `http://` one — it never tunnels cleartext. [OverlayRelay] answers anything that is
     * not `CONNECT` with **`405 Method Not Allowed`**. So pointing the relay at an `http://`
     * request would convert a request that works today into a hard failure. Answering
     * "direct" for cleartext is what keeps this seam fail-open.
     *
     * OkHttp maps `wss://` to `https` before the selector ever sees it, so WebSockets take
     * the same correct branch without being named here.
     */
    private fun usesConnect(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true)

    /**
     * `select` is called by OkHttp for every new route, on its own dispatcher threads.
     *
     * ⚠️ **Loopback is answered first, before the relay is even consulted.** The relay
     * listens on loopback, so a request aimed at the relay (or at any other in-process
     * server, such as the P2P engine's local stream) would otherwise be handed back to the
     * relay as its own proxy — a connection that CONNECTs to itself and hangs. The
     * exemption is not an optimisation; without it the relay deadlocks on first use.
     */
    private class RelayProxySelector : ProxySelector() {

        override fun select(uri: URI): List<Proxy> {
            val host = uri.host ?: return DIRECT
            if (isLoopbackHost(host)) return DIRECT
            if (!usesConnect(uri)) return DIRECT
            val endpoint = OverlayRelay.proxy ?: return DIRECT
            return listOf(
                Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, endpoint.port))
            )
        }

        /**
         * Called when a connection through a selected proxy fails.
         *
         * Deliberately does nothing but log. OkHttp treats this as advice about the proxy
         * and will try the next one in the list — and there is only ever one — so the only
         * useful outcome here is that the failure is visible rather than silent. A relay
         * that dies without logging looks exactly like a network problem.
         */
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
            Log.w(TAG, "relay connection failed for ${uri.host}: ${ioe.message}")
        }
    }
}

/**
 * Points an `OkHttpClient.Builder` at the overlay relay.
 *
 * One line at each call site, and — more importantly — one place to change if the seam ever
 * does. Applied unconditionally: the selector and the authenticator both answer "nothing"
 * while the relay is down, so there is no configuration flag to get wrong.
 */
internal fun OkHttpClient.Builder.withOverlayProxy(): OkHttpClient.Builder =
    proxySelector(OverlayProxy.selector).proxyAuthenticator(OverlayProxy.authenticator)

/**
 * The `Proxy-Authorization` value to answer a `407` with, or null to stay silent.
 *
 * ⚠️ **This is the security-relevant half of the seam, and its whole point is that it says
 * no.** OkHttp hands every `407` to the client's proxy authenticator, whoever sent it. The
 * naive implementation — "a `407` arrived, so attach the secret" — would replay the relay's
 * per-process secret at any origin server that felt like answering `407`, which is a
 * credential leak triggered by a *remote* party. Hence every check below:
 *
 *  * `proxyType != HTTP` — only an HTTP proxy can be challenged for proxy credentials.
 *  * `proxyAddress` loopback — the relay is the only loopback HTTP proxy this app talks to.
 *  * port equality — a relay that has been restarted owns a different port, so a challenge
 *    naming the old one is not from a relay that is still ours.
 *  * `alreadySent` — the loop guard. Answering the same challenge twice would spin until
 *    OkHttp's follow-up limit, so the presence of the header means "asked and answered".
 *
 * Pure, and deliberately so: this is the part worth testing, and it is testable only
 * because it takes the four facts it needs rather than an OkHttp `Route`.
 */
internal fun relayProxyAuthorization(
    proxyType: Proxy.Type?,
    proxyAddress: InetSocketAddress?,
    alreadySent: Boolean,
    endpoint: OverlayProxyEndpoint?,
): String? = when {
    endpoint == null -> null
    proxyType != Proxy.Type.HTTP -> null
    proxyAddress == null -> null
    proxyAddress.address?.isLoopbackAddress != true -> null
    proxyAddress.port != endpoint.port -> null
    alreadySent -> null
    else -> "Bearer ${endpoint.secret}"
}
