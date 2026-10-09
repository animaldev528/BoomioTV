package com.nuvio.app.core.overlay

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "OverlayWgTunnel"

/**
 * How long [OverlayWgTunnel.up] waits for a WireGuard handshake before calling the device dead.
 *
 * ⚠️ **Bounded, and generous on purpose.** wireguard-go initiates a handshake as soon as the
 * device is up when `persistent_keepalive_interval` is set — this app sets 25 s — and a healthy
 * peer answers in about a second; `OverlayTunnel`'s own doc records the same figure. Eight
 * seconds is therefore several times a healthy round trip, and it is a *wait* rather than a
 * policy: being wrong in the generous direction costs eight seconds of bring-up latency once,
 * while being wrong in the stingy direction declares a working tunnel dead.
 */
private const val HANDSHAKE_WAIT_MS = 8_000L

/** How often the wait above re-reads the device. Cheap — one `IpcGet` per poll. */
private const val HANDSHAKE_POLL_MS = 250L

/**
 * Whether wireguard-go's `IpcGet` dump shows a **completed** handshake.
 *
 * ⚠️ **`last_handshake_time_sec == 0` is the signal, and it is the only one the dump offers.**
 * A configured device reports `tx_bytes > 0` immediately — those bytes *are* the handshake
 * attempts — so neither "the device exists" nor "bytes moved" separates a live tunnel from one
 * shouting into a network that will never answer. Every other field is identical between the
 * two.
 *
 * A dump with no such line reads as **not** handshaked, which is the safe direction: an
 * unrecognised format falls back to the direct path rather than black-holing it.
 *
 * Pure, and that is the point — the real binding is a JNI library that cannot load on the host
 * JVM, so the one part of this decision worth testing is kept out of it.
 */
internal fun handshakeCompleted(rawStatus: String): Boolean =
    rawStatus.lineSequence()
        .firstOrNull { it.substringBefore('=').trim() == "last_handshake_time_sec" }
        ?.substringAfter('=', "")
        ?.trim()
        ?.toLongOrNull()
        ?.let { it > 0L }
        ?: false

/**
 * What the userspace tunnel is doing.
 *
 * ⚠️ **Deliberately not the same type as [RelayState] or [OverlayTunnel]'s, even though the
 * three read similarly.** They answer different questions — the relay's is "is there a
 * listener", [OverlayTunnel]'s is "did the user bring up a VPN the platform can see", and
 * this one is "is our own wireguard-go device up **and answering**". Collapsing them would make
 * the diagnostic row unable to say which of the three is the one that is wrong.
 */
internal sealed interface TunnelState {
    /** Never brought up, or brought down. The initial state. */
    data object Down : TunnelState

    /**
     * `Up()` returned, the device is running, **and a WireGuard handshake has completed**.
     * [status] is wireguard-go's own view.
     *
     * ⚠️ **The handshake clause is load-bearing, and it was added after a device regression.**
     * This state used to mean only "the device was configured", and two consumers read it as
     * "the tunnel carries traffic": [OverlayPinRegistry.ownTunnelCarriesTraffic], which stands
     * the LAN and WAN arms down while it is true, and [OverlayRelay]'s `mayFallBackToDirect`,
     * which holds the direct-dial fallback back while it is true. A device configured against an
     * endpoint it cannot reach — a WAN literal, on a network that does not hairpin — therefore
     * reported `Up`, suppressed every other path, and turned every live fetch into a
     * `CONNECT: 502`. See [OverlayWgTunnel.up] for the wait that makes the clause true.
     */
    data class Up(val status: String) : TunnelState

    /** The device could not be built, usually a rejected config. */
    data class Failed(val reason: String) : TunnelState
}

/**
 * The lifecycle of the app's **own** userspace WireGuard device.
 *
 * **Why this exists at all.** U2 shipped a transport with no driver: `GomobileWgBinding` had
 * zero callers, nothing generated a keypair on device, and nothing called `up()`. The AAR was
 * therefore a library nobody loaded — which is the one thing about this design that is still
 * completely unproven, because Spike B measured netstack as a **standalone Go binary pushed
 * to `/data/local/tmp`**, never as a `.so` loaded inside an APK. This class is the smallest
 * thing that closes that gap.
 *
 * **What it owns, and what it does not.** It owns the keypair (generated once, persisted) and
 * the device's up/down state. It does **not** decide *whether* to bring the tunnel up, where
 * the endpoint comes from, or what to do when it fails — those are U3's discovery and routing
 * decisions, and the answers live in `OverlayRelay` and (eventually) the ladder in
 * architecture §4.4. Keeping the policy out is what makes this testable against a fake
 * binding on the host JVM.
 *
 * ⚠️ **It takes its collaborators as constructor parameters rather than reaching for the
 * singletons**, which is the same shape [OverlayRelay.start] uses and for the same reason: a
 * host test must be able to hand it a [FakeWgBinding][OverlayWgBinding] and an in-memory
 * store, because the real binding is a JNI library that cannot load off-device.
 */
