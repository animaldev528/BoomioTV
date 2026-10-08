package com.nuvio.app.core.mtls

import android.util.Log
import com.nuvio.app.core.overlay.localServerHosts
import com.nuvio.app.core.overlay.serverDomainSuffixes
import okhttp3.Interceptor
import okhttp3.Response
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * When to ask for a re-registration — `docs/mtls-plan.md` P2.6, the *trigger* half.
 *
 * ── Why this is not the plan's wording ────────────────────────────────────
 * P2.6 reads *"if the server replies that the device is unknown, re-register the existing
 * cert"*. **No such reply exists.** The only thing `bsc` says about a device it will not serve is
 * `403 {"error":"device is revoked"}`, and that one is a *terminal* answer rather than a prompt:
 * re-registering a revoked device earns another `403`, which is why [MtlsRegistrationState.Revoked]
 * is one of the gates below. The plan assumes the **strict** form of the edge (§13.3, still open),
 * and under strict the refusal is a **TLS alert with no HTTP response at all** — no request is ever
 * carried. So the signal P2.6 names is not a reply. It is a failed handshake, and this is the piece
 * that turns one into a registration attempt.
 *
 * The *rule* half of P2.6 — that a re-registration re-sends the certificate already held and never
 * silently generates a new key — is [MtlsRegistration.mintAndInstall] and [planCertificate], and it
 * is already pinned by tests. This class only decides *when* to invoke it.
 *
 * ── Four gates, and why each one is there ─────────────────────────────────
 * A failed handshake has many causes, and only one of them is "the server does not know us". The
 * gates are ordered cheapest-first, and each one removes a class of false positive:
 *
 *  1. **Revoked** — terminal. Re-sending earns another `403` and would repeat forever, so the
 *     device stops asking for the life of the process. Re-admitting a device is an operator action
 *     that restores the *same* allow-list entry, so the certificate already held starts working
 *     again with no re-registration at all.
 *  2. **No certificate held** — there is nothing to re-send, and forcing here would send the
 *     registrar down the *mint* path, inventing a new key on the strength of an unrelated TLS
 *     failure. That is exactly what P2.6's parenthesis forbids.
 *  3. **Not one of our hosts** — a third party's TLS is not ours to repair, and this is what keeps
 *     a public Stremio addon, or a Supabase project on someone else's domain, from spending the
 *     device's registration budget.
 *  4. **Cooldown** — a burst of concurrent requests fails together, and each one arrives here. The
 *     first sets the stamp; the rest are absorbed. The stamp is written **before** [reRegister] is
 *     called rather than after, so the re-registration's own request — which travels over a client
 *     that carries this same interceptor — cannot re-enter and start a chain.
 *
 * ⚠️ **This class is deliberately free of Android and of I/O**, like the rest of the mTLS package
 * that is host-testable: every input is a lambda, including the clock. The Android logging lives in
 * [instance], the one place that is not under test.
 */
