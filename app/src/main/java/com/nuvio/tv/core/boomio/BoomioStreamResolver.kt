package com.nuvio.tv.core.boomio

import android.util.Log
import com.nuvio.app.core.mtls.hostIsOurs
import com.nuvio.app.core.overlay.hostOf
import com.nuvio.app.core.overlay.localServerHosts
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NetworkMeter
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Stream
import com.squareup.moshi.Moshi
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val TAG = "BoomioStreamResolver"

/**
 * Which client a `/find` call to [host] must travel over.
 *
 * ⚠️ **This is the whole reason the certificate-bearing client is `@Named` rather than the app's
 * default.** A client certificate is a device credential, and `withClientCertificate()` attaches it
 * to a *client* — so handing one to every request would offer this device's certificate to every host
 * the resolver was ever pointed at, including a third-party addon or subtitle server that has no
 * business knowing this device exists. Scoping happens here, per request, because that is the
 * granularity at which "is this host ours" is a question with an answer.
 *
 * ⚠️ **The predicate is [hostIsOurs] — host *identity* — and deliberately not
 * `OverlayPinRegistry.isPinnedHost`.** The pin answers "may this name be repointed at a discovered
 * LAN address", and it is *absent* on the LAN by construction; gating on it would therefore stop the
 * certificate being presented exactly when the device is at home. The identity predicate instead
 * answers "does the user's own server answer for this name", which is true on both sides of the pin.
 *
 * The rule itself is not re-derived here: [hostIsOurs] already owns it, deliberately mirroring the
 * overlay's `derivePinnableAddonHosts`, and it already covers the media-plane names (`bss-dav`,
 * `bss-tor`, …) that appear in no configuration but do share the server's registrable domain.
 *
 * [serverHosts] is a parameter rather than a call to `localServerHosts()` so the rule can be
 * asserted without a device, and so this function decides *nothing* about which hosts exist — only
 * that the two sets select different clients.
 */
internal fun resolveHttpClientFor(
    host: String,
    serverHosts: Set<String>,
    // The validating client with no certificate — the fallback for every host that is not ours.
    plain: OkHttpClient,
    // The certificate-bearing twin. See `NetworkModule.provideBoomioClientCertificateOkHttpClient`.
    certificate: OkHttpClient
): OkHttpClient = if (hostIsOurs(host, serverHosts)) certificate else plain

/**
 * [resolveHttpClientFor] as an OkHttp [Call.Factory], for call sites that never build their own
 * `Call` — Retrofit and Coil both hand a factory the request and take back a call, so the host check
 * has to live *inside* the factory rather than beside the call. Without this the only shape
 * available to those call sites is one client for the whole Retrofit/ImageLoader, which is exactly
 * the unconditional attach this file exists to prevent.
 *
 * ⚠️ [serverHosts] is a lambda rather than a set because `localServerHosts()` is read *live*: the
 * addon catalogue arrives seconds after the first browse, and a set captured once when the DI graph
 * was built would be the pre-browse one for the life of the process. See `LocalServerHosts.kt`.
 *
 * ⚠️ Written as an explicit `object` rather than a trailing lambda so it does not depend on
 * `Call.Factory` being a Kotlin `fun interface` — the one form that converts from a lambda on every
 * OkHttp this fork might resolve against.
 */
internal fun hostScopedCallFactory(
    serverHosts: () -> Set<String>,
    plain: OkHttpClient,
    certificate: OkHttpClient,
): Call.Factory = object : Call.Factory {
    override fun newCall(request: Request): Call =
        resolveHttpClientFor(request.url.host, serverHosts(), plain, certificate).newCall(request)
}

