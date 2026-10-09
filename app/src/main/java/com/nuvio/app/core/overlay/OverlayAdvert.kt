package com.nuvio.app.core.overlay

import android.util.Log
import java.net.InetAddress

/**
 * One resolved mDNS advert — the address *and* the tunnel tuple together.
 *
 * ⚠️ **This exists because `OverlayLocalDiscovery` originally kept only the address**, which was
 * correct while Tier 1 was tunnel-free and is wrong now that the tunnel carries the LAN too
 * (architecture §4.1). The advert always carried the tuple; nothing read it. Rather than browse
 * twice — once for the pin, once for the endpoint, on the same six-second window — the browse
 * publishes this and both consumers take what they need.
 *
 * [address] is the **SRV target's** address, i.e. where the server is on the network the client
 * is on. That is the endpoint's host: the advert's `addr=10.77.0.1` is the address the client
 * *takes on the overlay*, which is a different plane entirely and is never dialled.
 *
 * ⚠️ **[tuple] is carried whole rather than copied field by field.** It is the advert as parsed,
 * and it is the same shape the DNS rung produces, so the two channels hand the ladder one type
 * instead of two that agree only by convention. An earlier version of this class unpacked the two
 * fields the tunnel needed, which meant a second consumer of the same advert had nothing to read
 * and the two channels' field sets could drift one edit at a time.
 *
 * ⚠️ **`lan`/`wan` are ADDRESSES in v2, not names.** This advert used to publish two DuckDNS
 * hostnames and the ladder resolved them; a client with no working resolver — the case mDNS exists
 * to serve — could do nothing with a name. The addresses are dialled directly and only `svc` is
 * ever a name. See [OverlayDiscoveryTargets] for what the ladder does with them.
 */
internal data class OverlayMdnsAdvert(
    val address: InetAddress,
    val serviceName: String?,
    val tuple: OverlayAdvertTuple,
)

/**
 * One plane a v2 publication names: an address, and the two ports that address serves.
 *
 * ⚠️ **Both ports, because one address carries two protocols.** [tunnelPort] is the WireGuard UDP
 * port, [provisioningPort] the enroll TCP port. A plane naming only one would force a client to
 * infer the other from a global default — which is exactly the drift the explicit
 * `lanport`/`wanport`/`lanpport`/`wanpport` fields exist to remove. Both are resolved through the
 * record's own generic `port`/`pport` when the per-plane field is absent, so a record that names
 * only the generic ports still yields usable planes.
 */
internal data class OverlayPlane(
    val host: String,
    val tunnelPort: Int,
    val provisioningPort: Int,
    /** True for the address that routes at home. Only logging and pin placement read this. */
    val isLan: Boolean,
)

/**
 * The dial targets a v2 publication names — what the ladder hands to a dialler, as opposed to the
 * wire shape [OverlayAdvertTuple] parses.
 *
 * ⚠️ **`svc` is the only *name* here; `lan`/`wan` are addresses and are dialled directly.** That is
 * the owner's design: a client reads the two addresses out of the record and tries the LAN one
 * first, then the WAN one, "and [does not] care about dns for the fqdn". The service name survives
 * because a certificate is issued for a *name* — the URL, the SNI and the certificate check all
 * stay on `svc` while the address underneath changes. See `OverlayPinRegistry`, which is where
 * that substitution actually happens.
 *
 * ⚠️ **Order is the whole of the choice, and it is fixed: LAN first.** There used to be a
 * `preferLan` argument here, decided per network by an egress-address comparison and an on-link
 * test. Both are gone — not because they were wrong, but because they answered a question that no
 * longer exists. They chose between two *names*, only one of which resolved usefully depending on
 * where the client stood. Two literal addresses need no such choice: a private address on a
 * foreign network fails immediately rather than after a timeout, so trying `lan` first costs one
 * fast refusal away from home and nothing at all at home. Fixed order, no tiebreak, and no HTTP
 * round trip spent answering it.
 *
 * ⚠️ **A pair, replaced wholesale, never merged field by field.** Both publication channels carry
 * the whole tuple from one server at one instant, so a publication carrying only one address is
 * the server's *current truth* — an address that has been withdrawn — rather than a half-heard
 * message. Merging would keep a withdrawn `wan=` alive for the life of the process, which is
 * precisely the stale-discovery failure this whole subsystem exists to avoid.
 *
 * ⚠️ **None of this authenticates anything.** Every field is published in plaintext DNS and
 * broadcast over mDNS, and both channels are spoofable. Only `pk`/`ppk` are trust anchors, and
 * they are checked in the handshake, never here.
 */
