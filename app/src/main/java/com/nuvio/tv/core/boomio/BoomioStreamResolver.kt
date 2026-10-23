package com.nuvio.tv.core.boomio

import android.util.Log
import com.nuvio.app.core.mtls.hostIsOurs
import com.nuvio.app.core.overlay.localServerHosts
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.device.DeviceDecodeCeiling
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
 * ⚠️ **This is a straight alias of `localServerHosts()`, and that is the whole point.** It used to be
 * that set *plus* a union of the four hosts read straight off `BuildConfig`
 * (`BOOMIO_BASE_URL`, `BOOMIO_COMPANION_URL`, `BOOMIO_IPTV_URL`, `BSM_BASE_URL`). The union existed
 * because those call sites dialled the compile-time constant while this set is derived from
 * `BoomioConfig`, which discovery rewrites — so the two could disagree, and on the TV build tree they
 * did: the baked value was the retired `boomio.tracemonkey.org`. `hostIsOurs(dialledHost,
 * localServerHosts())` was then **false** for the very host `/find` called, the certificate was never
 * presented, and the request degraded silently to the certless, permissive path — the exact failure
 * this file exists to close, and one that flipped with the timing of a discovery race.
 *
 * ⚠️ **The union is gone because its cause is gone.** Every boomio call site now reads
 * `BoomioConfig` — the one seam that discovery, mDNS and manual entry all write — so the host a call
 * dials and the host this set derives are the same value by construction and cannot disagree.
 * `localServerHosts()` already covers the discovery names, the media-plane hosts that appear in no
 * configuration (`bss-dav`, `bss-tor`, …) and the addon hosts, so re-adding the service hosts here
 * would restore a second source of truth for a question that now has exactly one answer.
 *
 * ⚠️ **`INTRODB_API_URL` is deliberately NOT added here, even though `/skip/segments` is one of the
 * boomio routes.** It is a generic, operator-set override whose natural value could as well be a
 * THIRD-PARTY service (`api.introdb.app`) that bsc merely proxies for; unioning it would make that
 * public host read as ours and offer it this device's certificate — the exact leak this whole file
 * exists to prevent. The skip base normally needs no entry at all: it is derived from the service
 * origin (`BoomioConfig.skipBaseUrl`), so the host it dials is the origin's host, which this set
 * already carries. The override is the one case where the two can disagree, and there withholding
 * the credential must win over presenting it to an unknown endpoint.
 */
internal fun boomioOwnHosts(): Set<String> = localServerHosts()

/**
 * What one `/find` produced: the streams, and — when there are none — why.
 *
 * `message` is set only when boomio is the reason for the emptiness (the
 * household bandwidth budget is in use, or nothing fits inside it), never for a
 * network or HTTP failure: an unreachable server must stay silent so the addon
 * sources are the only thing the user sees.
 */
