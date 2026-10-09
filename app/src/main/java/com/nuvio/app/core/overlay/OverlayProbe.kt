package com.nuvio.app.core.overlay

import android.content.Context
import android.util.Log
import com.nuvio.app.features.boomio.BOOMIO_SERVICE_HOST
import com.nuvio.app.features.boomio.BoomioConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

private const val TAG = "OverlayProbe"

/**
 * What the probe queries when [BoomioConfig.serviceOrigin] yields no host.
 *
 * Belt and braces rather than a real path: the origin's own default is a well-formed URL, so this
 * only fires if a caller has written something unparseable into that field. Naming a host anyway
 * is better than probing the empty string, which would report a DNS failure and be read as a
 * broken tunnel.
 *
 * It is [BOOMIO_SERVICE_HOST] rather than a fourth copy of the literal: the probe and the ladder
 * must agree about the name, or a debug run would report a failure that is really a typo.
 */
private const val DEFAULT_PROBE_DNS_NAME = BOOMIO_SERVICE_HOST

/**
 * Debug-only: proves the userspace tunnel works **inside an installed APK**.
 *
 * ⚠️ **This exists because that is the one thing still completely unproven.** Spike B measured
 * netstack as a standalone Go binary pushed to `/data/local/tmp`; the AAR has never been
 * loaded by a real app. Whether the `.so` loads, whether JNI survives, and whether an
 * in-process netstack carries traffic with **no `VpnService` slot at all** are all open, and
 * every other question — discovery, wiring the engines, the ladder — is downstream of them.
 * This is the cheapest thing that answers them.
 *
 * **Why it is `public`, which nothing else in this package is.** The launcher lives in the
 * `androidApp` module's debug source set, and `internal` does not cross a module boundary.
 * The public surface is deliberately a single `String`-returning function, so no internal
 * type leaks and the widening buys nothing else.
 *
 * **What it does.** Generates (or loads) the device keypair, prints the exact `wg set` line
 * the operator must run on the server, brings the device up, and then makes a **TCP DNS query
 * to the overlay resolver** through the tunnel.
 *
 * ⚠️ **Why a DNS query is the probe rather than an HTTPS GET.** A plaintext DNS query over
 * TCP to `10.77.0.1:53` needs no TLS, no certificate handling and no `Socket` adapter over
 * the tunnel's streams — it is a length-prefixed frame each way — and it exercises *two*
 * load-bearing things at once: that TCP is genuinely carried end to end, and that the
 * in-tunnel resolver works, which the relay depends on absolutely because it CONNECTs by
 * hostname rather than by address. An HTTPS GET would prove the same carriage and add a
 * trust-all TLS stack to the debug path to do it.
 *
 * **The oracle is not this function's return value.** It is `wg show boomio-overlay` on the
 * server: the new peer's handshake time and transfer counters. A report that says "up" while
 * the server's counters sit at zero means the bytes did not traverse the tunnel, whatever the
 * app believes.
 */
object OverlayProbe {

    /**
     * The overlay address this probe claims.
     *
     * ⚠️ **`.9`, not the live phone peer's `.2`.** The server already holds a peer for
     * `10.77.0.2/32` keyed to the *existing* device key, so a fresh keypair claiming that
     * address would collide with it. A distinct address keeps this additive and leaves the
     * working peer untouched — which matters, because the whole point is that this must not
     * disturb anything.
     */
    private const val PROBE_LOCAL_CIDR = "10.77.0.9/32"

    /** Where the probe sends its query: the overlay resolver, over TCP. */
    private const val PROBE_DNS_HOST = "10.77.0.1"
    private const val PROBE_DNS_PORT = 53

    /**
     * A name that must resolve inside the tunnel, so a correct answer means real carriage.
     *
     * ⚠️ **This is the name the app actually dials, not any one service's host.** The collapsed
     * edge serves every service off one host behind a path prefix, so probing a per-service host
     * (`bsc.…`) would prove carriage of a name nothing dials any more — a green probe over a
     * broken configuration.
     *
     * ⚠️ **Read from [BoomioConfig.serviceOrigin] rather than baked, and that is the point.** The
     * origin is now learned from the discovery record, so a probe naming a compiled-in host would
     * go on reporting green for a name the app stopped using the moment the deployment renamed
     * itself — the exact failure this constant's previous value had. Reading it live keeps the
     * probe honest about what the app is actually configured to dial.
     *
     * A `get()` rather than a `const`: the origin changes at runtime, and a probe that ran before
     * discovery finished would otherwise be frozen on the default for the life of the process.
     */
    private val PROBE_DNS_NAME: String
        get() = hostOf(BoomioConfig.serviceOrigin) ?: DEFAULT_PROBE_DNS_NAME

    /**
     * Wall-clock bound on the in-tunnel query.
     *
     * The tunnel's streams are Go blocking reads and **cannot be interrupted**, so this is a
     * watchdog rather than a timeout: the worker is abandoned, not cancelled, and the probe
     * still returns a report saying it hung. A probe that never returns is worse than one
     * that returns bad news.
     */
    private const val PROBE_TIMEOUT_MS = 15_000L

    /**
     * Runs the probe and returns a multi-line report, also written to logcat under
     * [TAG]. Never throws — a probe that crashes tells you less than one that reports.
     */
    @JvmStatic
    fun run(context: Context): String = try {
        runInternal(context)
    } catch (t: Throwable) {
        Log.e(TAG, "Probe failed", t)
        "OVERLAY PROBE: threw ${t::class.java.simpleName}: ${t.message}"
    }