internal data class OverlayDiscoveryTargets(
    /** `svc` — the FQDN every URL, SNI and certificate check stays on. Null on a record without it. */
    val svcName: String?,
    /** The planes to dial, LAN first. See the ordering note on this class. */
    val planes: List<OverlayPlane>,
) {
    /** True when the publication named no plane at all — a record with neither address. */
    val isEmpty: Boolean get() = planes.isEmpty()

    /**
     * The planes, in the order to dial them.
     *
     * A named accessor rather than a bare list property so the *ordering* is visible at every call
     * site: it is a decision this type owns, and a caller that read `.planes` might reasonably
     * think the list was in wire order.
     */
    fun inOrder(): List<OverlayPlane> = planes
}

/**
 * The part of a discovery advert that is a **tunnel**, as opposed to an address.
 *
 * Both publication channels carry this and they spell it differently — mDNS uses one
 * `key=value` TXT attribute per field, DuckDNS packs them into one semicolon-separated string —
 * so both are parsed into this one shape and nothing downstream has to know which rung it came
 * from.
 *
 * Both fields are nullable, and that is a statement about the *wire*, not about what is
 * usable: a channel can publish an address without a key (the pre-2026-10-05 mDNS advert did
 * exactly that), and the honest representation of "there was no key in the advert" is null
 * rather than a guess.
 *
 * [svcName], [lanAddress] and [wanAddress] ride along for the same reason and are nullable for the
 * same reason: a record that does not name them must read as "no address" rather than as one that
 * happens to be blank — and, for `lan`/`wan`, a value that is not a dotted quad reads as *absent*
 * rather than as a hostname to go and resolve. See [targets] for what they become.
 *
 * ⚠️ **[version] is not decoration — without it the whole tuple is refused.** `lan`/`wan` changed
 * meaning between v1 (names to resolve) and v2 (addresses to dial), and a v1 value is a perfectly
 * well-formed string that under the v2 rule parses to *no* planes. Reading that as "nothing was
 * published" would be a lie about a record that published two names. So the version is required
 * and checked, and a record missing it is refused outright — see [advertTupleFrom].
 */