internal class OverlayWgTunnel(
    /**
     * `internal` rather than private so [OverlaySession] can hand the same binding to the
     * relay's tunnel dialler. One object, two consumers — and letting the session reach for
     * `GomobileWgBinding` directly instead would mean a host test exercising the session
     * against a fake device while the relay it wires up dialled through the real one.
     */
    internal val binding: OverlayWgBinding,
    private val loadKeypair: () -> OverlayWgKeypair?,
    private val saveKeypair: (OverlayWgKeypair) -> Unit,
    /**
     * How long [up] waits for a handshake before declaring the device dead.
     *
     * ⚠️ **A parameter rather than a direct read of [HANDSHAKE_WAIT_MS], for the same reason the
     * collaborators above are parameters**: the wait is real wall-clock cost, and a test that
     * must spend eight seconds to observe a timeout is a test nobody keeps running. The default
     * is the production value, so production is unaffected.
     */
    private val handshakeWaitMs: Long = HANDSHAKE_WAIT_MS,
) {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Down)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    @Volatile
    private var pair: OverlayWgKeypair? = null

    /**
     * The device's keypair, generated on first use and then stable for the install's life.
     *
     * ⚠️ **Stability matters more than it looks.** The server has to hold this public key as
     * a peer *before* the tunnel can handshake, and the operator adds it by hand today. A
     * keypair regenerated per launch would invalidate that enrollment every time the app
     * starts, so the persisted one is the point, not an optimisation.
     */
    @Synchronized
    fun keypair(): OverlayWgKeypair =
        pair ?: (loadKeypair() ?: binding.generateKeypair().also(saveKeypair)).also { pair = it }

    /** The client's public key, base64 — the exact string `wg set … peer` wants. */
    fun publicKeyBase64(): String = keypair().publicKeyBase64

    /**
     * The public key **as stored**, or null when nothing is stored yet.
     *
     * ⚠️ The difference from [publicKeyBase64] is the entire point, and it is not a
     * micro-optimisation. [keypair] *generates and persists* a fresh keypair whenever the
     * stored one is incomplete, so a caller that only means to **report** which identity it
     * presented would, in the one case where storage is missing, silently rotate that
     * identity instead — converting a diagnostic read into the cause of the next incident.
     * That is not hypothetical here: the failure this is used to explain was a key that
     * rotated out from under a server peer record.
     */
    fun storedPublicKeyBase64(): String? =
        pair?.publicKeyBase64 ?: loadKeypair()?.publicKeyBase64

    /**
     * Brings the device up against [endpoint] (`host:port`) and the server's [serverPublicKeyBase64].
     *
     * ⚠️ **[serverPublicKeyBase64] arrives base64 and is converted to hex here**, because the
     * two ends of this system speak different encodings and neither should have to learn the
     * other's: `wg set` on the server and both publication channels use base64, while
     * wireguard-go's `IpcSet` takes hex. Doing it here means the operator pastes the value
     * straight from the mDNS advert without a conversion step to get wrong.
     *
     * Returns the resulting state rather than throwing, and **never leaves the previous state
     * ambiguous**: a config the device rejects lands in [TunnelState.Failed] with the reason,
     * not in a half-up state.
     */
    @Synchronized
    fun up(
        endpoint: String,
        serverPublicKeyBase64: String,
        localCidr: String,
        allowedIps: String = DEFAULT_ALLOWED_IPS,
        dns: String = DEFAULT_DNS,
        mtu: Int = DEFAULT_MTU,
        keepalive: Int = DEFAULT_KEEPALIVE,
    ): TunnelState {
        if (endpoint.isBlank()) return fail("No overlay endpoint is configured")
        if (serverPublicKeyBase64.isBlank()) return fail("No server public key is configured")

        return try {
            val serverKey = decodeWireGuardKey(serverPublicKeyBase64)
                ?: return fail(
                    "The server public key is not a 32-byte base64 key " +
                        "(${serverPublicKeyBase64.length} chars): $serverPublicKeyBase64"
                )

            binding.up(
                privateKeyHex = keypair().privateKeyHex,
                peerPublicKeyHex = hexOf(serverKey),
                endpoint = endpoint,
                localCidr = localCidr,
                allowedIps = allowedIps,
                dns = dns,
                mtu = mtu,
                keepalive = keepalive,
            )
            // ⚠️ **`Up()` returning is not the same as a handshake having completed, and this
            // wait is what makes [TunnelState.Up] mean the latter.** The device comes up before
            // the peer answers — and, when the endpoint is one this network cannot reach at all,
            // it comes up and *never* answers. Both states used to publish `Up`, which two
            // consumers read as "the tunnel carries traffic": the pin registry stood the LAN and
            // WAN arms down, and the relay held the direct-dial fallback back. Every request then
            // went into a tunnel that carried nothing. The wait costs about a second on a healthy
            // bring-up and is the only thing that separates the two cases.
            //
            // ⚠️ **The bound is deliberately generous rather than tight.** Nothing here is
            // measuring how fast the peer is; it is separating "answered" from "cannot answer".
            // A stingy bound would declare a working tunnel dead on a slow network, which is the
            // one mistake with a lasting cost.
            val handshakeDeadline = System.nanoTime() + handshakeWaitMs * 1_000_000L
            var status = binding.status()
            while (!handshakeCompleted(status) && System.nanoTime() < handshakeDeadline) {
                Thread.sleep(HANDSHAKE_POLL_MS)
                status = binding.status()
            }
            if (!handshakeCompleted(status)) {
                // Torn down rather than left running. A device that is up but has never
                // handshaken is not a degraded tunnel, it is a black hole: leaving it up would
                // keep `_state` one caller away from reporting `Up` again and re-suppressing the
                // paths that do work.
                runCatching { binding.down() }
                    .onFailure { Log.w(TAG, "Tearing down a handshake-less device failed", it) }
                return fail(
                    "No WireGuard handshake within ${handshakeWaitMs}ms against $endpoint — " +
                        "configured, but not reachable from this network"
                )
            }
            TunnelState.Up(redactKeys(status)).also { _state.value = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not bring the overlay tunnel up", t)
            fail(t.message ?: t::class.java.simpleName)
        }
    }

    /** Tears the device down. Safe when already down. */
    @Synchronized
    fun down() {
        try {
            binding.down()
        } catch (t: Throwable) {
            // Not rethrown: the caller is asking for a state, and "already down" is that
            // state. A failure here means it is down for a different reason, which the next
            // `up()` will surface.
            Log.w(TAG, "Tearing the overlay tunnel down failed", t)
        }
        _state.value = TunnelState.Down
    }

    /**
     * wireguard-go's own view — handshake time and tx/rx counters. Never throws, and **never
     * carries key material**; see [redactKeys].
     */
    fun status(): String = try {
        redactKeys(binding.status())
    } catch (t: Throwable) {
        "unavailable: ${t.message}"
    }

    private fun fail(reason: String): TunnelState =
        TunnelState.Failed(reason).also { _state.value = it }

    /**
     * Strips the key lines out of wireguard-go's `IpcGet` dump.
     *
     * ⚠️ **`IpcGet` prints the private key, in full, in hex** — and every value this function
     * returns is display-bound. [TunnelState.Up.status] is documented as the diagnostic string
     * for the status row, and the first on-device run of the probe wrote one straight into a
     * report file and the log. A device dump, a screenshot of a diagnostics screen, or a
     * pasted support log would each carry a working private key off the device.
     *
     * The overlay key is not a user credential and its reach is bounded by the server's peer
     * table, which is why a leak is survivable rather than an incident — but "survivable" is
     * not a reason to put it in a string that exists to be shown to people. The counters and
     * handshake time, which are the parts worth reading, are untouched.
     */
    private fun redactKeys(raw: String): String =
        raw.lineSequence().joinToString("\n") { line ->
            when (line.substringBefore('=').trim()) {
                "private_key", "preshared_key" -> "${line.substringBefore('=')}=‹redacted›"
                else -> line
            }
        }

    companion object {
        /**
         * Everything the client routes into the tunnel. One entry, because every boomio
         * service sits behind one Caddy on one host — the same reasoning as the POC's
         * `AllowedIPs = 10.77.0.1/32` shape, widened to the /24.
         */
        const val DEFAULT_ALLOWED_IPS = "10.77.0.0/24"

        /**
         * The overlay resolver. Not optional: [OverlayWgBinding.dial] takes the **hostname**
         * from the CONNECT line, so names have to resolve *inside* the tunnel, and this is
         * what resolves them.
         */
        const val DEFAULT_DNS = "10.77.0.1"

        /** 1420, matching the server's `boomio-overlay`. */
        const val DEFAULT_MTU = 1420

        /**
         * 25 s. WireGuard's usual recommendation, and it is doing real work here rather than
         * being a default copied out of habit: the home NAT is endpoint-independent for
         * ordinary outbound UDP but the mapping still has to be *kept alive*, and a peer that
         * goes quiet long enough for the mapping to expire cannot be re-reached from outside.
         */
        const val DEFAULT_KEEPALIVE = 25
    }
}