internal class MtlsHandshakeWatch(
    private val holdsCertificate: () -> Boolean,
    private val isRevoked: () -> Boolean,
    private val isOurHost: (String) -> Boolean,
    private val nowMillis: () -> Long,
    /** Asked to re-register, and told which host was refused so the reason can be logged. */
    private val reRegister: (String) -> Unit,
    private val cooldownMillis: Long = COOLDOWN_MILLIS,
) {

    /**
     * When this watch last asked, or `null` if it never has.
     *
     * Plain rather than `@Volatile`, because the monitor below is the only thing that ever touches
     * it — there is no lock-free read path to keep coherent. Refusals are rare, so nothing here is
     * on a hot path that would argue for one.
     */
    private var lastAskMillis: Long? = null

    /**
     * Note that a TLS connection to [host] was refused, and ask for a re-registration if that is
     * worth acting on.
     *
     * Never throws: it is called from an interceptor that is about to rethrow the original
     * exception, so anything raised here would replace the failure the caller was going to see with
     * a worse one. The only lock it takes is its own, held for one comparison, and the ask is made
     * outside it.
     */
    fun onHandshakeRefused(host: String) {
        if (isRevoked()) return
        if (!holdsCertificate()) return
        if (!isOurHost(host)) return

        val now = nowMillis()
        synchronized(this) {
            val last = lastAskMillis
            // A negative elapsed means the clock moved backwards. That is allowed through rather
            // than suppressed: the cost is one extra attempt, and the alternative is a device whose
            // watch is wedged until wall-clock time catches up with a stamp in its own future.
            if (last != null && now - last in 0 until cooldownMillis) return
            lastAskMillis = now
        }

        reRegister(host)
    }

    internal companion object {

        private const val TAG = "BoomioMtls"

        /**
         * How long one ask covers.
         *
         * The route's own limit is ten per device per minute, so this is far inside it. It is set by
         * what the *failure* means rather than by the budget: a refusal that a re-registration would
         * repair is repaired on the first attempt, so a second attempt inside a quarter of an hour
         * is re-asking a question already answered.
         */
        const val COOLDOWN_MILLIS = 15 * 60 * 1000L

        /**
         * The production binding.
         *
         * `lazy` so that referring to [MtlsHandshakeWatch] from a client builder does not force
         * [MtlsRegistration] — and the `CoroutineScope` it owns — into existence at that moment.
         *
         * Everything that decides anything lives in the class above; this is wiring, and it is the
         * only part of this file that touches the platform.
         */
        val instance: MtlsHandshakeWatch by lazy {
            MtlsHandshakeWatch(
                holdsCertificate = { MtlsRegistration.certificatePem() != null },
                isRevoked = { MtlsRegistration.state.value is MtlsRegistrationState.Revoked },
                isOurHost = ::hostIsOurs,
                nowMillis = System::currentTimeMillis,
                reRegister = { host ->
                    Log.i(
                        TAG,
                        "A TLS handshake to $host was refused; asking the registrar to re-send the " +
                            "existing certificate",
                    )
                    MtlsRegistration.requestRegistration(force = true)
                },
            )
        }
    }
}

/**
 * Whether [host] is one the app's own server answers for.
 *
 * The rule is `derivePinnableAddonHosts`'s, deliberately repeated rather than shared: the overlay
 * package is ported byte-identically to the TV, and widening it here would make every future
 * re-port diverge for the sake of one predicate. The two must agree — a host the overlay pins is a
 * host this app treats as its server's.
 *
 * Matching the registrable domain rather than an enumerated set is what covers the media plane
 * (`bss-dav`, `bss-tor`, `nzbdav`, …), which appears in no configuration and arrives inside stream
 * URLs. It is anchored on hosts the user's own configuration named, so it can only ever widen to
 * hosts on the server's own domain.
 */
internal fun hostIsOurs(host: String): Boolean = hostIsOurs(host, localServerHosts())

/** [hostIsOurs] against a caller-supplied host set — the form that needs no repositories. */
internal fun hostIsOurs(host: String, serverHosts: Set<String>): Boolean =
    host in serverHosts || serverDomainSuffixes(serverHosts).any { host.endsWith(".$it") }

/**
 * Turns a refused handshake into a call to [MtlsHandshakeWatch].
 *
 * ⚠️ **This is inert until an edge enforces.** No site on the live deployment asks for a client
 * certificate yet — that is P0.5 and P4 — so nothing here fires today, and a client certificate
 * that is refused cannot be observed. It becomes live the moment the enforced plane does.
 *
 * ── Why `SSLException` and not `SSLHandshakeException` ────────────────────
 * A `bad_certificate` / `certificate_unknown` alert reaches the client as an
 * [javax.net.ssl.SSLHandshakeException] on TLS 1.2, where the alert lands *during* the handshake.
 * On TLS 1.3 the server's alert arrives after the client's Finished, when `startHandshake()` has
 * already returned, and it surfaces as a plain [SSLException] on the first read instead. Catching
 * only the narrower class would leave P2.6 silently dead on every TLS 1.3 connection — the exact
 * failure shape §8 warns about — so the whole family is caught.
 *
 * The one member that is *excluded* is [SSLPeerUnverifiedException]: it means the client did not
 * trust the **server**, and no amount of re-registering our own certificate repairs that.
 *
 * Nothing is swallowed: the exception is rethrown untouched, so the caller sees exactly the failure
 * it would have seen without this interceptor installed.
 */
internal class MtlsHandshakeWatchInterceptor(
    private val watch: MtlsHandshakeWatch,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: SSLException) {
            if (e !is SSLPeerUnverifiedException) watch.onHandshakeRefused(request.url.host)
            throw e
        }
    }
}
