package com.nuvio.app.core.mtls

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registration policy, driven entirely off fakes — no keystore, no network, no device.
 *
 * Two things are being pinned here and they fail in different ways. [planCertificate] is the answer
 * to P2's verify line (*"confirm no re-registration on relaunch"*) and to P2.6's *"(do **not**
 * silently generate a new key)"*, so its cases are the ones that decide whether a relaunch is
 * quiet. The [MtlsRegistrar] tests below it are about *ordering* — what has been written down by
 * the time a send fails — which is the class of bug that only shows up on a device that loses its
 * network at the wrong moment.
 */
class MtlsRegistrarTest {

    private val pemA = "-----BEGIN CERTIFICATE-----\nAAA\n-----END CERTIFICATE-----\n"
    private val pemB = "-----BEGIN CERTIFICATE-----\nBBB\n-----END CERTIFICATE-----\n"

    private fun held(
        name: String = "device-kyle-s24-3f9a1b2c",
        pem: String = pemA,
        matchesKey: Boolean = true,
        registered: Boolean = false,
        fingerprint: String? = null,
    ) = HeldCertificate(
        assignedName = name,
        pem = pem,
        matchesCurrentKey = matchesKey,
        registered = registered,
        registeredFingerprintSha256 = fingerprint,
    )

    // ── planCertificate ──────────────────────────────────────────────────────

    @Test
    fun `no assigned name is unavailable, not a failure`() {
        // A device that has enrolled but has not yet been given an address is on its way up. This
        // must not mint (there is no CN) and must not be reported as an error.
        assertEquals(
            CertPlan.Unavailable("the server has not assigned this device a name yet"),
            planCertificate(assignedName = null, held = null),
        )
        assertEquals(
            CertPlan.Unavailable("the server has not assigned this device a name yet"),
            planCertificate(assignedName = "   ", held = held()),
        )
    }

    @Test
    fun `nothing held mints`() {
        assertEquals(
            CertPlan.Mint("device-kyle-s24-3f9a1b2c", "no certificate is stored"),
            planCertificate(assignedName = "device-kyle-s24-3f9a1b2c", held = null),
        )
    }

    @Test
    fun `a name the server has changed mints for the new name`() {
        // The device was re-paired as a different device. Re-using the old certificate would send a
        // CN the server has already said it will not accept.
        val plan = planCertificate(
            assignedName = "device-pixel-9-ab12cd34",
            held = held(name = "device-kyle-s24-3f9a1b2c"),
        )

        assertTrue(plan is CertPlan.Mint, "got $plan")
        assertEquals("device-pixel-9-ab12cd34", (plan as CertPlan.Mint).name)
        assertTrue(plan.reason.contains("device-kyle-s24-3f9a1b2c"), "reason was ${plan.reason}")
    }

    @Test
    fun `a name differing only in case mints, because the server's names are lowercase`() {
        // Not pedantry: `peerNameFor` lowercases and slugs, so an uppercase name can only have come
        // from somewhere other than the server, and the server will refuse it with cn_mismatch.
        val plan = planCertificate(
            assignedName = "DEVICE-KYLE-S24-3F9A1B2C",
            held = held(name = "device-kyle-s24-3f9a1b2c"),
        )

        assertTrue(plan is CertPlan.Mint, "got $plan")
    }

    @Test
    fun `a certificate not bound to the current key mints`() {
        // ⚠️ The keystore lost its key (a restore, a reset, an alias replaced) but the stored
        // certificate survived. The server cannot detect this — it never asks the device to prove
        // possession — so without this check the device registers cleanly and then fails every
        // handshake on the edge with nothing local to explain it.
        val plan = planCertificate(
            assignedName = "device-kyle-s24-3f9a1b2c",
            held = held(matchesKey = false),
        )

        assertTrue(plan is CertPlan.Mint, "got $plan")
        assertTrue((plan as CertPlan.Mint).reason.contains("keystore"), "reason was ${plan.reason}")
    }