/**
 * The host set [resolveHttpClientFor] must treat as ours, for the boomio call sites on this fork.
 *
 * ⚠️ **Measured gap, not a defensive extra.** `localServerHosts()` answers a *pin* question: which
 * hosts may be repointed at a discovered LAN address. It is built from `BoomioConfig.*` — every one
 * of which falls back to the `boomio.duckdns.org` floor until a background discovery rewrites
 * `serviceOrigin` to whatever the published record says. But `/find` is not dialled at any of those:
 * it is dialled at `BuildConfig.BOOMIO_BASE_URL`, the URL baked in at build time, and on the TV build
 * tree that host is neither the floor nor guaranteed to be the discovery answer yet. So
 * `hostIsOurs(dialledHost, localServerHosts())` is **false** for the very host this class calls, the
 * certificate is never presented, and `/find` degrades silently to the certless, permissive path —
 * the exact failure this file exists to close, and one that would flip with the timing of a discovery
 * race. Unioning the compiled-in host in makes the answer depend on configuration rather than on
 * which subsystem won a race.
 *
 * ⚠️ **TV-only, deliberately.** Mobile's `BOOMIO_BASE_URL` is blank (its `BoomioConfig` is `commonMain`
 * with no baked value), so this divergence bites the TV alone; mobile's copy of this file is
 * unchanged. `localServerHosts()` is still the base, so the discovery names, the media-plane names and
 * the addon hosts remain ours here too.
 *
 * ⚠️ **Extended to every compiled-in boomio SERVICE host, not only bsf's.** The same divergence that
 * bites `/find` bites each of its siblings: [MusicClient] and [TrickplayClient] dial
 * `BOOMIO_COMPANION_URL` and [IptvClient] dials `BOOMIO_IPTV_URL` — each read straight off
 * `BuildConfig`, where this set is built from `BoomioConfig`, which discovery rewrites. Whenever
 * those disagree — and before discovery has read a record they can — a host this build dials on
 * every session is absent from the live set and the certificate is silently withheld, which is the
 * failure this file exists to close.
 *
 * ⚠️ **`INTRODB_API_URL` is deliberately NOT here, even though `/skip/segments` is one of the boomio
 * routes.** It is a generic, operator-set endpoint whose natural value is a THIRD-PARTY service
 * (`api.introdb.app`) that bsc merely proxies for; unioning it would make that public host read as
 * ours and offer it this device's certificate — the exact leak this whole file exists to prevent. The
 * skip client is instead scoped by this set as it stands, which already gives the right answer at
 * both ends: pointed at bsc its host is here (via `BOOMIO_COMPANION_URL`, the same origin), and
 * pointed at the real IntroDB it is not. An unknown third-party endpoint is the one case where
 * withholding the credential must win over presenting it.
 *
 * The union is over *boomio service hosts we already dial*, so it can only ever add a host this build
 * was compiled to talk to — never a third-party addon.
 */
internal fun boomioOwnHosts(): Set<String> = localServerHosts() + listOfNotNull(
    hostOf(BuildConfig.BOOMIO_BASE_URL.trim()),
    hostOf(BuildConfig.BOOMIO_COMPANION_URL.trim()),
    hostOf(BuildConfig.BOOMIO_IPTV_URL.trim()),
    // bsm's admin API is a boomio service on the same origin, and [provideBsmRetrofit] is scoped by
    // this set for the same reason as the rest. It is listed here for the same divergence: the baked
    // value and `BoomioConfig.bsmBaseUrl` can disagree.
    hostOf(BuildConfig.BSM_BASE_URL.trim()),
)

/**
 * Resolves playback streams through the boomio media plane (`bsf`).
 *
 * boomio exposes:
 *   GET /find/:imdbId                    — movie lookup
 *   GET /find/:imdbId/:season/:episode   — episode lookup
 *
 * and returns a ranked list of Stremio-shaped streams whose `url` is a signed
 * `bsc` byte-range proxy URL. Each entry is mapped onto the app's existing
 * [Stream] model via the same [toDomain] mapper the addon layer uses, so the
 * stream picker, autoplay selector and built-in player consume boomio streams
 * exactly like any addon stream.
 *
 * The seam is inert unless [BuildConfig.BOOMIO_BASE_URL] is set in
 * local.properties; the existing addon/debrid/Trakt/TMDB resolvers remain the
 * primary sources and are left untouched as fallback.
 *
 * ── The certificate on this route, and why it is not optional ────────────────
 * `bsf` reads the identity the edge stamps from the TLS client certificate, and on `/find` it uses
 * that identity to substitute the device's **server-stored** capability policy for the caps the
 * client declares. A request that arrives without a certificate is not refused — it is treated as
 * an unidentified device and served permissively, which is the silent degradation this file exists
 * to close. The phone already presents the certificate on this route; the TV did not, because it
 * took the app's ordinary (certless) client.
 */
