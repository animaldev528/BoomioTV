package com.nuvio.app.core.mtls

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Attaching the client certificate to an HTTP client — `MtlsSsl`, P2.4 and P2.5.
 *
 * Four things, and the last two are the ones with a decision in them:
 *
 * 1. **The key manager** is driven through a **real** `KeyStore` holding a real RSA-2048 keypair and
 *    a real certificate minted by `MtlsCertificate`, so the assertions are about the provider that
 *    will actually run rather than about a mock of it. No Robolectric, for the same reason
 *    `MtlsCertificateTest` needs none: `java.security` and `javax.net.ssl` are plain JVM.
 * 2. **The fallback in [AliasPreferredKeyManager]** is the thing that turns "the client silently
 *    sent no certificate" into a visible failure, and it is tested against a delegate that
 *    **provably** declines. The same behaviour is then checked end-to-end through the real provider —
 *    but that check cannot tell *why* it passed, which is exactly why both exist.
 * 3. **The cache**, which decides when a context is rebuilt and when it is reused.
 * 4. **The deferral**, which is P2.5: the certificate is resolved when a *connection* is opened, not
 *    when a client is built. Three tests hold that line — that building resolves nothing, that
 *    opening resolves, and that a certificate arriving *between* two connections is picked up
 *    without anything being rebuilt. The last of those is the property the whole design turns on,
 *    and it is the reason `withClientCertificate()` has no early return.
 *
 * ⚠️ Also covered here: that attaching a client certificate did not disable server verification.
 * That is the one thing an mTLS change can break silently and expensively, and the assertion is
 * that a self-signed certificate unknown to any trust store is still **refused**.
 *
 * ⚠️ **Every fake source below uses a fingerprint of its own, and that is not decoration.** The
 * cache in `MtlsSsl` is process-scoped and deliberately has no invalidation hook, so two tests
 * sharing a fingerprint would share a cached context and the second would silently not build.
 * Distinct literals make that impossible however JUnit orders the class.
 */
class MtlsSslTest {

    // ⚠️ The object is a singleton, so an injected source outlives the test that set it.
    @AfterTest
    fun restore() {
        MtlsSsl.source = MtlsSsl.androidKeyStoreSource
    }

    // ── the wrapper, against a delegate that provably declines ───────────────

    @Test
    fun `the delegate's choice of client alias wins`() {
        val manager = AliasPreferredKeyManager(FakeKeyManager(clientAlias = "theirs"), ALIAS)
        assertEquals("theirs", manager.chooseClientAlias(arrayOf("RSA"), null, null))
    }

    @Test
    fun `the alias is used when the delegate declines to choose`() {
        val manager = AliasPreferredKeyManager(FakeKeyManager(clientAlias = null), ALIAS)
        // The whole point: the platform found no match — a CA list that excludes a self-signed
        // certificate is how — and the certificate is presented anyway rather than withheld.
        assertEquals(ALIAS, manager.chooseClientAlias(arrayOf("RSA"), arrayOf(OTHER_CA), null))
    }

    @Test
    fun `the chain and the private key are still the delegate's`() {
        val identity = testIdentity()
        val manager = AliasPreferredKeyManager(
            FakeKeyManager(clientAlias = null, chain = arrayOf(identity.certificate), key = identity.keyPair.private),
            ALIAS,
        )
        assertContentEquals(arrayOf(identity.certificate), manager.getCertificateChain(ALIAS))
        assertSame(identity.keyPair.private, manager.getPrivateKey(ALIAS))
        assertEquals("server-alias", manager.chooseServerAlias("RSA", null, null))
    }

    // ── the same, end to end, through a real KeyStore and a real certificate ──

    @Test
    fun `a keystore holding the alias yields one key manager`() {
        val identity = testIdentity()
        val managers = clientKeyManagers(identity.keyStore, ALIAS)
        assertEquals(1, managers.size)
        assertTrue(managers.single() is X509KeyManager)
    }

    @Test
    fun `the alias is chosen for an RSA request with no issuer list`() {
        val identity = testIdentity()
        val manager = clientKeyManagers(identity.keyStore, ALIAS).single() as X509KeyManager
        assertEquals(ALIAS, manager.chooseClientAlias(arrayOf("RSA"), null, null))
    }