    @Test
    fun `an unregistered certificate is re-sent without being re-minted`() {
        // The first launch registered nothing (the tunnel was not up). The second must send the
        // SAME bytes: re-minting would write a second file into the allow-list directory for one
        // device, and the edge compares raw DER, so the two would not even collide.
        assertEquals(
            CertPlan.Reuse(pemA),
            planCertificate(assignedName = "device-kyle-s24-3f9a1b2c", held = held(registered = false)),
        )
    }

    @Test
    fun `a registered certificate is left alone on relaunch`() {
        // ⚠️ This is P2's verify line — "confirm no re-registration on relaunch" — as a unit test.
        // Registration is rate-limited per device (10/min) and written to the ledger as a
        // provisioning event, so a POST here would be indistinguishable from a re-provision.
        assertEquals(
            CertPlan.AlreadyRegistered("aabbcc"),
            planCertificate(
                assignedName = "device-kyle-s24-3f9a1b2c",
                held = held(registered = true, fingerprint = "aabbcc"),
            ),
        )
    }

    @Test
    fun `a registered certificate with no fingerprint is still registered`() {
        // The route is not obliged to return `fingerprint_sha256`. If the flag were inferred from
        // the fingerprint's presence, a reply without one would read as "never registered" and the
        // device would re-POST on every single launch — the exact behaviour this prevents.
        assertEquals(
            CertPlan.AlreadyRegistered(null),
            planCertificate(assignedName = "device-x", held = held(name = "device-x", registered = true)),
        )
    }

    @Test
    fun `force re-sends a registered certificate rather than re-minting it`() {
        // P2.6's only trigger: the server says it does not know this device. The bytes go back
        // unchanged — a device that re-keys here would abandon the certificate the server may in
        // fact be holding.
        assertEquals(
            CertPlan.Reuse(pemA),
            planCertificate(
                assignedName = "device-kyle-s24-3f9a1b2c",
                held = held(registered = true, fingerprint = "aabbcc"),
                force = true,
            ),
        )
    }

    @Test
    fun `force does not override a mismatch the server would refuse anyway`() {
        // `force` means "the bytes may not have arrived", not "make these bytes correct". A CN the
        // server has already rejected is rejected identically on every attempt.
        val plan = planCertificate(
            assignedName = "device-other-00000000",
            held = held(name = "device-kyle-s24-3f9a1b2c"),
            force = true,
        )

        assertTrue(plan is CertPlan.Mint, "got $plan")
    }

    // ── MtlsRegistrar ────────────────────────────────────────────────────────

    private class FakeApi(
        private val replies: List<CertRegistrationAck>,
    ) : MtlsRegistrationApi {
        val sent = mutableListOf<String>()
        override suspend fun register(pem: String): CertRegistrationAck {
            sent += pem
            return replies.getOrElse(sent.size - 1) { replies.last() }
        }
    }

    private class Recorder {
        val remembered = mutableListOf<HeldCertificate>()
        val minted = mutableListOf<String>()

        /** What `mintAndInstall` handed back, so a test can compare the stored PEM against it. */
        val certificates = mutableListOf<ClientCertificate>()
    }

    private fun registrar(
        api: MtlsRegistrationApi?,
        name: String? = "device-kyle-s24-3f9a1b2c",
        held: HeldCertificate? = null,
        recorder: Recorder = Recorder(),
        sleeps: MutableList<Long> = mutableListOf(),
        maxAttempts: Int = 5,
    ): Pair<MtlsRegistrar, Recorder> {
        var current = held
        val runner = MtlsRegistrar(
            api = { api },
            assignedName = { name },
            held = { current },
            mintAndInstall = { n ->
                recorder.minted += n
                ClientCertificate(
                    commonName = n,
                    der = byteArrayOf(1, 2, 3),
                    notBeforeMillis = 0L,
                    notAfterMillis = 1L,
                ).also { recorder.certificates += it }
            },
            remember = { current = it; recorder.remembered += it },
            sleep = { sleeps += it },
            maxAttempts = maxAttempts,
        )
        return runner to recorder
    }