internal data class OverlayAdvertTuple(
    val serverPublicKeyBase64: String?,
    val port: Int?,
    /**
     * `ppk` — the **provisioning** public key, which is deliberately not [serverPublicKeyBase64].
     *
     * The tunnel's key lives on the host under `/etc/boomio-overlay`, and bsc is not given it: that
     * store also holds client private keys, so handing bsc the tunnel's identity would make a
     * container compromise a tunnel compromise. The provisioning handshake therefore has a keypair
     * of its own, and this is its public half. Null when the record predates the field — a client
     * can still dial, but it cannot verify who answered, and must say so rather than proceed
     * silently.
     */
    val provisioningPublicKeyBase64: String? = null,
    /**
     * `prov` — the server's own kill switch, as the record states it. **Null means the record does
     * not say**, which is not the same as `false`.
     */
    val provisioningEnabled: Boolean? = null,
    /**
     * A `pport` override, for the escape hatch where provisioning moves to its own number. Unused
     * today: [provisioningPort] resolves to [port] when this is null.
     */
    val provisioningPortOverride: Int? = null,
    /**
     * `v` — the tuple's own version, as the record states it. **Null means the record did not say,
     * which [advertTupleFrom] refuses**, so a non-null value here is always [REQUIRED_VERSION].
     */
    val version: Int? = null,
    /** `svc` — the service FQDN. The only *name* in the tuple, and the one every URL keeps. */
    val svcName: String? = null,
    /** `lan` — the server's address at home, as a **literal**, never a name. */
    val lanAddress: String? = null,
    /** `wan` — the server's public address, as a **literal**, never a name. */
    val wanAddress: String? = null,
    /** `lanport` — the WireGuard UDP port at [lanAddress]. Null falls back to [port]. */
    val lanPort: Int? = null,
    /** `wanport` — the WireGuard UDP port at [wanAddress]. Null falls back to [port]. */
    val wanPort: Int? = null,
    /** `lanpport` — the enroll TCP port at [lanAddress]. Null falls back to [provisioningPort]. */
    val lanProvisioningPort: Int? = null,
    /** `wanpport` — the enroll TCP port at [wanAddress]. Null falls back to [provisioningPort]. */
    val wanProvisioningPort: Int? = null,
) {
    /**
     * Whether the record says a client may provision.
     *
     * ⚠️ **Only an explicit `prov=1` opens the door; absent is closed.** That mirrors the server,
     * whose own flag (`<OVERLAY_DATA_DIR>/prov`) fails closed on a missing or unreadable file, and
     * it is the right default for a switch whose whole job is to be off until an operator turns it
     * on. A record that simply never mentioned `prov` is unstated, not permissive.
     */
    val offersProvisioning: Boolean get() = provisioningEnabled == true

    /**
     * Where to dial provisioning: `pport` when the record names one, else the tunnel's own port.
     *
     * ⚠️ **One number, two protocols** — UDP carries the tunnel, TCP carries provisioning — which
     * is why the default is not a second constant. A malformed `pport` reads as absent and falls
     * back to [port], the same way a malformed `port` reads as absent.
     */
    val provisioningPort: Int? get() = provisioningPortOverride ?: port

    /**
     * The same publication as the ladder dials it: the addresses, each with the ports actually used.
     *
     * ⚠️ **The one difference from the fields above is that nothing here is nullable.** This class
     * is the *wire* view, where null honestly means "the record did not say"; a dial target with a
     * null port is not something anyone can use, so the absent case is resolved here, once, rather
     * than at every dial site. The generic `port`/`pport` supply the per-plane defaults in turn, so
     * a v2 record naming only the generic ports still yields both planes rather than none.
     *
     * Only planes the record actually addressed appear: a record carrying `lan` alone yields one
     * plane, never a second one guessed from the other field. Ordering, and why it is fixed, is
     * [OverlayDiscoveryTargets]'s to explain.
     */
    val targets: OverlayDiscoveryTargets
        get() {
            val tunnel = port ?: DEFAULT_PORT
            val enroll = provisioningPort ?: tunnel
            return OverlayDiscoveryTargets(
                svcName = svcName,
                planes = listOfNotNull(
                    lanAddress?.let { OverlayPlane(it, lanPort ?: tunnel, lanProvisioningPort ?: enroll, isLan = true) },
                    wanAddress?.let { OverlayPlane(it, wanPort ?: tunnel, wanProvisioningPort ?: enroll, isLan = false) },
                ),
            )
        }

    companion object {
        /** The WG listen port when the advert does not name one. The POC's port, and the default. */
        const val DEFAULT_PORT = 51820

        /**
         * The only tuple version this client can read.
         *
         * ⚠️ **Exact, not a minimum, and it is enforced in [advertTupleFrom].** `lan`/`wan` are not
         * additive fields — they changed meaning — so a client that "accepted >= 2" would still be
         * wrong about a hypothetical v3 that moved them again. Refusing anything else is the only
         * reading that cannot silently mis-parse; when a v3 exists this constant is the one line
         * that has to be revisited, which is the point of putting the gate at the parser.
         */
        const val REQUIRED_VERSION = 2
    }
}

/** Characters that can never appear in a hostname, and so can never appear in a discovery name. */
private const val ILLEGAL_NAME_CHARS = "/:=;\\@?#"

