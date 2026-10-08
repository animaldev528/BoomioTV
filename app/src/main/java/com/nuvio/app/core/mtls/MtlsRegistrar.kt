package com.nuvio.app.core.mtls

import kotlinx.coroutines.delay

/**
 * What to do about this device's certificate: register the one it holds, mint a new one, or leave
 * it alone.
 *
 * This is `docs/mtls-plan.md` P2.6's rule made decidable without a device, a network, or a
 * keystore, and it is also what satisfies P2's verify line — *"confirm no re-registration on
 * relaunch"*. The rule is one sentence: **re-registering is routine, re-keying is not.**
 *
 * ── Why the certificate is re-used rather than re-minted ─────────────────────
 * ⚠️ The edge compares **raw DER** (`x509.Certificate.Equal`), so a re-minted certificate is a
 * different file even when nothing about it *means* anything different — the serial is random, so
 * the bytes differ. Re-minting on each launch would leave one file per launch in the registration
 * directory, every one of them a live allow-list entry for the same device.
 *
 * ── Why "already registered" is its own answer, and not an optimisation ───────
 * A device that re-registers on every launch is not merely wasteful, it is *indistinguishable from
 * a device that is being re-provisioned* — and registration is rate-limited per device (10/min) as
 * well as written to the ledger as a provisioning event. So the fourth answer exists so that the
 * ordinary relaunch path does no network I/O at all, and the only thing that brings the certificate
 * back to the server is [CertPlan.Reuse]'s explicit `force` — which is P2.6's "the server replies
 * that the device is unknown" case and nothing else.
 *
 * Each `Mint` carries its reason because the reasons are operationally different: a changed
 * `assignedName` means the device was re-paired as a *different* device, while a lost keystore key
 * means the device was reset. Both mint, and neither should look like the other in a log.
 */
internal sealed interface CertPlan {

    /** These bytes have been acknowledged. Nothing to do — see the note above. */
    data class AlreadyRegistered(val fingerprintSha256: String?) : CertPlan

    /** Register this PEM. Reached when registration is forced, or has never succeeded. */
    data class Reuse(val pem: String) : CertPlan

    /** Mint for [name] and replace whatever is held. [reason] says why re-use was refused. */
    data class Mint(val name: String, val reason: String) : CertPlan

    /** Nothing can be minted — there is no CN the server would accept. An ordinary state. */
    data class Unavailable(val reason: String) : CertPlan
}

/**
 * What the device holds right now.
 *
 * The two booleans are facts only the keystore layer can establish, so they are inputs rather than
 * something this file derives.
 *
 * ⚠️ [matchesCurrentKey] is load-bearing because **the server never checks that the device holds
 * the private key** — `parseClientCertificate` inspects the certificate alone, with no challenge.
 * A certificate registered against a key the device has since lost registers perfectly well and
 * then fails at the TLS handshake, with the failure appearing on the *edge* and nothing in the
 * client's log to connect it to.
 */
internal data class HeldCertificate(
    /** The name this certificate's CN was minted for. */
    val assignedName: String,
    val pem: String,
    val matchesCurrentKey: Boolean,
    /**
     * Whether the server has acknowledged *these* bytes. The flag is separate from the fingerprint
     * below rather than inferred from it, because the route is not obliged to report one and a
     * missing fingerprint must not read as "never registered" — that would re-POST on every launch,
     * which is the exact behaviour this exists to prevent.
     */
    val registered: Boolean,
    /** The server's `fingerprint_sha256`, when it sent one. Informational. */
    val registeredFingerprintSha256: String? = null,
)

/**
 * @param force re-send even though these bytes were already acknowledged — P2.6's "the server does
 *   not know this device" path. The certificate is *not* re-minted by this; the same bytes go back.
 */
internal fun planCertificate(
    assignedName: String?,
    held: HeldCertificate?,
    force: Boolean = false,
): CertPlan {
    val name = assignedName?.trim().orEmpty()
    // ⚠️ Enforced here rather than at the mint call so "no name yet" is a *plan*, not an exception:
    // a device that has enrolled but not yet been assigned an address is an ordinary state on the
    // way up, and it must not be reported as a failure.
    if (name.isEmpty()) {
        return CertPlan.Unavailable("the server has not assigned this device a name yet")
    }
    if (held == null) return CertPlan.Mint(name, "no certificate is stored")
    if (held.assignedName != name) {
        return CertPlan.Mint(name, "the stored certificate is for \"${held.assignedName}\", not \"$name\"")
    }
    if (!held.matchesCurrentKey) {
        return CertPlan.Mint(name, "the stored certificate is not bound to the keystore's current key")
    }
    if (held.registered && !force) return CertPlan.AlreadyRegistered(held.registeredFingerprintSha256)
    return CertPlan.Reuse(held.pem)
}

/**
 * The one call the registrar makes, so the policy above can be driven by a fake — the same split
 * [com.nuvio.app.core.overlay.OverlayEnroller] uses for its two.
 */
internal interface MtlsRegistrationApi {
    suspend fun register(pem: String): CertRegistrationAck
}

