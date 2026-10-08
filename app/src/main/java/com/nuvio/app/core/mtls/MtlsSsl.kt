package com.nuvio.app.core.mtls

import android.util.Log
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Handing the client certificate to an HTTP client. This is `docs/mtls-plan.md` P2.4 — the step
 * between "the device holds a certificate the edge has listed" and "the device actually presents
 * it".
 *
 * The whole surface is [withClientCertificate], one line at a construction site:
 *
 * ```
 * OkHttpClient.Builder().withOverlayProxy().withClientCertificate()
 * ```
 *
 * ── Why the attach is safe to apply anywhere ─────────────────────────────────
 * A TLS client sends its certificate **only if the server asks for one** — RFC 8446 §4.4.2 (and
 * §7.4.6 for 1.2). Nothing here inspects the URL, so pointing a client at a host that does not run
 * client auth costs one unused key manager and changes no byte on the wire. That is what makes
 * "attach at every Boomio-facing client" a defensible default rather than a list that has to be
 * kept in sync with the edge's per-path enforcement (§8), which is still being decided in §13.
 *
 * ── P2.5: the client does not have to be rebuilt, and that is the point ──────
 * The obvious shape for this is to read [context] once, at the moment a client is built, and hand
 * the result to the builder. That shape has a defect that P2.5 exists to repair: at start-up —
 * before enrollment has landed and registration has run — there is no certificate, so every client
 * is built plain and stays plain for the life of the process. A device that registers but never
 * rebuilds then looks *precisely* like a device whose mTLS does not work, and the repair is a
 * rebuild hook on every singleton that holds a client ([SupabaseProvider], [AddonHttpClientProvider],
 * and four `private val http` objects in `commonMain` that `androidMain` cannot reach) — which is
 * the same machinery again for P2.6, where re-registering installs a *different* certificate.
 *
 * So the attach is **deferred instead**: the builder is always given
 * [DeferredClientCertSocketFactory], which resolves [context] once per **socket** rather than once
 * per client. OkHttp creates a socket per connection and pools the results, so a connection opened
 * after registration carries the certificate and one opened before it does not — which costs
 * nothing, because a pre-registration connection to an enforced host could not have completed
 * anyway. Re-minting is then picked up for free by the same mechanism.
 *
 * ⚠️ This is why the certificate is *not* resolved per **handshake**. A socket's `SSLContext` is
 * fixed when the socket is created, so per-handshake resolution is not available; per-connection
 * is, and it is the granularity the pool already gives us.
 *
 * ⚠️ One consequence worth stating: this installs a socket factory on **every** client it is
 * applied to, including ones pointed at hosts that will never ask for a certificate. That is not a
 * behaviour change — with no certificate the factory delegates to the platform default socket
 * factory, the same one OkHttp would have built — but it does mean the factory is on the path for
 * ordinary TLS, so it must stay a pure passthrough. [DeferredClientCertSocketFactory] is written to
 * be exactly that.
 *
 * ── Where it is deliberately *not* applied ───────────────────────────────────
 * `PlayerPlaybackNetworking` (its `checkServerTrusted = Unit` is scoped to playback and §8 says
 * leave it), `OverlayEnrollment.enrollmentHttpClient` (enrollment is what *creates* the tunnel, so
 * a client that could not complete without the tunnel would deadlock), and the pairing client
 * (`BoomioSessionRepository`, which runs before a session exists). Attaching there would not
 * merely be useless — on the playback client it would be a lie, because trust-all is the point.
 */
internal object MtlsSsl {

    private const val TAG = "BoomioMtls"

    /**
     * A TLS configuration that presents the client certificate: the socket factory a client needs,
     * plus the fingerprint it was built for so the cache below can tell when it has gone stale.
     *
     * There is no trust manager here, and that is not an omission — see [withClientCertificate]. It
     * is the platform default whichever way this resolves, so carrying a copy would only invite a
     * caller to use this one.
     */
    internal class TlsContext(
        val socketFactory: SSLSocketFactory,
        val fingerprint: String,
    )

    /**
     * Where the certificate comes from.
     *
     * An interface because the host suite cannot reach an `AndroidKeyStore` — there is no provider
     * for one off a device — and the cache below is the only part of this file with a decision in
     * it, so it is the part that has to be testable. See [MtlsSslTest].
     */
    internal interface CertificateSource {
        /** SHA-256 of the certificate a handshake would present, or `null` if there is none. */
        fun fingerprint(): String?

        /** Build a context for the certificate hashing to [fingerprint]. */
        fun build(fingerprint: String): TlsContext
    }