    @Test
    fun `no session skips without touching the keystore`() = runTest {
        val (runner, recorder) = registrar(api = null)

        assertEquals(
            MtlsRegistrationState.Skipped("this device has no server session"),
            runner.register(),
        )
        assertEquals(emptyList(), recorder.minted)
    }

    @Test
    fun `an unassigned device skips without minting`() = runTest {
        // The ordering the registrar exists for: the plan is made BEFORE anything is minted, so a
        // device that is merely unpaired never generates a key it has no name to certify.
        val (runner, recorder) = registrar(api = FakeApi(emptyList()), name = "")

        assertEquals(
            MtlsRegistrationState.Skipped("the server has not assigned this device a name yet"),
            runner.register(),
        )
        assertEquals(emptyList(), recorder.minted)
    }

    @Test
    fun `an already-registered device sends nothing at all`() = runTest {
        val api = FakeApi(emptyList())
        val (runner, recorder) = registrar(
            api = api,
            held = held(registered = true, fingerprint = "aabbcc"),
        )

        assertEquals(MtlsRegistrationState.AlreadyRegistered("aabbcc"), runner.register())
        assertEquals(emptyList(), api.sent)
        assertEquals(emptyList(), recorder.minted)
    }

    @Test
    fun `a minted certificate is written down before it is sent`() = runTest {
        // ⚠️ The load-bearing ordering. If the network fails here and the certificate was not
        // recorded, the next launch would mint again — a new serial, therefore different DER,
        // therefore a second live entry in the allow-list for one device.
        val api = FakeApi(listOf(CertRegistrationAck.Deferred("no route to host")))
        val (runner, recorder) = registrar(api = api, maxAttempts = 1)

        val result = runner.register()

        assertTrue(result is MtlsRegistrationState.Deferred, "got $result")
        assertEquals(1, recorder.remembered.size, "the minted certificate was never remembered")
        assertEquals("device-kyle-s24-3f9a1b2c", recorder.remembered.single().assignedName)
        assertEquals(recorder.certificates.single().pem, recorder.remembered.single().pem)
        assertTrue(!recorder.remembered.single().registered, "a failed send must not record success")
    }

    @Test
    fun `a successful send marks the bytes registered`() = runTest {
        val api = FakeApi(listOf(CertRegistrationAck.Registered("device-kyle-s24-3f9a1b2c", "aabbcc")))
        val (runner, recorder) = registrar(api = api)

        val result = runner.register()

        assertEquals(MtlsRegistrationState.Registered("device-kyle-s24-3f9a1b2c", "aabbcc"), result)
        assertEquals(2, recorder.remembered.size, "expected the pre-send write and the mark")
        assertTrue(!recorder.remembered.first().registered)
        assertTrue(recorder.remembered.last().registered)
        assertEquals("aabbcc", recorder.remembered.last().registeredFingerprintSha256)
    }

    @Test
    fun `the first attempt is immediate and the retries are spaced`() = runTest {
        // Registration fires the moment an assignment lands, and the tunnel is often not up yet —
        // so the window the retries cover is "the tunnel is coming up", not "the server is slow".
        // A delay before the first attempt would spend that window for nothing.
        val api = FakeApi(
            listOf(
                CertRegistrationAck.Deferred("no route"),
                CertRegistrationAck.Deferred("no route"),
                CertRegistrationAck.Registered("device-kyle-s24-3f9a1b2c", null),
            ),
        )
        val sleeps = mutableListOf<Long>()
        val (runner, _) = registrar(api = api, sleeps = sleeps)

        assertEquals(
            MtlsRegistrationState.Registered("device-kyle-s24-3f9a1b2c", null),
            runner.register(),
        )
        assertEquals(3, api.sent.size)
        assertEquals(listOf(4_000L, 4_000L), sleeps)
    }