@Singleton
class BoomioStreamResolver @Inject constructor(
    private val okHttpClient: OkHttpClient,
    // ⚠️ Injected by name because it must be reached *only* through [resolveHttpClientFor] — see the
    // device-credential leak that predicate exists to prevent. Nothing else in this class may call
    // it directly.
    @Named("boomioClientCertificate")
    private val certificateOkHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val networkMeter: NetworkMeter
) {
    private val responseAdapter = moshi.adapter(StreamResponseDto::class.java)

    /** True when the boomio seam is configured (BOOMIO_BASE_URL is set). */
    fun isEnabled(): Boolean = BuildConfig.BOOMIO_BASE_URL.isNotBlank()

    /**
     * Calls boomio `/find` for [videoId] (the Stremio-style id, e.g. `tt1234567`
     * for a movie) with optional [season]/[episode], and returns the resolved
     * streams in boomio's ranked order.
     *
     * Returns an empty list when the seam is disabled, the id is blank, the
     * request fails, or boomio returns no streams.
     */
    suspend fun resolve(videoId: String?, season: Int?, episode: Int?): List<Stream> {
        if (!isEnabled()) return emptyList()
        val id = videoId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            resolveUnsafe(id, season, episode)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "boomio /find failed for $id: ${error.message}")
            emptyList()
        }
    }

    private suspend fun resolveUnsafe(
        videoId: String,
        season: Int?,
        episode: Int?
    ): List<Stream> = withContext(Dispatchers.IO) {
        val url = buildFindUrl(videoId, season, episode) ?: return@withContext emptyList()
        val request = Request.Builder().url(url).get().build()
        // The host is read off the built URL rather than off BuildConfig, so a redirect-free route
        // is scoped by what is actually dialled. See [resolveHttpClientFor] for why the choice is
        // per request and why it is host *identity* rather than pin presence.
        resolveHttpClientFor(url.host, boomioOwnHosts(), okHttpClient, certificateOkHttpClient)
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "boomio /find HTTP ${response.code} for $videoId")
                    return@withContext emptyList()
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext emptyList()
                val dto = responseAdapter.fromJson(body)
                dto?.streams.orEmpty().mapNotNull { streamDto ->
                    streamDto.toDomain(addonName = ADDON_NAME, addonLogo = null)
                }
            }
    }

    /**
     * Builds the `/find` URL from [BuildConfig.BOOMIO_BASE_URL]. Episodes use the
     * explicit `/find/:imdbId/:season/:episode` route (which requires an IMDB id);
     * everything else uses `/find/:stremioId`, which boomio normalizes (it accepts
     * `tt…` and `tmdb:…` ids and infers the type from the presence of a `:`).
     *
     * Returns the parsed [HttpUrl] rather than its string, because the caller has to read the host
     * off it to choose the client, and parsing it twice would be two chances for the two answers to
     * disagree.
     */
    private fun buildFindUrl(videoId: String, season: Int?, episode: Int?): HttpUrl? {
        val base = BuildConfig.BOOMIO_BASE_URL.trim().trimEnd('/')
        val baseUrl = base.toHttpUrlOrNull() ?: run {
            Log.w(TAG, "boomio: invalid BOOMIO_BASE_URL")
            return null
        }
        val builder = baseUrl.newBuilder().addPathSegment("find")
        if (season != null && episode != null) {
            val imdbId = videoId.substringBefore(":")
            if (!imdbId.startsWith("tt")) return null
            builder.addPathSegment(imdbId)
                .addPathSegment(season.toString())
                .addPathSegment(episode.toString())
        } else {
            builder.addPathSegment(videoId)
        }
        // Install-level capability hint: when this build was compiled with a max
        // resolution (BOOMIO_MAX_RESOLUTION, e.g. "1080p"), ask bsf to cap the
        // streams it feeds back so higher resolutions never reach the picker.
        // (snake_case: bsf's GET parser reads maxres|max_resolution, not camelCase maxResolution.)
        BuildConfig.BOOMIO_MAX_RESOLUTION.trim().takeIf { it.isNotBlank() }?.let {
            builder.addQueryParameter("max_resolution", it)
        }
        // Measured link cap (Mbps): bsf's gates hard-drop streams above mobileCapMbps, so the box is
        // only offered qualities its measured link can actually hold. Absent until the meter has run.
        networkMeter.lastMeasuredMbps()?.let { mbps ->
            builder.addQueryParameter("mobileCapMbps", String.format(Locale.US, "%.1f", mbps))
        }
        return builder.build()
    }

    companion object {
        /** Group name under which boomio streams appear in the picker/autoplay. */
        const val ADDON_NAME = "Boomio"
    }
}