/**
 * The process-wide tunnel, wired to the real binding and to `SharedPreferences`.
 *
 * An `object` holding one instance rather than an `object` that *is* the tunnel, so the
 * tested class stays constructor-injectable while production still gets exactly one device
 * per process — which is what the Go side assumes anyway, since it keeps the device in
 * package-level state.
 */
internal object OverlayWgTunnelController {

    private const val PREFS = "boomio_overlay_tunnel"

    /**
     * ⚠️ **Plain `SharedPreferences`, and this is a deliberate, bounded choice rather than an
     * oversight.** The overlay identity is not a user credential — it is a per-install
     * transport key whose only power is to reach `10.77.0.0/24`, and its blast radius is
     * already bounded by the server's peer table. The app sandbox is the boundary today.
     *
     * The moment enrollment (U4) starts binding anything *user*-scoped to this identity, it
     * should move behind the AndroidKeyStore pattern `MdbListAuthPersistence` already uses.
     * Recorded here so that is a decision someone makes on purpose, not one they inherit.
     */
    @Volatile
    private var tunnel: OverlayWgTunnel? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (tunnel != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        tunnel = OverlayWgTunnel(
            binding = GomobileWgBinding,
            loadKeypair = { readKeypair(store) },
            saveKeypair = { writeKeypair(store, it) },
        )
    }

