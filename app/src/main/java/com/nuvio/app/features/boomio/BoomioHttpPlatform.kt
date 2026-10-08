package com.nuvio.app.features.boomio

import com.nuvio.app.core.mtls.withClientCertificate
import com.nuvio.app.core.overlay.withOverlayProxy
import com.nuvio.tv.core.network.IPv4FirstDns
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/**
 * TV compat layer — **not** part of the overlay port.
 *
 * Mobile declares this as an `expect`/`actual` pair in `features/boomio/BoomioHttpPlatform.kt`
 * (the module is KMP; the TV is not). Two files outside the overlay package need it and cannot
 * take a platform default:
 *
 * - [com.nuvio.app.core.mtls.MtlsRegistration] posts this device's certificate to
 *   `POST /api/overlay/cert`, and the certificate has to *be* on the connection for the edge's
 *   `client_auth` to accept it.
 * - [com.nuvio.app.core.overlay.SecurityPolicyRefresh] pulls the security policy, and that route
 *   is behind the same mTLS gate once the flag is on.
 *
 * It exists only to keep the ported files byte-identical to mobile's — delete it when the overlay
 * is deliberately renamed and this becomes a real app-level client factory.
 *
 * ## Why it is not the app's ordinary client
 *
 * The TV's own networking is OkHttp/Retrofit built in `tv/core/di/NetworkModule.kt`, and that
 * client carries `withOverlayProxy()` but **not** a client certificate. This one carries both,
 * because registration is the one call that must present a certificate on a host whose
 * allow-list does not yet contain this device: the certificate is what is being registered, so
 * it cannot be a precondition for reaching the route.
 *
 * ⚠️ **`withClientCertificate()` is inert until a certificate exists.** It resolves per
 * *connection*, not per client, so the client may be built before the first registration and
 * still present the certificate on the next call — which is exactly the order `MtlsRegistration`
 * needs, since it builds this client to perform the registration that installs the certificate.
 *
 * ⚠️ **Deliberately the TV's own [IPv4FirstDns], not a copy of mobile's.** Mobile's carries the
 * overlay pin (`OverlayPinRegistry.lookup`); the TV's is IPv4-ordering only. Wiring the pin here
 * would make this one client behave differently from the other thirteen in the app. When the pin
 * is wired it belongs in `com.nuvio.tv.core.network.IPv4FirstDns`, where every client picks it up
 * at once.
 */
internal fun createBoomioHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
    }
    // The registration route answers non-2xx with a body that carries *why* (`cn_mismatch`,
    // `revoked`, `write_failed`). Letting Ktor raise would discard the only information the
    // caller can act on, so status handling belongs to the caller.
    expectSuccess = false
    engine { config { dns(IPv4FirstDns()).withOverlayProxy().withClientCertificate() } }
}
