package com.nuvio.app.core.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "OverlayDnsClient"

/**
 * The rung-2 lookup: an address **and** a `TXT` tuple for one name.
 *
 * ### Why this exists at all
 *
 * Rung 1 (mDNS) is link-local: it needs multicast, which client isolation, a guest VLAN, some
 * APs and any VPN that owns the routing table can all take away. Rung 2 is the rung that has to
 * work when multicast does not — which is why architecture §10.7's "no LAN fallback" makes it
 * load-bearing rather than convenient.
 *
 * ### Why two mechanisms, and not one
 *
 * The `A` half is resolved through [InetAddress], i.e. **the platform's own resolver**, not a
 * query of our own. That is deliberate: the whole point of `boomio-local` is a name the
 * *network* knows and the app does not, and every deployment choice in §10.8 lands somewhere
 * the platform resolver already reads — a house dnsmasq host-record, a public record, a
 * Private-DNS zone. A hand-rolled query would have to reproduce the platform's server
 * selection, search domains and Private DNS handling to reach the same answer, and would
 * disagree with the rest of the app whenever it got one of them wrong.
 *
 * The `TXT` half has no platform API below API 29 (`android.net.DnsResolver`), and `minSdk` is
 * 24, so it is a plain UDP query to the network's own resolvers. That is a **narrower** claim
 * than the `A` half makes, and it is worth being explicit about the consequence: on a network
 * with Private DNS enabled, the address may resolve while the tuple does not. That is a
 * *degraded* rung 2, not a false one — [OverlayAdvertTuple] carries a null key, the ladder sees
 * an unusable candidate, and it falls through to rung 3 exactly as if the record were missing.
 * It never fabricates a key.
 *
 * ### Why the budget is threaded through rather than owned here
 *
 * §4.4 requires a rung-2 miss to be **cheap and timeboxed**, because rung 1's cost is already a
 * known cold-launch hazard (`BROWSE_WINDOW_MS`, the `1aa62092` fix) and a DNS timeout on the
 * critical path would put it straight back. The ladder owns the total; this object spends what
 * it is given, per phase, and never blocks past it.
 */
internal object OverlayDnsClient {

    private const val TYPE_A = 1
    private const val TYPE_CNAME = 5
    private const val TYPE_TXT = 16
    private const val CLASS_IN = 1

    /** A name that has been CNAME'd more than this far is a loop or a misconfiguration. */
    private const val MAX_CNAME_HOPS = 3

    /** Guards the pointer-following walk against a response that points at itself. */
    private const val MAX_NAME_JUMPS = 32

    /** Port 53, everywhere. */
    private const val DNS_PORT = 53

    /**
     * Resolves [name] for up to [budgetMs] in total.
     *
     * Returns null when nothing usable came back — a missing record, an unreachable resolver, a
     * malformed reply, or simply the budget expiring. **All four are the same answer to the
     * ladder**, which is the point: a rung that cannot produce an endpoint must be
     * indistinguishable from one that was never asked, so the caller never has to branch on
     * *why* DNS disappointed it.
     */
    suspend fun resolve(context: Context, name: String, budgetMs: Long): Resolved? {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val address = resolveAddress(name, budgetMs / 3)
        if (address == null) {
            Log.d(TAG, "'$name' has no IPv4 address; rung 2 cannot help")
            return null
        }
        val remaining = budgetMs - (android.os.SystemClock.elapsedRealtime() - startedAt)
        if (remaining <= 0) {
            Log.d(TAG, "'$name' resolved but the budget was spent before the TXT lookup")
            return Resolved(address, OverlayAdvertTuple(null, null))
        }
        val tuple = resolveTxt(context, name, remaining) ?: OverlayAdvertTuple(null, null)
        Log.d(TAG, "Rung 2: '$name' -> ${address.hostAddress}, tuple=$tuple")
        return Resolved(address, tuple)
    }

