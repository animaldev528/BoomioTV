package com.nuvio.app.core.overlay

import android.app.Application
import java.io.IOException
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The server-pushed security policy, end to end on the host: the model and its decode, the single
 * routing predicate it drives, and the refresh that keeps a running client current.
 *
 * ⚠️ **The four cases the requirement names are the four sections below**, in order: the default is
 * today's behaviour; `directLanPlayback=false` forces the LAN plane through the tunnel;
 * `directWanPlayback=false` does the same for the WAN plane; and the tunnel's lifecycle is never a
 * function of the policy. A fifth covers the fetch-failure contract.
 *
 * ⚠️ Robolectric because two of the seams under test — `OverlayPinRegistry` and
 * `SecurityPolicyRefresh` — log through `android.util.Log`, which throws unmocked in a plain JVM
 * host test. The pure decode tests would run without it; keeping one runner for the file is simpler
 * than splitting it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecurityPolicyTest {

    private val pinned = InetAddress.getByName("192.168.68.65")
    private val overlay = InetAddress.getByName("10.77.0.1")

    /** Production's own value, restored after every test so the ranking tests beside this stay true. */
    private val wasCarriesTraffic = OverlayPinRegistry.ownTunnelCarriesTraffic

    @AfterTest
    fun tearDown() {
        OverlayPinRegistry.clearAll()
        OverlayPinRegistry.ownTunnelCarriesTraffic = wasCarriesTraffic
        SecurityPolicyState.reset()
        BootstrapFallback.reset()
        SecurityPolicyRefresh.apiFactory = { null }
        SecurityPolicyRefresh.minIntervalMs = 5 * 60 * 1000L
        SecurityPolicyRefresh.lastAnswerStale = false
    }

    // ── the default is today's behaviour ─────────────────────────────────────────────────────

    @Test
    fun `the default policy is the server's, key for key`() {
        assertEquals(
            SecurityPolicy(
                directLanPlayback = true,
                directWanPlayback = false,
                mtlsEnforcedOnLan = false,
                mtlsEnforcedOnWan = false,
            ),
            SecurityPolicyState.current,
        )
    }

    @Test
    fun `the default policy still follows the LAN pin when the tunnel is not carrying`() {
        // Today's behaviour, and the reason the LAN toggle defaults to `true`: a device at home with
        // no tunnel yet reaches 192.168.68.65 directly, exactly as it did before this feature.
        OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
    }

    @Test
    fun `the default policy still prefers the tunnel for a carried host`() {
        // The routing the allow-set chose is unchanged by the default policy: the tunnel is tried
        // first, and only a tunnel that cannot take the connection is a question at all.
        val tunnel = RecordingDialer()
        val direct = RecordingDialer()

        PreferTunnelDialer(
            tunnel = tunnel,
            direct = direct,
            fallbackAllowed = { mayDialDirectly(DirectPlane.WAN) },
        ).dial("bss-tor.tracemonkey.org", 443)

        assertEquals(listOf("bss-tor.tracemonkey.org" to 443), tunnel.dialled)
        assertTrue(direct.dialled.isEmpty(), "the direct dialler must not be reached while the tunnel answers")
    }

    // ── directLanPlayback = false forces the LAN plane through the tunnel ─────────────────────

    @Test
    fun `directLanPlayback false stops the LAN pin from answering`() {
        // The pin is *kept* (the browse's work is not thrown away) but not followed — the same
        // mechanism, and the same shape, as the own-tunnel-carrying suppression beside it.
        OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        SecurityPolicyState.apply(SecurityPolicy(directLanPlayback = false))

        assertNull(OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        assertNull(OverlayPinRegistry.lookup("bss-dav.tracemonkey.org"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://bss-dav.tracemonkey.org/x.mkv"))
    }

    @Test
    fun `directLanPlayback false leaves the tunnel plane alone`() {
        // Only the LAN arm is suppressed. A tunnel pin names the overlay address and is exactly the
        // route the toggle is forcing traffic onto, so it must survive untouched.
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        SecurityPolicyState.apply(SecurityPolicy(directLanPlayback = false))

        assertEquals(overlay, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
    }

    @Test
    fun `loosening directLanPlayback back restores the pin with no re-browse`() {
        // The asymmetry rule, at the routing seam: going *down* is instant, because the pin never
        // left and the predicate is read live.
        OverlayPinRegistry.ownTunnelCarriesTraffic = { false }
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        SecurityPolicyState.apply(SecurityPolicy(directLanPlayback = false))
        assertNull(OverlayPinRegistry.lookup("bsc.tracemonkey.org"))

        SecurityPolicyState.apply(SecurityPolicy(directLanPlayback = true))
        assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
    }

    // ── directWanPlayback = false forces the WAN plane through the tunnel ─────────────────────

    @Test
    fun `directWanPlayback false forbids the direct fallback when the tunnel cannot carry`() {
        // A carried host the tunnel cannot take is exactly "direct WAN playback": the only place a
        // direct WAN dial happens. Forbidding it is a hard failure (the relay answers 502) rather
        // than a quiet reach for the public edge.
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = false))
        val tunnel = RecordingDialer(failure = IOException("tunnel is not up"))
        val direct = RecordingDialer()

        assertFailsWith<IOException> {
            PreferTunnelDialer(
                tunnel = tunnel,
                direct = direct,
                fallbackAllowed = { mayDialDirectly(DirectPlane.WAN) },
            ).dial("bss-tor.tracemonkey.org", 443)
        }
        assertTrue(direct.dialled.isEmpty(), "a forbidden WAN direct dial must not reach the network")
    }

    @Test
    fun `loosening directWanPlayback restores the direct fallback`() {
        SecurityPolicyState.apply(SecurityPolicy(directWanPlayback = true))
        val tunnel = RecordingDialer(failure = IOException("tunnel is not up"))
        val direct = RecordingDialer()

        PreferTunnelDialer(
            tunnel = tunnel,
            direct = direct,
            fallbackAllowed = { mayDialDirectly(DirectPlane.WAN) },
        ).dial("bss-tor.tracemonkey.org", 443)

        assertEquals(listOf("bss-tor.tracemonkey.org" to 443), direct.dialled)
    }

    // ── the policy never touches the tunnel's lifecycle ───────────────────────────────────────

    @Test
    fun `applying every policy leaves the relay and tunnel untouched`() {
        // The toggles change routing, never lifecycle. `OverlayRelay` is not started in this test,
        // and no policy — including the strictest — may move it, because nothing in this feature
        // has a path to `start`/`stop`/`down`.
        val before = OverlayRelay.state.value

        for (lan in listOf(true, false)) {
            for (wan in listOf(true, false)) {
                SecurityPolicyState.apply(SecurityPolicy(directLanPlayback = lan, directWanPlayback = wan))
                assertEquals(before, OverlayRelay.state.value)
            }
        }
        assertTrue(OverlayRelay.state.value is RelayState.Down, "the relay must not have been started")
    }

    @Test
    fun `a policy that forbids direct still carries a working tunnel`() {
        // Liveness still wins when the tunnel is up, under the strictest policy: a forbidden direct
        // dial only ever matters when the tunnel cannot carry. The tunnel is not disabled by policy.
        SecurityPolicyState.apply(SecurityPolicy(directLanPlayback = false, directWanPlayback = false))
        val tunnel = RecordingDialer()
        val direct = RecordingDialer()

        PreferTunnelDialer(
            tunnel = tunnel,
            direct = direct,
            fallbackAllowed = { mayDialDirectly(DirectPlane.WAN) },
        ).dial("bsf.tracemonkey.org", 443)

        assertEquals(listOf("bsf.tracemonkey.org" to 443), tunnel.dialled)
        assertTrue(direct.dialled.isEmpty())
    }

    // ── a failed fetch leaves the cache in force ──────────────────────────────────────────────

    @Test
    fun `a refresh that fails leaves the cached policy in force`() {
        val context = RuntimeEnvironment.getApplication()
        val cached = SecurityPolicy(directLanPlayback = true, directWanPlayback = true)
        SecurityPolicyStore.save(context, cached)
        SecurityPolicyRefresh.initialize(context)
        assertEquals(cached, SecurityPolicyState.current)

        // Every flavour of "no answer": a throwing api, and an api that returns null.
        SecurityPolicyRefresh.apiFactory = { SecurityPolicyApi { throw IOException("no channel") } }
        assertFalse(runBlocking { SecurityPolicyRefresh.refresh() })
        assertEquals(cached, SecurityPolicyState.current, "a failed fetch must not reset the policy")

        SecurityPolicyRefresh.apiFactory = { SecurityPolicyApi { null } }
        assertFalse(runBlocking { SecurityPolicyRefresh.refresh() })
        assertEquals(cached, SecurityPolicyState.current, "a null answer must not reset the policy")
    }

    @Test
    fun `a successful refresh applies and caches the fetched policy`() {
        val context = RuntimeEnvironment.getApplication()
        SecurityPolicyStore.save(context, SecurityPolicy(directLanPlayback = true))
        SecurityPolicyRefresh.initialize(context)

        val fetched = SecurityPolicy(directLanPlayback = false, directWanPlayback = true)
        SecurityPolicyRefresh.apiFactory = { SecurityPolicyApi { fetched } }

        assertTrue(runBlocking { SecurityPolicyRefresh.refresh() })
        assertEquals(fetched, SecurityPolicyState.current)
        assertEquals(fetched, SecurityPolicyStore.load(context), "the applied policy must be cached")
        // And the routing predicate it drives moved with it.
        assertFalse(mayDialDirectly(DirectPlane.LAN))
        assertTrue(mayDialDirectly(DirectPlane.WAN))
    }

    // ── the model's decode, and the plain-HTTPS bootstrap predicate ───────────────────────────

    @Test
    fun `a policy decodes from the server's JSON shape and merges over the defaults`() {
        val full = parseSecurityPolicy(
            """{"directLanPlayback":false,"directWanPlayback":true,"mtlsEnforcedOnLan":true,"mtlsEnforcedOnWan":false}""",
        )
        assertEquals(
            SecurityPolicy(
                directLanPlayback = false,
                directWanPlayback = true,
                mtlsEnforcedOnLan = true,
                mtlsEnforcedOnWan = false,
            ),
            full,
        )

        // An older blob with fewer keys answers complete, not with an undefined flag read as false.
        assertEquals(
            SecurityPolicy(directLanPlayback = false),
            parseSecurityPolicy("""{"directLanPlayback":false}"""),
        )

        // Not a policy at all.
        assertNull(parseSecurityPolicy("{}"))
        assertNull(parseSecurityPolicy("[]"))
        assertNull(parseSecurityPolicy("not json"))
        assertNull(parseSecurityPolicy(null))
    }

    @Test
    fun `a policy message on the provisioning channel decodes`() {
        val body = overlayProvisionJson.encodeToString(
            ProvisionMessageDto.serializer(),
            ProvisionMessageDto(
                t = "policy",
                securityPolicy = parseSecurityPolicy("""{"directWanPlayback":true}""")!!.toJsonObject(),
            ),
        )

        val message = decodeProvisionMessage(body.toByteArray(Charsets.UTF_8))

        assertEquals(
            SecurityPolicy(directWanPlayback = true),
            (message as ProvisionMessage.Policy).policy,
        )
    }

    @Test
    fun `the bootstrap falls through to plain HTTPS only when the overlay cannot answer`() {
        // Defaults: the overlay is desired and the fallback is armed, so an available overlay wins.
        assertFalse(BootstrapFallback.shouldUsePlainHttps(overlayAvailable = true))
        // "Not possible": the channel did not apply, so the client still reaches the server.
        assertTrue(BootstrapFallback.shouldUsePlainHttps(overlayAvailable = false))
        // "Not desired": the client-side switch, honored even when the overlay is available.
        BootstrapFallback.plainHttpsPreferred = true
        assertTrue(BootstrapFallback.shouldUsePlainHttps(overlayAvailable = true))
        // Switched off entirely: the overlay or nothing, the pre-existing behaviour.
        BootstrapFallback.enabled = false
        assertFalse(BootstrapFallback.shouldUsePlainHttps(overlayAvailable = false))
    }

    // ── the HTTPS read, its freshness label, and the transport chain ──────────────────────────

    @Test
    fun `a 200 decodes the policy and its freshness`() {
        val answer = decodeSecurityPolicyReply(
            status = 200,
            body = """{"policy":{"directLanPlayback":false,"directWanPlayback":true,"mtlsEnforcedOnLan":false,"mtlsEnforcedOnWan":true},"stale":false,"age_ms":0}""",
        )

        assertEquals(
            SecurityPolicy(
                directLanPlayback = false,
                directWanPlayback = true,
                mtlsEnforcedOnLan = false,
                mtlsEnforcedOnWan = true,
            ),
            answer?.policy,
        )
        assertEquals(false, answer?.stale)
    }

    @Test
    fun `a stale 200 is still an answer, and is applied`() {
        // The case this requirement exists for: bsc served its last known good value after a failed
        // re-read. That is an ANSWER, not a failure — the values are the ones an admin last saved —
        // so it must be applied; the only difference from a fresh one is the label.
        val answer = decodeSecurityPolicyReply(
            status = 200,
            body = """{"policy":{"directLanPlayback":false,"directWanPlayback":true,"mtlsEnforcedOnLan":false,"mtlsEnforcedOnWan":false},"stale":true,"age_ms":42000}""",
        )
        assertEquals(SecurityPolicy(directLanPlayback = false, directWanPlayback = true), answer?.policy)
        assertEquals(true, answer?.stale)
        assertEquals(42_000L, answer?.ageMs)

        // And it survives the whole way to the routing predicate and the observable label, so a
        // stale answer is provably not treated as a failure anywhere between the wire and the seam.
        val context = RuntimeEnvironment.getApplication()
        val stalePolicy = SecurityPolicy(directLanPlayback = false, directWanPlayback = true)
        SecurityPolicyStore.save(context, SecurityPolicy())
        SecurityPolicyRefresh.initialize(context)
        SecurityPolicyRefresh.apiFactory = {
            securityPolicyApiChain(
                https = object : SecurityPolicyApi, SecurityPolicyFreshness {
                    override val lastAnswer = SecurityPolicyAnswer(stalePolicy, stale = true, ageMs = 42_000L)
                    override suspend fun fetch(): SecurityPolicy = stalePolicy
                },
                channel = SecurityPolicyApi { null },
            )
        }

        assertTrue(runBlocking { SecurityPolicyRefresh.refresh() })
        assertEquals(stalePolicy, SecurityPolicyState.current)
        assertTrue(SecurityPolicyRefresh.lastAnswerStale, "a stale answer must be labelled, not discarded")
        assertFalse(mayDialDirectly(DirectPlane.LAN), "the stale policy still drives routing")
    }

    @Test
    fun `a 503 is no answer and leaves the cached policy in force`() {
        assertNull(
            decodeSecurityPolicyReply(
                status = 503,
                body = """{"error":"policy unavailable","reason":"policy_unavailable"}""",
            ),
        )

        val context = RuntimeEnvironment.getApplication()
        val cached = SecurityPolicy(directLanPlayback = true, directWanPlayback = true)
        SecurityPolicyStore.save(context, cached)
        SecurityPolicyRefresh.initialize(context)

        // Both arms answer nothing — an unavailable server, and no channel either. The cache stays.
        SecurityPolicyRefresh.apiFactory = {
            securityPolicyApiChain(https = SecurityPolicyApi { null }, channel = SecurityPolicyApi { null })
        }
        assertFalse(runBlocking { SecurityPolicyRefresh.refresh() })
        assertEquals(cached, SecurityPolicyState.current, "a 503 must not reset the policy")
    }

    @Test
    fun `a 401 and a 429 are no answer`() {
        // Both are "keep what you have": no/invalid bearer, and this device's poll budget spent.
        assertNull(decodeSecurityPolicyReply(401, ""))
        assertNull(decodeSecurityPolicyReply(429, """{"error":"Policy read rate limited."}"""))
    }

    @Test
    fun `a malformed policy body is no answer`() {
        // One key missing: not a policy with a hole in it, a body that went wrong. Refuse it.
        assertNull(
            decodeSecurityPolicyReply(
                200,
                """{"policy":{"directLanPlayback":true,"directWanPlayback":false,"mtlsEnforcedOnLan":false},"stale":false}""",
            ),
        )
        // One key non-boolean: same, and it must not be coerced to the default.
        assertNull(
            decodeSecurityPolicyReply(
                200,
                """{"policy":{"directLanPlayback":"yes","directWanPlayback":false,"mtlsEnforcedOnLan":false,"mtlsEnforcedOnWan":false}}""",
            ),
        )
        // No `policy` at all; a body that is not an object; one that will not parse.
        assertNull(decodeSecurityPolicyReply(200, """{"stale":false}"""))
        assertNull(decodeSecurityPolicyReply(200, "[]"))
        assertNull(decodeSecurityPolicyReply(200, "not json"))
    }

    @Test
    fun `the factory's chain prefers HTTPS and falls back to the channel only on a null answer`() {
        val httpsPolicy = SecurityPolicy(directLanPlayback = false)
        val channelPolicy = SecurityPolicy(directLanPlayback = true, directWanPlayback = true)

        var httpsCalls = 0
        var channelCalls = 0
        val https = SecurityPolicyApi { httpsCalls++; httpsPolicy }
        val channel = SecurityPolicyApi { channelCalls++; channelPolicy }

        // HTTPS answers → it is the answer, and the channel is never touched.
        val both = securityPolicyApiChain(https, channel)!!
        assertEquals(httpsPolicy, runBlocking { both.fetch() })
        assertEquals(1, httpsCalls)
        assertEquals(0, channelCalls, "the channel must not be consulted while HTTPS answers")

        // HTTPS yields null — an unreachable edge, a non-200, a malformed body — so the channel is
        // reached, and is reached only now.
        val httpsNull = SecurityPolicyApi { httpsCalls++; null }
        val fallback = securityPolicyApiChain(httpsNull, channel)!!
        assertEquals(channelPolicy, runBlocking { fallback.fetch() })
        assertEquals(2, httpsCalls, "HTTPS is always tried first")
        assertEquals(1, channelCalls, "the channel answers only because HTTPS was null")

        // One arm absent → the other is used alone; neither → nothing, and the cache stays in force.
        assertEquals(https, securityPolicyApiChain(https, null))
        assertEquals(channel, securityPolicyApiChain(null, channel))
        assertNull(securityPolicyApiChain(null, null))
    }
}

/** Records what a dialer was asked to dial, and can be made to fail like a dead tunnel. */
private class RecordingDialer(private val failure: Throwable? = null) : OverlayDialer {

    val dialled = mutableListOf<Pair<String, Int>>()

    override fun dial(host: String, port: Int): OverlayConnection {
        dialled += host to port
        failure?.let { throw it }
        return object : OverlayConnection {
            override val input = java.io.ByteArrayInputStream(ByteArray(0))
            override val output = java.io.ByteArrayOutputStream()
            override fun close() = Unit
        }
    }
}