    @Test
    fun `the alias is still chosen when the server names a CA that never signed it`() {
        val identity = testIdentity()
        val manager = clientKeyManagers(identity.keyStore, ALIAS).single() as X509KeyManager
        // ⚠️ This passes whether the provider filters on issuers or ignores them, so it pins the
        // outcome and not the mechanism — `the alias is used when the delegate declines` above is
        // what pins the mechanism. Both are worth having: this one would catch a change in the
        // wrapper's *delegate-first* ordering, where honouring a real match matters.
        assertEquals(ALIAS, manager.chooseClientAlias(arrayOf("RSA"), arrayOf(OTHER_CA), null))
    }

    @Test
    fun `the key manager reaches the minted certificate and its private key`() {
        val identity = testIdentity()
        val manager = clientKeyManagers(identity.keyStore, ALIAS).single() as X509KeyManager
        val chain = assertNotNull(manager.getCertificateChain(ALIAS))
        assertContentEquals(identity.certificate.encoded, chain[0].encoded)
        assertNotNull(manager.getPrivateKey(ALIAS))
    }

    @Test
    fun `a keystore without the alias yields no key managers`() {
        // An SSLContext with no key managers sends nothing, which is the honest meaning of "this
        // device has no certificate" — as against an SSLContext that throws and takes the client's
        // construction with it.
        assertEquals(0, clientKeyManagers(testIdentity().keyStore, "some.other.alias").size)
    }

    // ── the cache ────────────────────────────────────────────────────────────

    @Test
    fun `no certificate means no context`() {
        MtlsSsl.source = FakeSource(fingerprint = null)
        assertNull(MtlsSsl.context())
    }

    @Test
    fun `the context is built once for a certificate and then reused`() {
        val source = FakeSource(fingerprint = "fp-one")
        MtlsSsl.source = source
        val first = assertNotNull(MtlsSsl.context())
        val second = assertNotNull(MtlsSsl.context())
        assertSame(first, second)
        assertEquals(listOf("fp-one"), source.built)
    }

    @Test
    fun `a different certificate is built afresh`() {
        val source = FakeSource(fingerprint = "fp-two")
        MtlsSsl.source = source
        val first = assertNotNull(MtlsSsl.context())
        // Re-minting for a new name, or replacing the key, is exactly this: same call, new bytes.
        source.fingerprint = "fp-three"
        val second = assertNotNull(MtlsSsl.context())
        assertTrue(first !== second)
        assertEquals(listOf("fp-two", "fp-three"), source.built)
    }

    @Test
    fun `a failed build yields no context rather than throwing`() {
        // A client that cannot get a certificate is the pre-registration state, which is ordinary.
        // Letting this escape would crash start-up on a device that merely has not enrolled yet.
        MtlsSsl.source = FakeSource(fingerprint = "fp-four", failure = IllegalStateException("no provider"))
        assertNull(MtlsSsl.context())
    }

    @Test
    fun `a failed build is not remembered`() {
        val source = FakeSource(fingerprint = "fp-five", failure = IllegalStateException("kipped"))
        MtlsSsl.source = source
        assertNull(MtlsSsl.context())
        assertNull(MtlsSsl.context())
        // Retried, not cached as a failure: a keystore that was briefly unavailable must not
        // disable mTLS for the rest of the process's life.
        assertEquals(listOf("fp-five", "fp-five"), source.built)
    }

    // ── the attach itself ────────────────────────────────────────────────────

    @Test
    fun `a builder gets the deferred factory and no context is built`() {
        val source = FakeSource(fingerprint = "fp-six")
        MtlsSsl.source = source
        val builder = OkHttpClient.Builder()
        assertSame(builder, builder.withClientCertificate())
        assertTrue(builder.build().sslSocketFactory is DeferredClientCertSocketFactory)
        // ⚠️ The assertion that matters. Resolving the certificate here is precisely the defect:
        // the answer would be fixed at build time and a client built before registration could
        // never present one afterwards. Nothing may be resolved until a connection is opened.
        assertTrue(source.built.isEmpty(), "the attach resolved the certificate at build time")
    }

    @Test
    fun `a builder is given the deferred factory even with no certificate`() {
        // ⚠️ This is the whole of P2.5. The old shape returned the builder untouched in this case,
        // and a client built this way could never pick a certificate up later.
        val source = FakeSource(fingerprint = null)
        MtlsSsl.source = source
        assertTrue(OkHttpClient.Builder().withClientCertificate().build().sslSocketFactory is DeferredClientCertSocketFactory)
        assertTrue(source.built.isEmpty())
    }