/**
 * Parses the mDNS advert's TXT attribute map.
 *
 * The publisher is `avahi-publish-service` on the server (`overlay/`), which emits `v=2`,
 * `svc=…`, `lan=…`, `wan=…`, `port=…`, `pport=…`, the four per-plane ports, `pk=`/`ppk=` and
 * `prov=` as **separate** TXT strings; Android hands them back as a `Map<String, ByteArray>`, so
 * the split has already happened by the time this is called.
 *
 * ⚠️ **The unit's arguments are generated from the file the publisher writes**
 * (`/var/lib/boomio-overlay/advert.env`), so what appears here is what DNS was told, not a second
 * hand-maintained copy. The two channels cannot drift; a value that is wrong in one is wrong in
 * both, which is the property that makes a disagreement between them a real signal.
 *
 * ⚠️ **`pk` and `pubkey` are both accepted on purpose.** The DuckDNS record uses `pk` and the
 * mDNS advert uses `pubkey`, and a ladder whose rungs disagree about a field name would fail
 * rung 2 for a reason that has nothing to do with DNS. Accepting both here costs one `?:` and
 * removes a whole class of "it works on the LAN and not off it" bug.
 *
 * Every value is trimmed and every blank is treated as absent: an advert is written by a shell
 * script, and `pubkey=` with nothing after it must read as "no key" rather than as a key that
 * happens to be the empty string.
 */
internal fun parseMdnsAdvertTxt(attributes: Map<String, ByteArray>?): OverlayAdvertTuple {
    if (attributes.isNullOrEmpty()) return OverlayAdvertTuple(null, null)
    val fields = attributes.mapValues { (_, raw) ->
        runCatching { raw.toString(Charsets.UTF_8) }.getOrDefault("").trim()
    }
    return advertTupleFrom { fields[it] }
}

/**
 * Parses the `TXT` rdata a DNS channel returns, in either of the two shapes it arrives in.
 *
 * The record the publisher writes is one string:
 *
 * ```
 * v=2;svc=boomio.duckdns.org;lan=192.168.68.65;wan=203.0.113.7
 *   ;port=51820;pport=51820;lanport=51820;wanport=51820;lanpport=51820;wanpport=51820
 *   ;pk=<base64>;ppk=<base64>;prov=1
 * ```
 *
 * A DNS `TXT` record is a *sequence* of length-prefixed strings, though, and a resolver is free
 * to return the same logical record split across several of them — or to return several
 * records. So [strings] is joined before splitting: one `;`-delimited field list, however the
 * transport chose to frame it. Joining with `;` rather than `""` is deliberate — a split that
 * lands mid-field would otherwise fuse two fields into one nonsense token, while a separator
 * that is already the field delimiter turns that case into two ordinary fields.
 *
 * Unknown **additive** fields are ignored rather than rejected: the format is versioned so a server
 * can add a field without a client update, and a client that failed on an unrecognised token would
 * make that impossible. `v` itself is the exception, and the honest one — it is not an extra field
 * beside the others but a claim about the shape of all of them, so it is required and checked.
 *
 * ⚠️ **`v=2` is a cut, and the older spelling of the same change is why it has to be.** `lan=` and
 * `wan=` were first added *without* a version bump — correctly, as an additive field: a v1 client
 * saw two unknown tokens and parsed exactly what it did before. v2 is not that. The two fields
 * changed **meaning**, from names to resolve into addresses to dial, so there is no reading of a v1
 * record that this client can act on — and the wrong reading is invisible, because a hostname
 * simply fails the dotted-quad check and the record reads as though nothing were published. The
 * versioning rule is unchanged; this is the case it does not cover, and the reason `v` is now
 * load-bearing rather than decorative.
 */
internal fun parseDnsTxtRecord(strings: List<String>): OverlayAdvertTuple {
    if (strings.isEmpty()) return OverlayAdvertTuple(null, null)
    val fields = strings.joinToString(";")
        .split(';')
        .mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            token.substring(0, separator).trim().lowercase() to token.substring(separator + 1).trim()
        }
        .toMap()
    return advertTupleFrom { fields[it] }
}

/**
 * The one place a discovery advert becomes an [OverlayAdvertTuple].
 *
 * Both rungs land here so they cannot disagree about a field, which is the same argument that keeps
 * `pk` and `pubkey` interchangeable below: the mDNS advert and the DuckDNS record are published by
 * different programs on the same box, and a ladder whose rungs read different names would fail
 * rung 2 for a reason that has nothing to do with DNS — the "works on the LAN and not off it"
 * class of bug.
 *
 * Every value is a trimmed string or null. Blank is treated as absent throughout, because these
 * fields are written by shell scripts and `ppk=` with nothing after it must read as "no key"
 * rather than as a key that is the empty string.
 *
 * ⚠️ **This is also where the version gate lives, and it sits here precisely because this is the
 * one funnel.** Both rungs are published by different programs on the same box, and a gate applied
 * to one channel would leave the other half-parsing; applied to the function they share, a record
 * cannot reach a consumer without having passed it.
 */