/** The registrar's outcome. [Skipped] and [AlreadyRegistered] are ordinary; the rest are terminal. */
internal sealed interface MtlsRegistrationState {
    data object Idle : MtlsRegistrationState

    /** Nothing to do — no session, or no server-assigned name yet. Not a failure. */
    data class Skipped(val reason: String) : MtlsRegistrationState

    /** The bytes were already acknowledged and nothing was sent. */
    data class AlreadyRegistered(val fingerprintSha256: String?) : MtlsRegistrationState

    data class Registered(val name: String, val fingerprintSha256: String?) : MtlsRegistrationState

    /** The validator refused these bytes. [code] is `cn_mismatch`, `eku`, … and is what a log greps. */
    data class Refused(val reason: String, val code: String?) : MtlsRegistrationState

    data class Revoked(val reason: String) : MtlsRegistrationState

    data class Unauthenticated(val reason: String) : MtlsRegistrationState

    /** The last attempt's reason, after every attempt was deferred. */
    data class Deferred(val reason: String) : MtlsRegistrationState
}

/**
 * Decides, then sends, then decides whether sending again is worth it.
 *
 * The order of the decisions is the point. The **plan** is made before anything is minted, so a
 * device that is merely unpaired never generates a key it cannot use; and the minted certificate is
 * **remembered before it is sent**, so a registration that fails on the network does not cause the
 * next launch to mint different bytes and orphan the file the previous run already wrote.
 *
 * @param api `null` when this device has no session — the registrar will not touch the keystore
 *   without one.
 * @param maxAttempts note that the first attempt is **immediate**. Registration is triggered the
 *   moment an assignment lands and the tunnel often is not up yet, so the retries exist to cover
 *   that window rather than to cover a slow server.
 */
internal class MtlsRegistrar(
    private val api: () -> MtlsRegistrationApi?,
    private val assignedName: () -> String?,
    private val held: () -> HeldCertificate?,
    private val mintAndInstall: (String) -> ClientCertificate,
    private val remember: (HeldCertificate) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {

    suspend fun register(force: Boolean = false): MtlsRegistrationState {
        val client = api() ?: return MtlsRegistrationState.Skipped("this device has no server session")

        val pem = when (val plan = planCertificate(assignedName(), held(), force)) {
            is CertPlan.Unavailable -> return MtlsRegistrationState.Skipped(plan.reason)
            is CertPlan.AlreadyRegistered ->
                return MtlsRegistrationState.AlreadyRegistered(plan.fingerprintSha256)
            is CertPlan.Reuse -> plan.pem
            is CertPlan.Mint -> {
                val certificate = mintAndInstall(plan.name)
                // ⚠️ Recorded BEFORE the network call, and `matchesCurrentKey = true` is not an
                // assumption: `mintAndInstall` binds this certificate into the keystore under the
                // same alias it took the key from. Recording after a successful POST would mean a
                // network failure re-mints on the next launch — different serial, different bytes,
                // another allow-list entry for one device.
                remember(
                    HeldCertificate(
                        assignedName = plan.name,
                        pem = certificate.pem,
                        matchesCurrentKey = true,
                        registered = false,
                    ),
                )
                certificate.pem
            }
        }
        return send(client, pem)
    }

    private suspend fun send(client: MtlsRegistrationApi, pem: String): MtlsRegistrationState {
        var lastReason = "the server did not answer"
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) sleep(RETRY_INTERVAL_MS)
            when (val ack = client.register(pem)) {
                is CertRegistrationAck.Registered -> {
                    // Only now is the server known to hold these bytes, so only now may the next
                    // launch skip the POST.
                    markRegistered(ack.fingerprintSha256)
                    return MtlsRegistrationState.Registered(
                        name = ack.name.orEmpty(),
                        fingerprintSha256 = ack.fingerprintSha256,
                    )
                }
                // The three terminal replies stop the loop rather than spending the rest of the
                // budget rediscovering a fact that will not change — see the protocol file's table.
                is CertRegistrationAck.Refused -> return MtlsRegistrationState.Refused(ack.reason, ack.code)
                is CertRegistrationAck.Revoked -> return MtlsRegistrationState.Revoked(ack.reason)
                is CertRegistrationAck.Unauthenticated ->
                    return MtlsRegistrationState.Unauthenticated(ack.reason)
                is CertRegistrationAck.Deferred -> lastReason = ack.reason
            }
        }
        return MtlsRegistrationState.Deferred(lastReason)
    }

    /**
     * Record that these bytes are registered, keeping the rest of what is held.
     *
     * Re-reads through [held] rather than taking the PEM as an argument: if nothing is held (a
     * caller forced a registration for a certificate this process does not have) there is nothing
     * to mark, and inventing an entry would make the next plan re-use a PEM from nowhere.
     */
    private fun markRegistered(fingerprintSha256: String?) {
        val current = held() ?: return
        remember(current.copy(registered = true, registeredFingerprintSha256 = fingerprintSha256))
    }

    private companion object {
        /** Five attempts at 4s covers the window between an assignment and the tunnel. */
        const val MAX_ATTEMPTS = 5
        const val RETRY_INTERVAL_MS = 4_000L
    }
}