data class BoomioResolveResult(
    val streams: List<Stream>,
    val failureReason: String? = null,
    val message: String? = null
) {
    val isEmpty: Boolean get() = streams.isEmpty()

    companion object {
        val EMPTY = BoomioResolveResult(emptyList())
    }
}

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
 * The base it dials is [BoomioConfig.boomioBaseUrl] — floored to the service origin
 * and rewritten the moment discovery reads a published record or the user names a
 * server by hand — so the seam is live on every build rather than only one that baked
 * a URL into `local.properties`. The existing addon/debrid/Trakt/TMDB resolvers remain
 * the primary sources and are left untouched as fallback.
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

    /**
     * True when the boomio seam has a base to dial.
     *
     * ⚠️ Read off [BoomioConfig], not `BuildConfig.BOOMIO_BASE_URL`. The seam is no longer "did this
     * build bake a URL": the origin has a compiled floor, so it is live on every build and this is a
     * sanity check rather than a feature switch. Gating on the baked value instead would have turned
     * the whole media plane off the moment the stale `local.properties` value was retired.
     */
    fun isEnabled(): Boolean = BoomioConfig.boomioBaseUrl.isNotBlank()

    /**
     * Calls boomio `/find` for [videoId] (the Stremio-style id, e.g. `tt1234567`
     * for a movie) with optional [season]/[episode], and returns the resolved
     * streams in boomio's ranked order.
     *
     * Returns an empty result when the seam is disabled, the id is blank, or the
     * request fails. A successful request with no streams carries boomio's own
     * explanation in [BoomioResolveResult.message] when there is one — an empty
     * list on its own cannot tell "this title has no sources" apart from "the
     * household's bandwidth budget is in use", and the picker should say which.
     */
    suspend fun resolve(videoId: String?, season: Int?, episode: Int?): BoomioResolveResult {
        if (!isEnabled()) return BoomioResolveResult.EMPTY
        val id = videoId?.trim()?.takeIf { it.isNotBlank() } ?: return BoomioResolveResult.EMPTY
        return runCatching {
            resolveUnsafe(id, season, episode)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "boomio /find failed for $id: ${error.message}")
            BoomioResolveResult.EMPTY
        }
    }

    private suspend fun resolveUnsafe(
        videoId: String,
        season: Int?,
        episode: Int?
    ): BoomioResolveResult = withContext(Dispatchers.IO) {
        val url = buildFindUrl(videoId, season, episode) ?: return@withContext BoomioResolveResult.EMPTY
        val request = Request.Builder().url(url).get().build()
        // The host is read off the built URL rather than off BuildConfig, so a redirect-free route
        // is scoped by what is actually dialled. See [resolveHttpClientFor] for why the choice is
        // per request and why it is host *identity* rather than pin presence.
        resolveHttpClientFor(url.host, boomioOwnHosts(), okHttpClient, certificateOkHttpClient)
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "boomio /find HTTP ${response.code} for $videoId")
                    return@withContext BoomioResolveResult.EMPTY
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext BoomioResolveResult.EMPTY
                val dto = responseAdapter.fromJson(body)
                val streams = dto?.streams.orEmpty().mapNotNull { streamDto ->
                    streamDto.toDomain(addonName = ADDON_NAME, addonLogo = null)
                }
                // Only an empty result can be explained: a message beside real
                // streams would be a gate note about candidates that were
                // dropped, not something to show over a working picker.
                if (streams.isNotEmpty()) {
                    BoomioResolveResult(streams)
                } else {
                    BoomioResolveResult(
                        streams = emptyList(),
                        failureReason = dto?.failureReason,
                        message = dto?.message
                    )
                }
            }
    }

    /**
     * Builds the `/find` URL from [BoomioConfig.boomioBaseUrl]. Episodes use the
     * explicit `/find/:imdbId/:season/:episode` route (which requires an IMDB id);
     * everything else uses `/find/:stremioId`, which boomio normalizes (it accepts
     * `tt…` and `tmdb:…` ids and infers the type from the presence of a `:`).
     *
     * Returns the parsed [HttpUrl] rather than its string, because the caller has to read the host
     * off it to choose the client, and parsing it twice would be two chances for the two answers to
     * disagree.
     */
    private fun buildFindUrl(videoId: String, season: Int?, episode: Int?): HttpUrl? {
        val base = BoomioConfig.boomioBaseUrl.trim().trimEnd('/')
        val baseUrl = base.toHttpUrlOrNull() ?: run {
            Log.w(TAG, "boomio: invalid boomio base url")
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
        // What this box can actually decode, probed from MediaCodecList and read off the
        // process-wide cache — see [DeviceDecodeCeiling]. Sent so bsf's resolution gate drops
        // streams this box cannot decode *before* the picker ever offers them; the Fire TV Stick's
        // "stuck on starting stream" was a 3840x2076 HEVC entry reaching a decoder capped at
        // 1920x1088, where it died in configureCodec(-22).
        //
        // The build property stays as an operator override for a deliberate down-cap; the probe is
        // the default because a compile-time constant cannot know what a device decodes.
        // (snake_case: bsf's GET parser reads maxres|max_resolution, not camelCase maxResolution.)
        val maxResolution = BuildConfig.BOOMIO_MAX_RESOLUTION.trim()
            .ifBlank { DeviceDecodeCeiling.maxResolution() }
        if (maxResolution.isNotBlank()) {
            builder.addQueryParameter("max_resolution", maxResolution)
        }
        // The per-codec twin, and the reason it exists: the scalar above is binary at the 4K
        // boundary, so it cannot say "2160 is fine on HEVC but not on AVC".
        DeviceDecodeCeiling.maxResByCodec().takeIf { it.isNotBlank() }?.let {
            builder.addQueryParameter("codecCapsMaxRes", it)
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