    /** What rung 2 produced: an address always, and a key/port if the `TXT` said so. */
    data class Resolved(
        val address: InetAddress,
        val tuple: OverlayAdvertTuple,
    )

    /**
     * The `A` record, via the platform resolver.
     *
     * ⚠️ **Filtered to IPv4 on purpose.** The ladder hands this to a WireGuard endpoint, and an
     * IPv6 answer would be a different reachability question on a network whose IPv6 is
     * routinely worse than its IPv4 — the same reasoning `IPv4FirstDns` already encodes for the
     * app's own traffic.
     */
    private suspend fun resolveAddress(name: String, budgetMs: Long): InetAddress? =
        withTimeoutOrNull(budgetMs) {
            withContext(Dispatchers.IO) {
                runCatching { InetAddress.getAllByName(name).toList() }
                    .onFailure { Log.d(TAG, "'$name' did not resolve: ${it.message}") }
                    .getOrDefault(emptyList())
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull()
            }
        }

    /**
     * The `TXT` record, over plain UDP to the network's resolvers.
     *
     * Resolvers are read from the **active** network's `LinkProperties` rather than from
     * `InetAddress`'s internal list, because the question being asked is "what does *this*
     * network say about `boomio-local`" — which on a house network means the router, and
     * nowhere else.
     */
    private suspend fun resolveTxt(context: Context, name: String, budgetMs: Long): OverlayAdvertTuple? {
        val resolvers = resolverAddresses(context)
        if (resolvers.isEmpty()) {
            Log.d(TAG, "No DNS resolvers on the active network; skipping the TXT lookup")
            return null
        }
        val perServer = (budgetMs / resolvers.size).coerceAtLeast(200L)
        val deadline = android.os.SystemClock.elapsedRealtime() + budgetMs

        // A resolver that does not answer is ordinary — a captive portal, a stub that only
        // speaks DoT — so the list is walked rather than a single server being trusted.
        for (resolver in resolvers) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            val strings = queryTxt(resolver, name, minOf(perServer, remaining)) ?: continue
            val tuple = parseDnsTxtRecord(strings)
            if (tuple.serverPublicKeyBase64 != null || tuple.port != null) return tuple
        }
        return null
    }

    private fun resolverAddresses(context: Context): List<InetAddress> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        return runCatching {
            val network = manager.activeNetwork ?: return emptyList()
            manager.getLinkProperties(network)?.dnsServers?.toList().orEmpty()
        }.onFailure { Log.d(TAG, "Could not read the network's resolvers: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * One UDP conversation with one resolver, which may be several queries long.
     *
     * ⚠️ **The `CNAME` follow is a *loop*, not a single hop, and it lives inside the socket for
     * a reason.** `boomio-local` on a public suffix is plausibly an alias, and a resolver that
     * answers with the alias alone would otherwise make rung 2 look exactly like a missing
     * record — the failure would be indistinguishable from "the owner published nothing", which
     * is the one thing this rung must not do. Re-using the socket keeps the follow inside the
     * one budget rather than paying a fresh bind and timeout per hop, and [MAX_CNAME_HOPS]
     * bounds it because the chain is dictated by a remote answer.
     */
    private suspend fun queryTxt(resolver: InetAddress, name: String, budgetMs: Long): List<String>? =
        withTimeoutOrNull(budgetMs + 250L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = budgetMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        var question = name
                        repeat(MAX_CNAME_HOPS + 1) { hop ->
                            val id = Random.nextInt(1, 0xFFFF)
                            val query = buildQuery(question, TYPE_TXT, id)
                            socket.send(
                                DatagramPacket(query, query.size, InetSocketAddress(resolver, DNS_PORT)),
                            )
                            // A stray datagram from an earlier question would otherwise be
                            // parsed as this one's answer; the id check rejects it and the
                            // loop asks again rather than believing it.
                            var parsed: DnsMessage? = null
                            while (parsed == null) {
                                val buffer = ByteArray(1500)
                                val response = DatagramPacket(buffer, buffer.size)
                                socket.receive(response)
                                parsed = parseResponse(buffer.copyOf(response.length), id)
                            }
                            val message = parsed ?: return@use null
                            if (message.truncated) {
                                // A TCP retry is out of scope for a record this small; saying
                                // so beats parsing a half-message as if it were whole.
                                Log.d(TAG, "TXT answer for '$question' was truncated; giving up on rung 2")
                                return@use null
                            }
                            if (message.txt.isNotEmpty()) return@use message.txt
                            val target = message.cname ?: return@use null
                            Log.d(TAG, "'$question' is a CNAME for '$target' (hop ${hop + 1})")
                            question = target
                        }
                        null
                    }
                }.onFailure {
                    // Includes SocketTimeoutException, which here is the *normal* way of
                    // saying "this resolver has nothing for us".
                    Log.d(TAG, "TXT query to ${resolver.hostAddress} failed: ${it.message}")
                }.getOrNull()
            }
        }

    // ---------------------------------------------------------------------------------------
    // Wire format. Pure from here down: no sockets, no clock, no Android — which is what makes
    // the parser testable at all, and the parser is where every interesting bug in this file
    // would live.
    // ---------------------------------------------------------------------------------------

    /** One resource record, reduced to what this client can act on. */
    internal data class DnsMessage(
        val id: Int,
        val rcode: Int,
        val truncated: Boolean,
        val addresses: List<InetAddress>,
        val txt: List<String>,
        val cname: String?,
    )

    /**
     * Builds a single-question query.
     *
     * Written by hand rather than pulled from a library: this is 30 bytes of a well-specified
     * header, and a DNS dependency for one lookup would be a dependency on the *shipping* path.
     * (`OverlayProbe` has an equivalent private builder for its debug query; the two are
     * deliberately not shared, because that one is a fixed-TYPE_A probe whose failure mode is
     * scoped to a debug screen.)
     */
    internal fun buildQuery(name: String, type: Int, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // flags: standard query, recursion desired
        out.write(0x00); out.write(0x01) // QDCOUNT
        out.write(0x00); out.write(0x00) // ANCOUNT
        out.write(0x00); out.write(0x00) // NSCOUNT
        out.write(0x00); out.write(0x00) // ARCOUNT
        for (label in name.trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            // A label is length-prefixed with a single byte, so 63 is the hard ceiling and a
            // longer one would corrupt every field after it rather than fail.
            if (bytes.isEmpty() || bytes.size > 63) return ByteArray(0)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)                  // root label
        out.write((type ushr 8) and 0xFF); out.write(type and 0xFF)
        out.write(0x00); out.write(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * Parses a response, keeping only the answers that came from *this* query.
     *
     * ⚠️ **The id check is the whole security story of a UDP lookup.** Anything on the path can
     * inject a datagram; matching the id (and requiring a response, not a query) is what makes
     * the answer plausibly ours. It is not DNSSEC — nothing here is — but a mismatched id is
     * dropped rather than parsed, which is why [parseResponse] returns null instead of an empty
     * message for it.
     */
    internal fun parseResponse(bytes: ByteArray, expectedId: Int): DnsMessage? {
        if (bytes.size < 12) return null
        val id = readU16(bytes, 0)
        if (id != expectedId) return null
        val flags = readU16(bytes, 2)
        val isResponse = (flags and 0x8000) != 0
        val truncated = (flags and 0x0200) != 0
        val rcode = flags and 0x000F
        if (!isResponse) return null

        val questions = readU16(bytes, 4)
        val answers = readU16(bytes, 6)

        var offset = 12
        repeat(questions) {
            offset = skipName(bytes, offset) ?: return null
            offset += 4 // QTYPE + QCLASS
            if (offset > bytes.size) return null
        }

        val addresses = mutableListOf<InetAddress>()
        val txt = mutableListOf<String>()
        var cname: String? = null

        repeat(answers) {
            val nameResult = readName(bytes, offset) ?: return@repeat
            offset = nameResult.second
            if (offset + 10 > bytes.size) return@repeat
            val type = readU16(bytes, offset)
            val length = readU16(bytes, offset + 8)
            val rdataStart = offset + 10
            val rdataEnd = rdataStart + length
            if (rdataEnd > bytes.size) return@repeat

            when (type) {
                TYPE_A -> if (length == 4) {
                    runCatching {
                        InetAddress.getByAddress(bytes.copyOfRange(rdataStart, rdataEnd))
                    }.getOrNull()?.let(addresses::add)
                }

                TYPE_TXT -> txt += parseTxtRdata(bytes, rdataStart, length)

                // Only the first alias is kept: the chain is followed by re-querying, and a
                // record with two CNAMEs is malformed anyway.
                TYPE_CNAME -> if (cname == null) {
                    readName(bytes, rdataStart)?.let { cname = it.first }
                }
            }
            offset = rdataEnd
        }

        return DnsMessage(id, rcode, truncated, addresses, txt, cname)
    }

    /**
     * The strings inside a `TXT` rdata, concatenated per RFC 1035.
     *
     * A `TXT` record is not one string — it is a sequence of length-prefixed ones, and a
     * publisher that writes more than 255 bytes (or simply chooses to split) emits several. An
     * implementation that read only the first would see `v1;pk=AAAA` and no port, which is a
     * *usable-looking* half-answer; joining is what keeps a split record from silently
     * truncating.
     */
    internal fun parseTxtRdata(bytes: ByteArray, start: Int, length: Int): String {
        val out = StringBuilder()
        var offset = start
        val end = start + length
        while (offset < end && offset < bytes.size) {
            val chunkLength = bytes[offset].toInt() and 0xFF
            offset += 1
            val chunkEnd = minOf(offset + chunkLength, end, bytes.size)
            if (chunkEnd <= offset) break
            out.append(String(bytes, offset, chunkEnd - offset, Charsets.UTF_8))
            offset = chunkEnd
        }
        return out.toString()
    }

    /** Advances past a name, following compression pointers, and returns the next offset. */
    internal fun skipName(bytes: ByteArray, start: Int): Int? = readName(bytes, start)?.second

    /**
     * Reads a name, returning it and the offset just past it **in the original buffer**.
     *
     * ⚠️ **The two offsets are not the same number once a pointer is followed**, and conflating
     * them is the classic way to write this parser wrong: the returned offset must be where the
     * *record* continues, while the name itself may have come from anywhere earlier in the
     * packet. Hence the separate `resume` value.
     *
     * ⚠️ **The jump cap is load-bearing, not defensive.** A response can legitimately point
     * backwards, so a walk that simply follows pointers will loop forever on a malicious or
     * corrupt packet — and this runs on the UI-adjacent cold path, where a hang is worse than a
     * wrong answer.
     */
    private fun readName(bytes: ByteArray, start: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var offset = start
        var resume: Int? = null
        var jumps = 0

        while (offset < bytes.size) {
            val length = bytes[offset].toInt() and 0xFF
            if (length == 0) {
                offset += 1
                return labels.joinToString(".") to (resume ?: offset)
            }
            if ((length and 0xC0) == 0xC0) {
                if (offset + 1 >= bytes.size) return null
                val pointer = ((length and 0x3F) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
                if (resume == null) resume = offset + 2
                if (++jumps > MAX_NAME_JUMPS) return null
                offset = pointer
                continue
            }
            // A length above 63 that is not a pointer is not a valid label.
            if ((length and 0xC0) != 0) return null
            if (offset + 1 + length > bytes.size) return null
            labels += String(bytes, offset + 1, length, Charsets.US_ASCII)
            offset += 1 + length
        }
        return null
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