private fun advertTupleFrom(field: (String) -> String?): OverlayAdvertTuple {
    fun value(name: String): String? = field(name)?.takeIf { it.isNotEmpty() }

    // ⚠️ A REFUSAL, not a partial read — see the note on [OverlayAdvertTuple.version]. The failure
    // this prevents is silent: a v1 `lan=` is a hostname, which under the v2 rule parses to no
    // address at all, so a client that read it anyway would report "nothing published" about a
    // record that published two names and could sit in that state indefinitely. An empty tuple
    // instead makes the ladder fall through to its baked defaults, which is a state the client is
    // built to survive. Refusing to guess is the whole of the design.
    val statedVersion = value("v")
    val version = statedVersion?.toIntOrNull()
    if (version != OverlayAdvertTuple.REQUIRED_VERSION) {
        Log.w(
            TAG,
            "Discovery advert refused: version ${statedVersion ?: "unstated"}, " +
                "expected ${OverlayAdvertTuple.REQUIRED_VERSION}",
        )
        return OverlayAdvertTuple(null, null)
    }

    return OverlayAdvertTuple(
        serverPublicKeyBase64 = value("pk") ?: value("pubkey"),
        port = value("port").orEmpty().toPortOrNull(),
        // ⚠️ Validated, not taken on trust — `validServerKeyOrNull` owns the 32-byte rule for this
        // package, and a `ppk` that is the wrong length would fail the handshake in a way that
        // reads as an impostor rather than as the typo it is.
        provisioningPublicKeyBase64 = validServerKeyOrNull(value("ppk")),
        provisioningEnabled = value("prov").toProvisioningFlagOrNull(),
        provisioningPortOverride = value("pport").orEmpty().toPortOrNull(),
        version = version,
        // `svc` is a NAME — the one field here that is — because a certificate is issued for a
        // name; everything under it is an address, dialled as written.
        svcName = validDiscoveryNameOrNull(value("svc")),
        lanAddress = validDottedQuadOrNull(value("lan")),
        wanAddress = validDottedQuadOrNull(value("wan")),
        lanPort = value("lanport").orEmpty().toPortOrNull(),
        wanPort = value("wanport").orEmpty().toPortOrNull(),
        lanProvisioningPort = value("lanpport").orEmpty().toPortOrNull(),
        wanProvisioningPort = value("wanpport").orEmpty().toPortOrNull(),
    )
}

/** Log tag for this file's one diagnostic: a publication this client cannot read. */
private const val TAG = "OverlayAdvert"

/**
 * A usable TCP/UDP port number, or null.
 *
 * ⚠️ **A `Long` range check, not an `Int` one.** `toIntOrNull` rejects overflow, but
 * `"99999999999"` is well inside a `Long` and would silently wrap if it were narrowed first.
 * The check happens before any conversion, so a nonsense port is rejected rather than truncated
 * into a plausible-looking one.
 */
internal fun String.toPortOrNull(): Int? =
    toLongOrNull()?.takeIf { it in 1..65535 }?.toInt()

/**
 * The `prov` kill switch as a client reads it, or null when the record does not say.
 *
 * ⚠️ **Anything unrecognised is null, and null is closed.** `prov` is a switch whose entire job is
 * to be off until an operator turns it on, so the honest reading of `prov=maybe` is "I do not know
 * what this means", not "probably fine". The server takes the same position on its own flag — a
 * missing or unreadable `<OVERLAY_DATA_DIR>/prov` means provisioning is OFF.
 *
 * Nullable receiver so an absent field and an empty one are the same answer, which keeps the call
 * site from having to spell out the difference.
 */
internal fun String?.toProvisioningFlagOrNull(): Boolean? = when (this?.trim()?.lowercase()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
}

