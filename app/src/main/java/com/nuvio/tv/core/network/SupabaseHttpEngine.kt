package com.nuvio.tv.core.network

import com.nuvio.app.core.overlay.withOverlayProxy
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * The HTTP engine the Supabase client is built on.
 *
 * ⚠️ **This is not cosmetic, and it is the difference between working at home and working
 * anywhere else.** `provideSupabaseClient` is the app's spine — auth, profiles, the
 * catalogue, watch progress and Storage all ride this one client, and supabase-kt builds its
 * own `HttpClient` internally unless it is handed one. Left on the platform default, this
 * client is the one engine in the app that no overlay seam reaches.
 *
 * At home that is invisible and the reason is worth stating, because it is what makes the
 * bug look like a server fault: on the LAN [com.nuvio.app.core.overlay.OverlayPinRegistry]
 * repoints `*.tracemonkey.org` at the LAN address *in DNS*, and DNS applies to every client
 * whether or not it was told about the overlay. So the unpinned backbone still lands on the
 * right box.
 *
 * Off the LAN there is no pin, and the **only** route into the tunnel is the loopback CONNECT
 * relay — which a client has to be explicitly pointed at (see `OverlayProxy`: a userspace
 * tunnel captures nothing, so there is no global switch). An engine nobody told keeps
 * resolving `nuvioserver.tracemonkey.org` publicly, reaches the house WAN address, and finds
 * no 443 forward there. To the user that reads as "it can't find the server", and it reads
 * that way while pairing and the tunnel are both up and healthy — because those ride the
 * provisioning channel on TCP 51820, not HTTP.
 *
 * Mobile reached the same conclusion and this mirrors its `SupabaseHttpPlatform.kt`; the port
 * to the TV dropped it, which is why the TV failed where the phone did not.
 *
 * ⚠️ **`withOverlayProxy()` is safe on a `@Singleton` client built before the relay starts.**
 * It does not capture the relay: `OverlayProxy.selector` reads `OverlayRelay.proxy` on every
 * route and answers `Proxy.NO_PROXY` while the relay is down, so this fails open — a build
 * with no overlay configured behaves exactly as it did before this file existed.
 *
 * supabase-kt exposes `httpEngine` on its builder, so covering this needs no upstream change.
 * The engine's own defaults are preserved: this is OkHttp on Android either way, and only the
 * DNS ordering and the proxy seam are added.
 */
internal fun createSupabaseHttpEngine(): HttpClientEngine =
    OkHttp.create { config { dns(IPv4FirstDns()).withOverlayProxy() } }