    /** The real source: the `AndroidKeyStore` alias [MtlsIdentity] owns. */
    internal val androidKeyStoreSource: CertificateSource = object : CertificateSource {

        override fun fingerprint(): String? =
            MtlsRegistration.presentableCertificate()?.let { sha256Hex(it.encoded) }

        override fun build(fingerprint: String): TlsContext {
            // ⚠️ The logging lives here rather than in [context] on purpose. `android.util.Log` is
            // unavailable to a JVM host test unless it runs under Robolectric, and the cache above
            // is tested without one — so the Android-only half of this file is the half that talks
            // to Android. [context] catches what this throws and says nothing.
            try {
                val context = SSLContext.getInstance("TLS")
                context.init(keyManagers(), arrayOf<TrustManager>(platformTrustManager()), null)
                return TlsContext(context.socketFactory, fingerprint)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not build a TLS context for the client certificate", t)
                throw t
            }
        }
    }

    @Volatile
    internal var source: CertificateSource = androidKeyStoreSource

    @Volatile
    private var cached: TlsContext? = null

    /** The context to attach, or `null` when this device has nothing to present. */
    internal fun context(): TlsContext? {
        // Re-read every call: this is a keystore lookup plus a hash, and it is the *only* thing that
        // notices the certificate changed.
        val fingerprint = source.fingerprint() ?: return null
        cached?.let { if (it.fingerprint == fingerprint) return it }
        synchronized(this) {
            cached?.let { if (it.fingerprint == fingerprint) return it }
            // A failure here must not escape into the caller. `context()` is on the path of every
            // https connection this app makes once [withClientCertificate] has been applied, so a
            // throw here would turn a keystore hiccup into a crash on an ordinary request; answering
            // `null` instead means the connection is made the way it would have been made without
            // mTLS at all, which is the pre-registration behaviour. Nothing is cached on failure, so
            // the next connection retries and a transient failure heals on its own.
            val built = try {
                source.build(fingerprint)
            } catch (t: Throwable) {
                return null
            }
            cached = built
            return built
        }
    }

    /**
     * There is deliberately **no `invalidate()`**.
     *
     * The cache is keyed on the certificate's own hash, so re-minting for a new name, replacing the
     * key, or deleting the identity all produce a different fingerprint — or no fingerprint, which
     * returns before the cache is consulted — and the stale entry is simply never matched again. An
     * invalidation call would be a second source of truth for "the certificate changed" that a
     * caller has to remember to make, and this file exists because that kind of obligation is how
     * mTLS ends up silently off.
     */
    private fun keyManagers(): Array<KeyManager> =
        clientKeyManagers(
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) },
            MtlsIdentity.KEY_ALIAS,
        )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Point an `OkHttpClient.Builder` at the client certificate — always, and without deciding yet
 * whether there is one.
 *
 * ⚠️ **There is deliberately no early return for "this device has no certificate yet".** Deciding
 * that here is the defect [MtlsSsl]'s KDoc describes: the answer is fixed at build time and a client
 * built before registration can never present a certificate afterwards. Every call site is a
 * construction site that may run before enrollment, so none of them can make that decision.
 *
 * The trust manager is the platform's, and it is passed unconditionally because
 * `sslSocketFactory(factory, trustManager)` will not take a null one. That is not a concession: it
 * is the same manager OkHttp installs for itself, and [DeferredClientCertSocketFactory] delegates to
 * the platform socket factory whenever there is no certificate, so an ordinary TLS client is
 * configured exactly as it would have been.
 *
 * ── The interceptor, and why it rides along here ──────────────────────────
 * P2.6's trigger is a *refused handshake*, and the only place that failure is visible is the client
 * that presented the certificate. Attaching [MtlsHandshakeWatchInterceptor] here rather than at
 * each site keeps the rule that **a client carrying the certificate is a client that notices it was
 * rejected** — the same "there is no per-site decision" property the factory above is built on. It
 * is inert today: nothing enforces yet, so nothing is refused.
 *
 * It is added as an *application* interceptor, outside OkHttp's retry and follow-up logic, so it
 * observes the failure the caller will actually see rather than one of the internally-retried
 * attempts along the way.
 */
internal fun OkHttpClient.Builder.withClientCertificate(): OkHttpClient.Builder =
    sslSocketFactory(DeferredClientCertSocketFactory(), platformTrustManager())
        .addInterceptor(MtlsHandshakeWatchInterceptor(MtlsHandshakeWatch.instance))

/**
 * A socket factory whose identity is chosen when a **connection** is opened, not when the client is
 * built.
 *
 * OkHttp calls one of the `createSocket` overloads once per connection and pools the socket, so
 * resolving [MtlsSsl.context] from inside them is what lets a client built before registration
 * present the certificate on its next connection, and what makes a re-minted certificate (P2.6)
 * take effect without anything being rebuilt.
 *
 * ⚠️ The delegation target is resolved on **every** call rather than cached, because caching it is
 * the very defect this class exists to avoid — but "resolved" is cheap and already memoised one
 * level down: [MtlsSsl.context] re-reads the keystore only to hash the certificate, and returns the
 * same `TlsContext` it built last time when the hash has not moved.
 */
internal class DeferredClientCertSocketFactory : SSLSocketFactory() {