/**
 * A discovery name a client could actually resolve, or null.
 *
 * ⚠️ **A name is not an authority, and this is where that is enforced.** `svc=` carries a
 * **hostname** — the URL, the SNI and the certificate check all stay on it, while the address
 * underneath is supplied by a pin — so a value carrying a scheme, a port, a path or whitespace is
 * a publisher bug rather than something to be salvaged by guessing which part was meant. Rejecting
 * is the honest reading, and it is validated here rather than at the point of use for the same
 * reason [toPortOrNull] is: a garbage value would otherwise become a request to a nonsense host on
 * a *failure* path, which is the one place a stray timeout is least affordable.
 *
 * ⚠️ **`svc` is the only field in the tuple this still applies to.** `lan`/`wan` used to be names
 * and are now addresses, so they go through [validDottedQuadOrNull] instead — the two validators
 * exist as a pair, and a field that moved from one to the other is exactly the v1→v2 change.
 *
 * A single trailing dot is dropped, because `boomio.duckdns.org.` and `boomio.duckdns.org` are
 * one name written two ways, and only one of them compares equal to what a log or a test
 * expects. Interior dots are the name and are untouched. The result is lowercased for the same
 * reason — DNS is case-insensitive, so two spellings of one name should not be two entries in a
 * cache or two lines in a log.
 */
internal fun validDiscoveryNameOrNull(value: String?): String? {
    val candidate = value?.trim()?.removeSuffix(".")?.lowercase().orEmpty()
    if (candidate.isEmpty()) return null
    if (candidate.any { it.isWhitespace() || it in ILLEGAL_NAME_CHARS }) return null
    return candidate
}

/**
 * An IPv4 literal a client could dial as written, or null.
 *
 * ⚠️ **Strictly four octets, each 0–255, decimal, and nothing else.** This is the check that makes
 * the v1→v2 change safe in the direction that matters: `lan=` used to carry a *hostname*, and under
 * the v2 rule a hostname has to read as "no address published" rather than as a literal that some
 * resolver will be asked about. The contrast with [validDiscoveryNameOrNull] is the whole point —
 * a value that is a valid name and a value that is a valid address are disjoint sets here, so a
 * record from either era lands in exactly one of them and neither can be mistaken for the other.
 *
 * ⚠️ **This is deliberately not an IP-address parser, and deliberately not IPv6.** The publisher
 * emits exactly this shape — the LAN literal is written by the script, the WAN one comes from a
 * STUN `XOR-MAPPED-ADDRESS` whose IPv4 family has already been checked — so accepting more than the
 * publisher can produce would only widen what a *spoofed* record could put in front of the dialler.
 * V4 is also the family the pins and the pin registry work in; admitting a v6 literal here would
 * produce a plane the rest of the ladder cannot carry.
 *
 * Leading zeros are rejected rather than reinterpreted. `192.168.068.65` is not a spelling any
 * publisher produces, and reading it as `192.168.68.65` would be this parser inventing an address
 * that was never published — the same class of quiet correction the version gate exists to avoid.
 */
internal fun validDottedQuadOrNull(value: String?): String? {
    val candidate = value?.trim().orEmpty()
    if (candidate.isEmpty()) return null
    val octets = candidate.split('.')
    if (octets.size != 4) return null
    val wellFormed = octets.all { octet ->
        octet.isNotEmpty() &&
            octet.length <= 3 &&
            octet.all { it.isDigit() } &&
            // Length is already bounded at 3, so this cannot overflow.
            octet.toInt() <= 255 &&
            // `007` is a different octet from `7` in some notations; here it is simply not a
            // spelling the publisher emits, so it is not one this client accepts.
            (octet.length == 1 || octet[0] != '0')
    }
    return candidate.takeIf { wellFormed }
}

/**
 * A WireGuard public key the tunnel could actually use, or null.
 *
 * ⚠️ **Validated here, at the edge, rather than at `IpcSet`.** A key that is the wrong length
 * is the classic silent failure of this whole subsystem: `android.util.Base64` is lenient about
 * stray characters, so a truncated paste decodes cleanly to the wrong bytes, the handshake
 * never completes, and the only symptom is a tunnel that does not come up. Rejecting it here
 * means the ladder keeps walking rungs instead of stopping on a candidate that cannot work —
 * see [decodeWireGuardKey], which owns the 32-byte rule for the whole package.
 */
internal fun validServerKeyOrNull(value: String?): String? {
    val candidate = value?.trim().orEmpty()
    if (candidate.isEmpty()) return null
    return candidate.takeIf { decodeWireGuardKey(it) != null }
}
