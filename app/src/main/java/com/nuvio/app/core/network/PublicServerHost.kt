package com.nuvio.app.core.network

/**
 * TV compat layer — **not** part of the overlay port.
 *
 * The overlay filters its pin candidates through `isPublicServerHost` so that a discovered
 * `.local` name or an RFC1918 address can never itself become a pin target.
 *
 * ⚠️ **This delegates rather than reimplements.** The TV already carries a predicate of exactly
 * that name, at `com.nuvio.tv.domain.model.isPublicServerHost`, with the same body the mobile fork
 * uses: reject `localhost`, `.local`, `::1`, `127.`, `10.`, `192.168.`, `172.16`–`172.31` and
 * `169.254.`; accept everything else, **including unparseable input**. It is `internal`, which is
 * why this seam is needed at all — but the overlay lives in the same Gradle module, so the
 * `internal` original is directly callable from here.
 *
 * ⚠️ The two are not bit-identical on one input, and it does not matter: mobile parses with Ktor
 * (`Url("nonsense")` succeeds and claims host `localhost`, so it is *rejected*), while the TV
 * parses with OkHttp (`toHttpUrlOrNull("nonsense")` is null, so it is *accepted*). Both callers
 * — `derivePinnableHosts` here, and the overlay's own `localServerHostCandidates()` path — pass
 * the result through `hostOf`, whose load-bearing `contains("://")` guard returns null for exactly
 * that scheme-less input. The set that reaches the pin is the same either way, and the ported
 * `LocalServerHostsTest` case `ignores blanks and unparseable values` passes under both.
 */
internal fun isPublicServerHost(url: String): Boolean =
    com.nuvio.tv.domain.model.isPublicServerHost(url)