    @Test
    fun `a refusal stops immediately rather than spending the budget`() = runTest {
        // A wrong CN fails identically on every attempt. Retrying it would burn the device's
        // ten-per-minute allowance rediscovering a permanent fact.
        val api = FakeApi(listOf(CertRegistrationAck.Refused("certificate CN must be \"device-x\"", "cn_mismatch")))
        val sleeps = mutableListOf<Long>()
        val (runner, _) = registrar(api = api, sleeps = sleeps)

        val result = runner.register()

        assertEquals(MtlsRegistrationState.Refused("certificate CN must be \"device-x\"", "cn_mismatch"), result)
        assertEquals(1, api.sent.size)
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun `revocation and a dead session also stop immediately`() = runTest {
        val revoked = FakeApi(listOf(CertRegistrationAck.Revoked("device is revoked")))
        val (r1, _) = registrar(api = revoked)
        assertEquals(MtlsRegistrationState.Revoked("device is revoked"), r1.register())
        assertEquals(1, revoked.sent.size)

        val unauthenticated = FakeApi(listOf(CertRegistrationAck.Unauthenticated("unauthorized")))
        val (r2, _) = registrar(api = unauthenticated)
        assertEquals(MtlsRegistrationState.Unauthenticated("unauthorized"), r2.register())
        assertEquals(1, unauthenticated.sent.size)
    }

    @Test
    fun `an exhausted budget reports the last reason it was given`() = runTest {
        val api = FakeApi(listOf(CertRegistrationAck.Deferred("no route to host")))
        val sleeps = mutableListOf<Long>()
        val (runner, _) = registrar(api = api, sleeps = sleeps, maxAttempts = 3)

        assertEquals(MtlsRegistrationState.Deferred("no route to host"), runner.register())
        assertEquals(3, api.sent.size)
        assertEquals(listOf(4_000L, 4_000L), sleeps)
    }

    @Test
    fun `a retry re-sends the identical bytes`() = runTest {
        // The whole point of the retry loop: the certificate is minted once and every attempt sends
        // that one certificate. A loop that re-minted per attempt would write a file per attempt.
        val api = FakeApi(
            listOf(
                CertRegistrationAck.Deferred("no route"),
                CertRegistrationAck.Registered("device-x", null),
            ),
        )
        val (runner, recorder) = registrar(api = api)

        runner.register()

        assertEquals(2, api.sent.size)
        assertEquals(api.sent[0], api.sent[1])
        // ⚠️ And the bytes sent are the ones that were *minted*, not some other certificate — the
        // two assertions together are what make "identical" mean "identical to what was stored".
        assertEquals(recorder.certificates.single().pem, api.sent[0])
        assertEquals(1, recorder.certificates.size, "the retry loop minted more than once")
    }

    @Test
    fun `a held certificate that is re-used is sent verbatim`() = runTest {
        val api = FakeApi(listOf(CertRegistrationAck.Registered("device-x", null)))
        val (runner, recorder) = registrar(api = api, held = held(pem = pemB))

        runner.register()

        assertEquals(pemB, api.sent.single())
        assertEquals(emptyList(), recorder.minted)
    }

    @Test
    fun `a forced registration of held bytes does not re-mint`() = runTest {
        val api = FakeApi(listOf(CertRegistrationAck.Registered("device-x", null)))
        val (runner, recorder) = registrar(
            api = api,
            held = held(registered = true, fingerprint = "aabbcc"),
        )

        runner.register(force = true)

        assertEquals(pemA, api.sent.single())
        assertEquals(emptyList(), recorder.minted)
    }

    @Test
    fun `marking registered keeps the rest of what is held`() = runTest {
        // `markRegistered` re-reads through `held()` and copies, so a field the plan did not touch
        // — the PEM above all — survives. Setting only the flag on a fresh object would silently
        // blank the certificate and force a re-mint on the next launch.
        val api = FakeApi(listOf(CertRegistrationAck.Registered("device-x", "aabbcc")))
        val (runner, recorder) = registrar(api = api, held = held(pem = pemB))

        runner.register()

        assertEquals(pemB, recorder.remembered.last().pem)
        assertEquals("aabbcc", recorder.remembered.last().registeredFingerprintSha256)
        assertTrue(recorder.remembered.last().registered)
    }
}
