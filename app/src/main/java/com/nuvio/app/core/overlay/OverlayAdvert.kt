package com.nuvio.app.core.overlay

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
 * [lanName] and [wanName] are the server's two **discovery names** — not addresses, and not part
 * of the tuple. They are carried here because the advert is one publication of one server, and a
 * client that learned the box by mDNS is otherwise stranded the moment it leaves the network the
 * advert came from. See [OverlayDiscoveryNames] for what the ladder does with them.
 */
internal data class OverlayMdnsAdvert(
    val address: InetAddress,
    val serverPublicKeyBase64: String?,
    val port: Int?,
    val serviceName: String?,
    val lanName: String? = null,
    val wanName: String? = null,
)

/**
 * The two names a server publishes alongside its tuple, so a client that found it on one network
 * can still find it on the next.
 *
 * ⚠️ **These are names, never addresses, and that is the whole point.** The rest of the tuple is
 * a property of the *deployment* — the key, the port, both of which are true wherever the client
 * stands. The endpoint the ladder finally accepts is a **literal address**, and a literal learned
 * on the LAN is meaningless the moment the phone leaves it: at home the SRV target is
 * `192.168.68.65`, and off it that address routes nowhere. The names are the part of discovery
 * that stays true across the move. `lan=` resolves to the server's private address at home,
 * `wan=` resolves publicly; *which one works is not a property of the name but of where the
 * client is standing*, which is why [inOrder] tries them rather than deciding between them.
 *
 * ⚠️ **A pair, replaced wholesale, not two independently-updated fields.** Both publication
 * channels carry both names from one server at one instant, so a publication carrying only one is
 * the server's *current truth* — a name that has been withdrawn — rather than a half-heard
 * message. Merging field-by-field would keep a withdrawn `wan=` alive for the life of the
 * process, which is precisely the stale-discovery failure this whole subsystem exists to avoid.
 */
internal data class OverlayDiscoveryNames(
    val lan: String?,
    val wan: String?,
) {
    /** True when the publication carried neither name, which is the case for a pre-`#66` server. */
    val isEmpty: Boolean get() = lan == null && wan == null

    /**
     * The names to climb, in the order to climb them — **LAN first unless [preferLan]**.
     *
     * At home `lan=` is the reachable one and `wan=` is not (the public address does not hairpin —
     * architecture §7); off-LAN the reverse holds. Trying `lan=` first therefore costs one failed
     * probe at home and *nothing* away from it, because a private address on a foreign network
     * fails immediately rather than after a timeout. The reverse order would put the working name
     * behind a guaranteed-dead one on every cold start at home, which is the common case.
     *
     * ⚠️ **`preferLan` exists because that reasoning assumes the gate can still tell the names
     * apart.** A private address fails fast on a foreign network only while there is something to
     * fail *against*; with the WAN forward closed — the in-app tunnel's whole point, since it
     * needs nothing but UDP 51820 — nothing answers for either name, and the order becomes the
     * only thing doing the choosing. [OverlayEndpointDiscovery] therefore passes the network it is
     * really on, so the name that suits it is tried first *and* is the one kept when no name
     * proves itself. The default is the published order, which is what a caller with no network to
     * read should get.
     *
     * Deduplicated, because a publisher that set both fields to the same name would otherwise
     * cost a second resolve and a second failed probe — on the failure path, for an answer the
     * ladder already has. Trying one name twice is never a different question. Deduplication runs
     * before the reversal, so the result is deduplicated either way round.
     */
    fun inOrder(preferLan: Boolean = true): List<String> {
        val byPreference = listOfNotNull(lan, wan).distinct()
        return if (preferLan) byPreference else byPreference.asReversed()
    }
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
 * [lanName] and [wanName] ride along for the same reason and are nullable for the same reason:
 * a server older than `#66` publishes neither, and that must read as "no names" rather than as a
 * name that happens to be blank. See [OverlayDiscoveryNames].
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
    val lanName: String? = null,
    val wanName: String? = null,
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

    companion object {
        /** The WG listen port when the advert does not name one. The POC's port, and the default. */
        const val DEFAULT_PORT = 51820
    }
}

/** Characters that can never appear in a hostname, and so can never appear in a discovery name. */
private const val ILLEGAL_NAME_CHARS = "/:=;\\@?#"

/**
 * Parses the mDNS advert's TXT attribute map.
 *
 * The publisher is `avahi-publish-service` on the server (`overlay/`), which emits
 * `addr=10.77.0.1`, `pubkey=<base64>`, `port=51820`, `v=1` as **separate** TXT strings; Android
 * hands them back as a `Map<String, ByteArray>`, so the split has already happened by the time
 * this is called.
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
 * The record the POC publishes is one string:
 *
 * ```
 * v1;pk=<base64>;port=51820;prov=0
 * ```
 *
 * A DNS `TXT` record is a *sequence* of length-prefixed strings, though, and a resolver is free
 * to return the same logical record split across several of them — or to return several
 * records. So [strings] is joined before splitting: one `;`-delimited field list, however the
 * transport chose to frame it. Joining with `;` rather than `""` is deliberate — a split that
 * lands mid-field would otherwise fuse two fields into one nonsense token, while a separator
 * that is already the field delimiter turns that case into two ordinary fields.
 *
 * Unknown fields (`v`) are ignored rather than rejected: the format is versioned precisely so a
 * server can add fields without a client update, and a client that failed on an unrecognised token
 * would make that impossible.
 *
 * ⚠️ **`lan=` and `wan=` are the fields that version note was written for.** They were added
 * after `v1` shipped and the version was deliberately **not** bumped — a server may add a field
 * without a client update, and an older client reading this record sees two unknown tokens and
 * parses exactly what it did before. Bumping to `v2` would have turned every already-deployed
 * client into one that rejects the record, which is the failure the versioning rule forbids.
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
 */
private fun advertTupleFrom(field: (String) -> String?): OverlayAdvertTuple {
    fun value(name: String): String? = field(name)?.takeIf { it.isNotEmpty() }

    return OverlayAdvertTuple(
        serverPublicKeyBase64 = value("pk") ?: value("pubkey"),
        port = value("port").orEmpty().toPortOrNull(),
        // ⚠️ Validated, not taken on trust — `validServerKeyOrNull` owns the 32-byte rule for this
        // package, and a `ppk` that is the wrong length would fail the handshake in a way that
        // reads as an impostor rather than as the typo it is.
        provisioningPublicKeyBase64 = validServerKeyOrNull(value("ppk")),
        provisioningEnabled = value("prov").toProvisioningFlagOrNull(),
        provisioningPortOverride = value("pport").orEmpty().toPortOrNull(),
        lanName = validDiscoveryNameOrNull(value("lan")),
        wanName = validDiscoveryNameOrNull(value("wan")),
    )
}

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
 * ⚠️ **A name is not an authority, and this is where that is enforced.** `lan=`/`wan=` carry a
 * **hostname** — the `A` record does the resolving — so a value carrying a scheme, a port, a path
 * or whitespace is a publisher bug rather than something to be salvaged by guessing which part
 * was meant. Rejecting is the honest reading, and it is validated here rather than at the
 * resolver for the same reason [toPortOrNull] is: a garbage value would otherwise become a
 * resolver call on the ladder's *failure* path, which is the one place a stray timeout is least
 * affordable.
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