    private fun delegate(): SSLSocketFactory =
        MtlsSsl.context()?.socketFactory ?: platformSocketFactory()

    override fun getDefaultCipherSuites(): Array<String> = delegate().defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate().supportedCipherSuites

    override fun createSocket(): Socket = delegate().createSocket()

    override fun createSocket(host: String, port: Int): Socket = delegate().createSocket(host, port)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = delegate().createSocket(host, port, localHost, localPort)

    override fun createSocket(
        socket: Socket,
        host: String,
        port: Int,
        autoClose: Boolean,
    ): Socket = delegate().createSocket(socket, host, port, autoClose)

    override fun createSocket(host: InetAddress, port: Int): Socket = delegate().createSocket(host, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = delegate().createSocket(address, port, localAddress, localPort)
}

/**
 * The trust manager to keep alongside the certificate.
 *
 * ⚠️ This is the **platform default, built the same way OkHttp builds it itself**
 * (`Platform.platformTrustManager()` is exactly these three lines), so attaching a client
 * certificate changes *who we are* and nothing about *who we trust*. That matters twice over: this
 * app's trust decisions belong to `network_security_config.xml`, which the default `X509TrustManager`
 * honours, and a custom trust manager sneaked in beside the key manager is how "we added mTLS" turns
 * into "we stopped verifying the server".
 */
private fun platformTrustManager(): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .firstOrNull()
        ?: throw IllegalStateException("the platform offers no X509 trust manager")

/**
 * The socket factory OkHttp would have used had we not attached anything: the platform default
 * context with the platform default key and trust managers.
 *
 * ⚠️ `init(null, null, null)` is not a shortcut. It is literally how OkHttp builds its own default
 * (`Platform.newSslSocketFactory`), so when this device has no certificate the handshake is
 * byte-for-byte the one the app performs today. Installing a *different* default here would be a
 * silent change to every https request in the app.
 */
private fun platformSocketFactory(): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory

/**
 * The key manager for [alias], wrapped so it cannot decline to present it.
 *
 * ⚠️ **This is the one thing in P2.4 that turns a silent failure into a loud one, and it is worth
 * the ten lines.** A client picks its certificate through `chooseClientAlias`, and the platform's
 * implementation filters candidates by the certificate authorities the *server* listed in its
 * `CertificateRequest`. Our certificate is self-signed and no CA ever signed it, so if the edge
 * ever sends a non-empty CA list — a `trust_pool` appearing beside the `leafdir` verifier, a
 * reordered `client_auth` block, a future Caddy that derives the list differently — the platform
 * finds no match and returns `null`. The client then sends **no certificate at all**, and the edge
 * answers with a handshake failure that reads exactly like a broken certificate. §8 calls this out
 * by name as the failure mode to avoid.
 *
 * So the delegate is asked first and its answer is honoured; ours is the fallback, and it is only
 * ever reached when the platform was going to send nothing. Presenting a certificate the server
 * may reject is strictly more informative than presenting none, and against the edge as configured
 * today (a verifier with no trust pool ⇒ an empty CA list ⇒ the delegate matches) this is a no-op.
 *
 * Only `chooseClientAlias` is overridden, because it is the method every TLS stack calls to answer
 * a `CertificateRequest`; the rest of the interface is delegated untouched, which is what keeps
 * `getCertificateChain`/`getPrivateKey` reading from the keystore rather than from here.
 */
internal class AliasPreferredKeyManager(
    private val delegate: X509KeyManager,
    private val alias: String,
) : X509KeyManager by delegate {

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = delegate.chooseClientAlias(keyType, issuers, socket) ?: alias
}

/**
 * The key managers for [keyStore]'s [alias], or none.
 *
 * Takes the `KeyStore` as an argument rather than reaching for `AndroidKeyStore` so that the host
 * suite can run it against a real provider — see [MtlsSslTest], which builds a genuine RSA-2048
 * keypair and a genuine minted certificate into a `PKCS12` store and drives the real
 * `KeyManagerFactory` through it. Returning an empty array is the honest answer for an alias that
 * is not there: an `SSLContext` with no key managers sends nothing, which is what "this device has
 * no certificate" should mean.
 *
 * ⚠️ `init(keyStore, null)` — the null password — is not sloppiness. An `AndroidKeyStore` entry's
 * private key has no password to give: the key never leaves the keystore, and the provider
 * authorises its use through the key's own `KeyGenParameterSpec` ([MtlsIdentity.spec]), not through
 * a passphrase.
 */
internal fun clientKeyManagers(keyStore: KeyStore, alias: String): Array<KeyManager> {
    if (!keyStore.containsAlias(alias)) return emptyArray()
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore, null)
    val delegate = factory.keyManagers.filterIsInstance<X509KeyManager>().firstOrNull()
        ?: return emptyArray()
    return arrayOf(AliasPreferredKeyManager(delegate, alias))
}