    @Test
    fun `opening a connection resolves the certificate at that moment`() {
        val source = FakeSource(fingerprint = "fp-seven")
        MtlsSsl.source = source
        val factory = DeferredClientCertSocketFactory()
        assertTrue(source.built.isEmpty(), "constructing the factory must resolve nothing")
        factory.createSocket()
        assertEquals(listOf("fp-seven"), source.built)
    }

    @Test
    fun `a connection opened before registration and one after are not the same identity`() {
        val source = FakeSource(fingerprint = null)
        MtlsSsl.source = source
        val factory = DeferredClientCertSocketFactory()

        // Before: no certificate, so the platform default — exactly the handshake the app performs
        // today. The socket is still a real one; the attach is not allowed to break ordinary TLS.
        assertTrue(factory.createSocket() is SSLSocket)
        assertTrue(source.built.isEmpty())

        // Registration lands. Nothing is rebuilt and nothing is invalidated.
        source.fingerprint = "fp-eight"
        factory.createSocket()
        assertEquals(listOf("fp-eight"), source.built)

        // And it keeps working the other way: losing the certificate returns to the platform
        // default rather than holding a stale identity.
        source.fingerprint = null
        assertNotNull(factory.createSocket())
    }

    @Test
    fun `the attach does not stop the server being verified`() {
        // ⚠️ The failure this guards is "we added mTLS and quietly stopped checking the server".
        // A self-signed certificate that no trust store knows must still be refused.
        val client = OkHttpClient.Builder().withClientCertificate().build()
        val trustManager = assertNotNull(client.x509TrustManager)
        assertFailsWith<CertificateException> {
            trustManager.checkServerTrusted(arrayOf(testIdentity().certificate), "RSA")
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private class TestIdentity(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
        val keyStore: KeyStore,
    )

    /** A real RSA-2048 keypair, a real minted certificate, and a keystore holding both. */
    private fun testIdentity(): TestIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val minted = MtlsCertificate.selfSign(CN, keyPair, 1_700_000_000_000)
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(minted.der)) as X509Certificate
        val keyStore = KeyStore.getInstance("PKCS12")
            .apply { load(null as java.io.InputStream?, null as CharArray?) }
        keyStore.setKeyEntry(ALIAS, keyPair.private, null, arrayOf(certificate))
        return TestIdentity(keyPair, certificate, keyStore)
    }

    /** A source of certificates with no Android in it, and a record of what it was asked to build. */
    private class FakeSource(
        var fingerprint: String?,
        private val failure: Throwable? = null,
    ) : MtlsSsl.CertificateSource {

        val built = mutableListOf<String>()
        private val made = mutableMapOf<String, MtlsSsl.TlsContext>()

        override fun fingerprint(): String? = fingerprint

        override fun build(fingerprint: String): MtlsSsl.TlsContext {
            built += fingerprint
            failure?.let { throw it }
            return made.getOrPut(fingerprint) { realContext(fingerprint) }
        }
    }

    private class FakeKeyManager(
        private val clientAlias: String?,
        private val chain: Array<X509Certificate>? = null,
        private val key: PrivateKey? = null,
    ) : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = clientAlias
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            arrayOf("server-alias")
        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String = "server-alias"
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = chain
        override fun getPrivateKey(alias: String?): PrivateKey? = key
    }

    private companion object {
        const val ALIAS = "boomio.mtls.client.v1"
        const val CN = "device-test-1a2b3c4d"
        val OTHER_CA = X500Principal("CN=Some CA That Never Signed This")
    }
}

/**
 * A genuine TLS context, so the attach has real objects to hand out — and, in
 * `opening a connection resolves the certificate at that moment`, a real socket at the end of the
 * delegation. `init(null, null, null)` is the platform default, the same thing `MtlsSsl` falls back
 * to when there is no certificate.
 */
private fun realContext(fingerprint: String): MtlsSsl.TlsContext {
    val ssl = SSLContext.getInstance("TLS").apply { init(null, null, null) }
    return MtlsSsl.TlsContext(ssl.socketFactory, fingerprint)
}