    /** Null until [initialize] — callers treat that as "no tunnel", never as an error. */
    val instance: OverlayWgTunnel? get() = tunnel

    private fun readKeypair(store: SharedPreferences): OverlayWgKeypair? {
        val privB64 = store.getString(KEY_PRIVATE_B64, null) ?: return null
        val pubB64 = store.getString(KEY_PUBLIC_B64, null) ?: return null
        val privHex = store.getString(KEY_PRIVATE_HEX, null) ?: return null
        val pubHex = store.getString(KEY_PUBLIC_HEX, null) ?: return null
        return OverlayWgKeypair(privB64, pubB64, privHex, pubHex)
    }

    private fun writeKeypair(store: SharedPreferences, kp: OverlayWgKeypair) {
        // Persisted in both encodings, exactly as the Go side emitted them, rather than
        // re-derived on read: the conversion the app *can* do (base64 → hex) is a different
        // one from the one it needs (both from the same raw key), and re-deriving a private
        // key is not something to do on a path this cheap to just store.
        store.edit()
            .putString(KEY_PRIVATE_B64, kp.privateKeyBase64)
            .putString(KEY_PUBLIC_B64, kp.publicKeyBase64)
            .putString(KEY_PRIVATE_HEX, kp.privateKeyHex)
            .putString(KEY_PUBLIC_HEX, kp.publicKeyHex)
            .apply()
    }

    private const val KEY_PRIVATE_B64 = "private_key_b64"
    private const val KEY_PUBLIC_B64 = "public_key_b64"
    private const val KEY_PRIVATE_HEX = "private_key_hex"
    private const val KEY_PUBLIC_HEX = "public_key_hex"
}

/**
 * Base64 → bytes, via `android.util.Base64` rather than `java.util.Base64`.
 *
 * ⚠️ **Not a stylistic choice.** The module's `minSdk` is 24 and `java.util.Base64` arrived
 * in API 26, so the standard-library version is a `NoClassDefFoundError` on the oldest
 * devices this app supports — on the exact path that builds the tunnel.
 */
private fun base64ToBytes(value: String): ByteArray =
    Base64.decode(value.trim(), Base64.NO_WRAP)

/**
 * A WireGuard key, or null if [value] is not one.
 *
 * ⚠️ **The length check is the check, not a belt-and-braces extra.** `android.util.Base64` is
 * *lenient* about characters outside the alphabet — it skips them rather than throwing, which
 * a test caught — so `"not!valid!base64!"` decodes to a short, well-formed-looking byte array
 * instead of raising. Without this, a paste that picked up a stray character would become a
 * garbage peer key, and the only symptom would be a tunnel that never completes a handshake
 * and says nothing about why. A WireGuard key is exactly 32 bytes; anything else is a
 * misconfiguration, and saying so at the point of configuration is worth far more than the
 * two lines it costs.
 */
internal fun decodeWireGuardKey(value: String): ByteArray? = try {
    base64ToBytes(value).takeIf { it.size == WIREGUARD_KEY_BYTES }
} catch (t: IllegalArgumentException) {
    null
}

/** WireGuard keys — Curve25519 public and private — are 32 bytes, both ends. */
private const val WIREGUARD_KEY_BYTES = 32

/** Lower-case hex, which is the encoding wireguard-go's `IpcSet` parses. */
private fun hexOf(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    val digits = "0123456789abcdef"
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
}