    private fun runInternal(context: Context): String {
        val report = StringBuilder()

        if (OverlayWgTunnelController.instance == null) {
            OverlayWgTunnelController.initialize(context)
        }
        val tunnel = OverlayWgTunnelController.instance
            ?: return "OVERLAY PROBE: controller did not initialize".also { Log.w(TAG, it) }

        val endpoint = BoomioConfig.overlayEndpoint.trim()
        val serverKey = BoomioConfig.overlayServerPubKey.trim()

        report.appendLine("OVERLAY PROBE — built for the userland tunnel spike")
        report.appendLine("endpoint      = ${endpoint.ifEmpty { "(unset)" }}")
        report.appendLine("overlay addr  = ${BoomioConfig.overlayServerAddress.ifEmpty { "(unset)" }}")
        report.appendLine("server pubkey = ${serverKey.ifEmpty { "(unset)" }}")

        if (endpoint.isEmpty() || serverKey.isEmpty()) {
            report.appendLine()
            report.appendLine("RESULT: cannot run — set BOOMIO_OVERLAY_ENDPOINT and")
            report.appendLine("        BOOMIO_OVERLAY_PUBKEY in local.properties and rebuild.")
            return report.toString().also { Log.i(TAG, it) }
        }

        // The client's public key, printed *before* the tunnel comes up, because it is needed
        // server-side to enroll this peer and the operator has to see it even if `up()` then
        // fails. This is the one step that is still manual: adding the peer is a write to the
        // server's WireGuard interface, which the operator performs.
        val clientKey = tunnel.publicKeyBase64()
        report.appendLine("client pubkey = $clientKey")
        report.appendLine()
        report.appendLine("server-side enrollment (run once, on the host):")
        report.appendLine("  wg set boomio-overlay peer $clientKey allowed-ips $PROBE_LOCAL_CIDR")
        report.appendLine()

        if (tunnel.state.value is TunnelState.Up) tunnel.down()

        val up = tunnel.up(
            endpoint = endpoint,
            serverPublicKeyBase64 = serverKey,
            localCidr = PROBE_LOCAL_CIDR,
        )
        report.appendLine("up() -> $up")

        if (up is TunnelState.Failed) {
            report.appendLine()
            report.appendLine("RESULT: FAILED to bring the device up — ${up.reason}")
            return report.toString().also { Log.i(TAG, it) }
        }

        report.appendLine()
        report.appendLine("in-tunnel query: TCP $PROBE_DNS_HOST:$PROBE_DNS_PORT for $PROBE_DNS_NAME")
        report.appendLine(queryOverTunnel(tunnel))

        report.appendLine()
        report.appendLine("wireguard-go status: ${tunnel.status()}")
        report.appendLine()
        report.appendLine("RESULT: see above. The AUTHORITATIVE check is on the server:")
        report.appendLine("  wg show boomio-overlay   # this peer's handshake + rx/tx must be non-zero")

        return report.toString().also { Log.i(TAG, it) }
    }

    /**
     * One TCP DNS query through the tunnel, on a watchdog.
     *
     * Going through [OverlayWgDialer] rather than the binding directly is deliberate: it is
     * the same path the relay will use, so a pass here exercises the U2 seam as well as the
     * transport.
     */
    private fun queryOverTunnel(tunnel: OverlayWgTunnel): String {
        var result = "(no result — the query did not finish)"
        val worker = Thread {
            result = try {
                OverlayWgDialer(GomobileWgBinding)
                    .dial(PROBE_DNS_HOST, PROBE_DNS_PORT)
                    .use { connection ->
                        val id = 0x4F57 // arbitrary but fixed, so the reply can be matched
                        val query = dnsQuery(PROBE_DNS_NAME, id)

                        connection.output.write(byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()))
                        connection.output.write(query)
                        connection.output.flush()

                        val input = DataInputStream(connection.input)
                        val length = input.readUnsignedShort()
                        val response = ByteArray(length)
                        input.readFully(response)

                        val replyId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
                        val answers = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
                        if (replyId != id) {
                            "CARRIED but the reply is for a different query (id=$replyId)"
                        } else {
                            "CARRIED — $length bytes back, $answers answer(s) for $PROBE_DNS_NAME"
                        }
                    }
            } catch (t: Throwable) {
                // A dial failure here is the informative case: U2's dialler throws when the
                // tunnel cannot connect, which is exactly how a missing server-side peer
                // (no handshake, so nothing routes back) presents itself.
                "NOT CARRIED — ${t::class.java.simpleName}: ${t.message}"
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(PROBE_TIMEOUT_MS)
        if (worker.isAlive) {
            return "HUNG — no reply within ${PROBE_TIMEOUT_MS / 1000}s. " +
                "The device is up but nothing came back; check the server-side peer was added."
        }
        return result
    }

    /**
     * A minimal DNS `A` query, length-prefixing left to the caller.
     *
     * Written by hand rather than pulled from a library because the debug path should not add
     * a dependency for 30 bytes of well-specified header.
     */
    private fun dnsQuery(name: String, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // flags: standard query, recursion desired
        out.write(0x00); out.write(0x01) // QDCOUNT
        out.write(0x00); out.write(0x00) // ANCOUNT
        out.write(0x00); out.write(0x00) // NSCOUNT
        out.write(0x00); out.write(0x00) // ARCOUNT
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)                  // root label
        out.write(0x00); out.write(0x01) // QTYPE  A
        out.write(0x00); out.write(0x01) // QCLASS IN
        return out.toByteArray()
    }
}
