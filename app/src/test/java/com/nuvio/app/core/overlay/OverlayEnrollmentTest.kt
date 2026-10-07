package com.nuvio.app.core.overlay

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B3: what the device does with an overlay assignment.
 *
 * **What is actually at stake.** Two things, and both fail silently.
 *
 * The first is *when the cache is trusted*. A cached assignment is reused with no server call,
 * which is what lets the tunnel come up on a plane or a dead network — and the same shortcut,
 * taken one step too far, hands the tunnel an address the server has since given to somebody
 * else. The line between those is [CachedAssignment.issuedForPublicKeyBase64]: the address is
 * allocated per public key, so a cached value is valid exactly while the device's key is the
 * one it was issued for.
 *
 * The second is the wire contract with `bsc/routes/overlay.js`. Every field name decoded below
 * is a name on that side of the seam, and a rename there produces no error anywhere — the
 * device simply polls forever, which looks exactly like a server that is merely slow. That is
 * why the decoder is a free function and the tests below feed it literal JSON.
 *
 * Robolectric because this file shares a source set with `android.util.Log`; no server and no
 * device, because [OverlayEnrollmentApi] is the seam and the fake *is* the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayEnrollmentTest {

    private val deviceKey = "kQ0m5v0X1r2s3t4u5v6w7x8y9zABCDEFGHIJKLMNOP="
    private val otherKey = "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ="

    private val assignment = OverlayAssignment(
        address = "10.77.0.7",
        serverPublicKeyBase64 = "kqZZdZcbAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        endpoint = "boomio.duckdns.org:51820",
        overlayCidr = "10.77.0.0/24",
        mtu = 1420,
    )

    /** A server whose two answers are fixed up front, and which counts what it was asked. */
    private class FakeApi(
        private val ack: EnrollAck = EnrollAck.Pending(0),
        private val polls: List<EnrollPoll> = listOf(EnrollPoll.Pending),
    ) : OverlayEnrollmentApi {
        var enrollCalls = 0
            private set
        var pollCalls = 0
            private set

        override suspend fun enroll(publicKeyBase64: String): EnrollAck {
            enrollCalls++
            return ack
        }

        override suspend fun poll(): EnrollPoll {
            val answer = polls.getOrElse(pollCalls) { polls.last() }
            pollCalls++
            return answer
        }
    }

    private class Recorder {
        val applied = mutableListOf<OverlayAssignment>()
        val remembered = mutableListOf<CachedAssignment>()
        val slept = mutableListOf<Long>()
    }

    private fun enroller(
        api: OverlayEnrollmentApi?,
        recorder: Recorder = Recorder(),
        key: String? = deviceKey,
        held: CachedAssignment? = null,
        maxPolls: Int = 12,
    ): Pair<OverlayEnroller, Recorder> {
        val built = OverlayEnroller(
            api = { api },
            devicePublicKey = { key },
            cached = { held },
            remember = { recorder.remembered += it },
            apply = { recorder.applied += it },
            sleep = { recorder.slept += it },
            maxPolls = maxPolls,
        )
        return built to recorder
    }

    // ── the cache ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cache issued for this device key is applied without asking the server`() = runBlocking {
        val server = FakeApi()
        val (subject, recorder) = enroller(
            api = server,
            held = CachedAssignment(assignment, issuedForPublicKeyBase64 = deviceKey),
        )

        val result = subject.enroll(useCache = true)

        val ready = assertIs<OverlayEnrollmentState.Ready>(result)
        assertTrue(ready.fromCache, "a cache hit is reported as cached so the caller can refresh behind it")
        assertEquals(assignment, ready.assignment)
        assertEquals(listOf(assignment), recorder.applied)
        // ⚠️ The point of the cache is that this did NOT happen. A cold start with no
        // connectivity has to produce a working tunnel, and it cannot if it waits on a request.
        assertEquals(0, server.enrollCalls)
        assertEquals(0, server.pollCalls)
    }

    @Test
    fun `a cache issued for a DIFFERENT key is discarded and the server is asked`() = runBlocking {
        // The device regenerated its keypair. The server is holding a peer for a key this
        // device no longer has, so the old address is not stale — it is wrong, and applying it
        // would produce a tunnel that handshakes with nobody.
        val server = FakeApi(ack = EnrollAck.Pending(0), polls = listOf(EnrollPoll.Ready(assignment)))
        val (subject, recorder) = enroller(
            api = server,
            held = CachedAssignment(assignment.copy(address = "10.77.0.2"), issuedForPublicKeyBase64 = otherKey),
        )

        val result = subject.enroll(useCache = true)

        val ready = assertIs<OverlayEnrollmentState.Ready>(result)
        assertEquals(false, ready.fromCache)
        assertEquals(1, server.enrollCalls, "a mismatched cache must not short-circuit the server")
        assertEquals(listOf(assignment), recorder.applied, "only the server's answer is ever applied here")
    }

    @Test
    fun `a refresh ignores a perfectly good cache`() = runBlocking {
        // The startup path. The cache is applied for availability, but the server is still
        // asked, because the server's endpoint or public key may have rotated since — and
        // nothing else in the app would ever notice that.
        val server = FakeApi(ack = EnrollAck.Pending(0), polls = listOf(EnrollPoll.Ready(assignment)))
        val (subject, _) = enroller(
            api = server,
            held = CachedAssignment(assignment, issuedForPublicKeyBase64 = deviceKey),
        )

        subject.enroll(useCache = false)

        assertEquals(1, server.enrollCalls)
        assertEquals(1, server.pollCalls)
    }

    // ── refusals that are not retryable ─────────────────────────────────────────────────────

    @Test
    fun `no companion session is Unavailable, not a failure`() = runBlocking {
        // A device that has never paired has nothing to enrol *as*. Reporting that as a failure
        // would put an error in front of a user who has done nothing wrong.
        val (subject, recorder) = enroller(api = null)

        assertEquals(OverlayEnrollmentState.Unavailable, subject.enroll())

        assertTrue(recorder.applied.isEmpty())
    }

    @Test
    fun `a device with no key pair fails rather than enrolling as nothing`() = runBlocking {
        val server = FakeApi()
        val (subject, _) = enroller(api = server, key = null)

        assertIs<OverlayEnrollmentState.Failed>(subject.enroll())

        assertEquals(0, server.enrollCalls, "there is no key to enroll")
    }

    @Test
    fun `a rejected enrollment surfaces the server's own words`() = runBlocking {
        val server = FakeApi(ack = EnrollAck.Rejected("Enrollment rate limited."))
        val (subject, _) = enroller(api = server)

        val failed = assertIs<OverlayEnrollmentState.Failed>(subject.enroll())

        assertEquals("Enrollment rate limited.", failed.reason)
    }

    // ── the poll loop ───────────────────────────────────────────────────────────────────────

    @Test
    fun `pending then ready caches and applies the assignment`() = runBlocking {
        val server = FakeApi(
            ack = EnrollAck.Pending(pollAfterMs = 3_000),
            polls = listOf(EnrollPoll.Pending, EnrollPoll.Pending, EnrollPoll.Ready(assignment)),
        )
        val (subject, recorder) = enroller(api = server)

        val ready = assertIs<OverlayEnrollmentState.Ready>(subject.enroll(useCache = false))

        assertEquals(false, ready.fromCache)
        assertEquals(assignment, ready.assignment)
        assertEquals(listOf(assignment), recorder.applied)
        // Cached, because the assignment is what makes the *next* cold start work offline.
        assertEquals(listOf(CachedAssignment(assignment, deviceKey)), recorder.remembered)
    }

    @Test
    fun `the server's poll hint is honoured for the first wait only`() = runBlocking {
        val server = FakeApi(
            ack = EnrollAck.Pending(pollAfterMs = 3_000),
            polls = listOf(EnrollPoll.Pending, EnrollPoll.Ready(assignment)),
        )
        val (subject, recorder) = enroller(api = server)

        subject.enroll(useCache = false)

        assertEquals(listOf(3_000L, 3_000L), recorder.slept)
    }

    @Test
    fun `a failed poll is reported as failed`() = runBlocking {
        val server = FakeApi(
            ack = EnrollAck.Pending(0),
            polls = listOf(EnrollPoll.Failed("the host rejected the public key")),
        )
        val (subject, recorder) = enroller(api = server)

        val failed = assertIs<OverlayEnrollmentState.Failed>(subject.enroll(useCache = false))

        assertEquals("the host rejected the public key", failed.reason)
        assertTrue(recorder.applied.isEmpty(), "nothing is applied from a failed enrollment")
        assertTrue(recorder.remembered.isEmpty(), "and nothing is cached")
    }

    @Test
    fun `polling gives up after maxPolls rather than looping forever`() = runBlocking {
        // The adapter runs on a host timer. It can genuinely be slower than this window, and
        // when it is, nothing is wrong — so this must terminate without being a hard failure
        // the caller would treat as a reason to stop asking on later launches.
        val server = FakeApi(ack = EnrollAck.Pending(2_500), polls = listOf(EnrollPoll.Pending))
        val (subject, recorder) = enroller(api = server, maxPolls = 4)

        assertIs<OverlayEnrollmentState.Failed>(subject.enroll(useCache = false))

        assertEquals(4, server.pollCalls)
        assertEquals(4, recorder.slept.size)
        assertEquals(2_500L, recorder.slept.first(), "the first wait is the server's hint")
        assertTrue(recorder.slept.drop(1).all { it == 3_000L }, "later waits are the steady interval")
    }

    // ── the assignment's own shape ──────────────────────────────────────────────────────────

    @Test
    fun `an assignment renders its address as a host route`() {
        // `Address =` in a WireGuard config is CIDR, and a client's overlay address is its own
        // — a /32, never the /24 the server owns. Getting this wrong is the silent-drops
        // failure: the interface comes up and routes nothing.
        assertEquals("10.77.0.7/32", assignment.localCidr)
    }

    @Test
    fun `an assignment names the server at the overlay's first host`() {
        // ⚠️ The value that used to be build-time only. bsc has never sent the server's own
        // overlay address, so the client derives it — and `next_peer_address()` in
        // overlay/overlay-server-setup.sh allocates from **.2**, which is what makes .1 the
        // server rather than a guess. This test is the client half of that convention: if the
        // allocator's base ever moves, this is where it should fail.
        assertEquals("10.77.0.1", assignment.serverAddress)
    }

    @Test
    fun `the server address is derived from the network, not from the device's own`() {
        // A device at .7 on somebody else's overlay must not be told the server is at .8.
        assertEquals("10.77.0.1", overlayServerAddressOf("10.77.0.0/24"))

        // Masking means a host address and its network give the same answer.
        assertEquals("10.77.0.1", overlayServerAddressOf("10.77.0.7/24"))

        // And a different plan is a different answer — nothing here is hardcoded to 10.77.
        assertEquals("10.8.0.1", overlayServerAddressOf("10.8.0.0/16"))
        assertEquals("172.16.5.1", overlayServerAddressOf("172.16.5.0/24"))
    }

    @Test
    fun `an unparseable cidr yields no server address rather than a wrong one`() {
        // ⚠️ Blank is the fail-closed answer: the resolver stays off. A wrong address would
        // point every boomio FQDN at a host that never answers, which reads exactly like a
        // broken tunnel and costs far more to find than a resolver that never turned on.
        assertEquals("", overlayServerAddressOf(""))
        assertEquals("", overlayServerAddressOf("10.77.0.0"))
        assertEquals("", overlayServerAddressOf("not-a-cidr/24"))
        assertEquals("", overlayServerAddressOf("10.77.0/24"))
        assertEquals("", overlayServerAddressOf("10.77.0.256/24"))
        assertEquals("", overlayServerAddressOf("10.77.0.0/31"))
        assertEquals("", overlayServerAddressOf("10.77.0.0/32"))
        assertEquals("", overlayServerAddressOf("10.77.0.0/0"))
    }

    // ── the wire contract with bsc ──────────────────────────────────────────────────────────

    @Test
    fun `the enrol request uses the field name bsc parses`() {
        val body = overlayEnrollJson.encodeToString(EnrollRequestDto(deviceKey))

        assertTrue(body.contains("\"pubkey\""), "bsc reads req.body.pubkey at routes/overlay.js:156; got: $body")
        assertTrue(body.contains(deviceKey))
    }

    @Test
    fun `the 202 acknowledgement carries the poll hint`() {
        val ack = decodeEnrollAck("""{"status":"pending","name":"device-x","poll_after_ms":3000}""")

        assertEquals(EnrollAck.Pending(3_000), ack)
    }

    @Test
    fun `a 202 without a hint still polls on a sane default`() {
        assertEquals(EnrollAck.Pending(OVERLAY_ENROLL_DEFAULT_POLL_MS), decodeEnrollAck("""{"status":"pending"}"""))
    }

    @Test
    fun `an unreadable acknowledgement is rejected, not polled`() {
        // Polling on a body we could not parse would spend the whole window waiting for a
        // request that was never recorded.
        assertIs<EnrollAck.Rejected>(decodeEnrollAck("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `a ready status decodes every field the tunnel needs`() {
        val poll = decodeEnrollStatus(
            """
            {"status":"ready","name":"device-x","address":"10.77.0.7",
             "server_pubkey":"kqZZdZcbAA=","endpoint":"boomio.duckdns.org:51820",
             "overlay_cidr":"10.77.0.0/24","mtu":1420}
            """.trimIndent(),
        )

        assertEquals(
            EnrollPoll.Ready(
                OverlayAssignment(
                    address = "10.77.0.7",
                    serverPublicKeyBase64 = "kqZZdZcbAA=",
                    endpoint = "boomio.duckdns.org:51820",
                    overlayCidr = "10.77.0.0/24",
                    mtu = 1420,
                ),
            ),
            poll,
        )
    }

    @Test
    fun `a ready status with a missing endpoint is refused`() {
        // ⚠️ The dangerous half-answer: an address with no server key to authenticate, or no
        // endpoint to dial. Applying it produces a tunnel that is up and useless, which is
        // indistinguishable from a network problem.
        val poll = decodeEnrollStatus(
            """{"status":"ready","address":"10.77.0.7","server_pubkey":"kqZZdZcbAA=","endpoint":null}""",
        )

        assertIs<EnrollPoll.Failed>(poll)
    }

    @Test
    fun `an unrecognised status is pending, not an error`() {
        // The adapter has more states than the client cares about. Treating an unknown one as
        // a failure would make a future server-side state stop every client from enrolling.
        assertEquals(EnrollPoll.Pending, decodeEnrollStatus("""{"status":"queued"}"""))
        assertEquals(EnrollPoll.Pending, decodeEnrollStatus("""{"status":"pending"}"""))
    }

    @Test
    fun `a failed status carries the reason through`() {
        val poll = decodeEnrollStatus("""{"status":"failed","name":"device-x","reason":"peer name rejected"}""")

        assertEquals(EnrollPoll.Failed("peer name rejected"), poll)
    }

    @Test
    fun `a failed status without a reason still fails`() {
        assertEquals(EnrollPoll.Failed("The server could not enroll this device."), decodeEnrollStatus("""{"status":"failed"}"""))
    }

    @Test
    fun `an error body is preferred over the status code, and 401 has its own words`() {
        assertEquals(
            "Enrollment rate limited.",
            describeEnrollFailure("""{"error":"Enrollment rate limited."}""", 429),
        )
        assertEquals(
            "This device is not paired with the server.",
            describeEnrollFailure("", 401),
        )
        assertEquals("The server refused the request (500).", describeEnrollFailure("not json", 500))
    }

    @Test
    fun `an error body with no message falls back rather than returning blank`() {
        val text = describeEnrollFailure("""{"error":""}""", 500)

        assertNull(text.takeIf { it.isBlank() }, "an empty reason would render as a silent failure: $text")
    }

    // ── the calls themselves, against a real socket ─────────────────────────────────────────
    //
    // The decoders above pin the *bodies*; these pin the *requests*. A wrong path or a dropped
    // bearer is not something any amount of JSON parsing can catch, and both fail the same
    // silent way — the device polls a route that does not exist and reports nothing.

    private val server = MockWebServer()

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.shutdown()
    }

    /** Plain OkHttp, no overlay proxy and no custom resolver — the same shape as production. */
    private fun client(): HttpClient = HttpClient(OkHttp) { expectSuccess = false }

    private fun api(token: String = "bs_ses_test") = BscOverlayEnrollmentApi(
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = token,
        http = client(),
    )

    @Test
    fun `enrolling posts the pubkey to the route bsc mounts, with the session bearer`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("""{"status":"pending","name":"device-x","poll_after_ms":3000}"""),
        )

        val ack = api().enroll(deviceKey)

        assertEquals(EnrollAck.Pending(3_000), ack)

        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "no request arrived")
        assertEquals("POST", request.method)
        // ⚠️ `requireSession` reads the bearer to decide *which device* this is — the peer name
        // is derived from the verified session, not the body. Without this header the route
        // answers 401 and the device can never enrol.
        assertEquals("/api/overlay/enroll", request.path)
        assertEquals("Bearer bs_ses_test", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains(deviceKey), "the device's own public key is the payload")
    }

    @Test
    fun `polling hits the status route with the session bearer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"pending","name":"device-x"}"""))

        assertEquals(EnrollPoll.Pending, api().poll())

        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "no request arrived")
        assertEquals("GET", request.method)
        // ⚠️ No device parameter, deliberately: bsc derives the peer name from the verified
        // session, so there is nothing here for a client to point at another device.
        assertEquals("/api/overlay/enroll/status", request.path)
        assertEquals("Bearer bs_ses_test", request.getHeader("Authorization"))
    }

    @Test
    fun `a malformed status body is reported rather than treated as pending`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>502 Bad Gateway</html>"))

        // `assertTrue`, not `assertIs`: this block's last expression becomes the test
        // function's inferred return type, and `assertIs<EnrollPoll.Failed>` would return an
        // `internal` type from a `public` function — a compile error, and a confusing one.
        val poll = api().poll()
        assertTrue(poll is EnrollPoll.Failed, "a 200 with a non-JSON body must not read as pending: $poll")
    }

    @Test
    fun `a ready status survives a real round trip`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"ready","name":"device-x","address":"10.77.0.7","server_pubkey":"kqZZdZcbAA=",""" +
                    """"endpoint":"boomio.duckdns.org:51820","overlay_cidr":"10.77.0.0/24","mtu":1420}""",
            ),
        )

        val poll = api().poll()

        assertEquals(
            EnrollPoll.Ready(
                OverlayAssignment(
                    address = "10.77.0.7",
                    serverPublicKeyBase64 = "kqZZdZcbAA=",
                    endpoint = "boomio.duckdns.org:51820",
                    overlayCidr = "10.77.0.0/24",
                    mtu = 1420,
                ),
            ),
            poll,
        )
    }

    @Test
    fun `the route's own rate-limit refusal is surfaced, not swallowed`() = runBlocking {
        // `enrollLimiter` answers 429 with exactly this body. A device that re-enrolled on a
        // loop would spend the household's whole budget, so this message has to reach the
        // caller rather than being flattened into a generic failure.
        server.enqueue(
            MockResponse().setResponseCode(429).setBody("""{"error":"Enrollment rate limited."}"""),
        )

        assertEquals(EnrollAck.Rejected("Enrollment rate limited."), api().enroll(deviceKey))
    }

    @Test
    fun `an unpaired device is told so rather than being left to poll`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))

        assertEquals(EnrollPoll.Failed("unauthorized"), api().poll())
    }
}
