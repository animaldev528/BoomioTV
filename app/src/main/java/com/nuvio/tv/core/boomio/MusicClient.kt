package com.nuvio.tv.core.boomio

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.dto.MusicIdentifyDto
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Why we could not ask the question at all. Distinct from the server answering
 * "I found nothing", which is a result, not a fault — the two produce different
 * copy and must not be collapsed.
 */
enum class MusicUnavailableReason {
    /** This build has no bsc base URL compiled in. */
    NOT_CONFIGURED,

    /**
     * Nothing is playing on this TV, so there is no audio to identify.
     *
     * Its own reason rather than folded into [NOT_PAIRED]: a phone whose TV is
     * simply sitting on the home screen *is* paired and *is* linked, and telling
     * that viewer to re-pair would send them to fix something that is not broken.
     */
    NOT_PLAYING,

    /** Paired on no TV yet, so there is no session to identify with. */
    NOT_PAIRED,

    /**
     * The session carries no user, so there is no library to write into.
     *
     * Refused rather than guessed at: picking a library for the viewer would put
     * one person's song in another person's list. Re-pairing the TV from the
     * dashboard is the fix, so the copy says so.
     */
    NOT_LINKED,

    /** The call itself failed: offline, timeout, or a 5xx. */
    NETWORK
}

/** How asking "what is this?" ended. */
sealed interface MusicIdentifyResult {
    /** The server answered. [MusicIdentifyDto.status] says what it found. */
    data class Answered(val response: MusicIdentifyDto) : MusicIdentifyResult

    /** The question never reached the server. */
    data class Unavailable(
        val reason: MusicUnavailableReason,
        val detail: String? = null
    ) : MusicIdentifyResult
}

/**
 * A track the viewer asked to keep.
 *
 * Deliberately identical to the fields the identify answer returned, because
 * that is what the library stores — re-identifying on save would be a second
 * provider call for an answer we are already holding.
 *
 * No user field: the owner comes from the session on the server. The client
 * does not get a say in whose library this lands in.
 */
data class MusicSaveRequest(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val isrc: String? = null,
    val artworkUrl: String? = null,
    val provider: String? = null,
    val providerTrackId: String? = null,
    /** Where it was heard, so the library can read back "from tt0119147 · 42:13". */
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long? = null
)

/** How a save ended. */
sealed interface MusicSaveResult {
    /**
     * The track is in the library. [duplicate] is true when it was already
     * there, which the server tells us rather than leaving us to guess.
     */
    data class Stored(val duplicate: Boolean) : MusicSaveResult

    data class Failed(
        val reason: MusicUnavailableReason,
        val detail: String? = null
    ) : MusicSaveResult
}

/** The context a press carries. Everything here is what the player already knows. */
data class MusicIdentifyRequest(
    val deviceId: String,
    val positionMs: Long,
    val imdbId: String?,
    val season: Int?,
    val episode: Int?,
    val streamUrl: String?,
    /**
     * Which audio track the viewer is hearing, as a **0-based ordinal into the
     * audio-only list** — the player's own numbering, not ffprobe's.
     *
     * This is sent on the request body rather than left to the telemetry-written
     * position record for two reasons. The body is what the player has selected
     * *at the moment of the press*, where the record is a 1s-cadence write behind
     * a 60s TTL and can still describe the track from before the viewer switched
     * to commentary — the exact case this field exists to get right. And the
     * route accepts it directly, so it needs no server change to take effect.
     */
    val audioTrack: Int?
)

/**
 * Client for the bsc music endpoints.
 *
 * Session-gated with the same `bs_ses_…` token the IPTV edge uses — one pairing,
 * both services. Reads soft: a dead bsc degrades the button to a message rather
 * than an exception, matching [IptvClient].
 *
 * The call is genuinely slow (measured 6.5–9.5 s on an 88 GB remux, because the
 * server fetches and decodes a window of the real stream), so callers must show
 * a pending state and must not block playback on it.
 *
 * ── The certificate on these routes ──────────────────────────────────────────
 * `BOOMIO_COMPANION_URL` is the bsc companion host — a host of ours — so both
 * requests here must travel over the certificate-bearing client. Without it the
 * edge sees an *unidentified* device and serves it permissively rather than
 * refusing, which is the silent degradation this change closes. The choice is made
 * per request off the URL's own host (see [resolveHttpClientFor]), so the
 * certificate is never offered to a host that is not ours.
 */
@Singleton
class MusicClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    // Reached only through [resolveHttpClientFor]; see its KDoc for the leak that scoping prevents.
    @Named("boomioClientCertificate")
    private val certificateOkHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val authStore: IptvAuthStore
) {
    private val identifyAdapter = moshi.adapter(MusicIdentifyDto::class.java)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * The client [request] must travel over — the certificate-bearing one when its host is ours,
     * the plain validating one otherwise. Scoped per request; see [resolveHttpClientFor].
     */
    private fun clientFor(request: Request): OkHttpClient =
        resolveHttpClientFor(request.url.host, boomioOwnHosts(), okHttpClient, certificateOkHttpClient)

    /** Configured only when the companion host was compiled in. */
    fun isConfigured(): Boolean = baseUrl() != null

    /**
     * The identify answer, re-emitted exactly as bsc sent it.
     *
     * This exists for the companion relay. A phone that presses "what is this?"
     * cannot ask bsc itself — the answer depends on the audio-track ordinal the
     * viewer is hearing, which only this TV holds — so the TV forwards what the
     * server said. Forwarding *this*, rather than a second shape hand-built on
     * the TV side, is what keeps the phone parsing the same contract the TV
     * does: a field added to `MusicIdentifyDto` reaches the phone the moment the
     * server sends it, with nothing in between to fall behind.
     */
    fun wireJson(dto: MusicIdentifyDto): String = identifyAdapter.toJson(dto)

    /**
     * The bsc REST base, derived from the companion WebSocket host.
     *
     * Deliberately **not** `BOOMIO_BASE_URL`: that field points at bsf, the
     * stream-resolver addon, and the music routes are not mounted there —
     * `/api/music/health` answers 404 on bsf and 401 on bsc, which is the
     * session gate doing its job. bsc is the companion hub, so its REST edge is
     * the same host the companion socket already connects to; only the scheme
     * differs, and `wss` maps to `https`.
     *
     * Tying the two together also means there is one host to configure per
     * device rather than two that could silently disagree.
     */
    private fun baseUrl(): HttpUrl? {
        val raw = BuildConfig.BOOMIO_COMPANION_URL.trim().trimEnd('/')
        if (raw.isBlank()) return null
        val http = when {
            raw.startsWith("wss://") -> "https://" + raw.removePrefix("wss://")
            raw.startsWith("ws://") -> "http://" + raw.removePrefix("ws://")
            else -> raw
        }
        return http.toHttpUrlOrNull()
    }

    suspend fun identify(request: MusicIdentifyRequest): MusicIdentifyResult =
        withContext(Dispatchers.IO) {
            val base = baseUrl()
                ?: return@withContext MusicIdentifyResult.Unavailable(
                    MusicUnavailableReason.NOT_CONFIGURED
                )
            val token = authStore.currentToken()
                ?: return@withContext MusicIdentifyResult.Unavailable(
                    MusicUnavailableReason.NOT_PAIRED
                )

            val body = JSONObject().apply {
                put("deviceId", request.deviceId)
                put("positionMs", request.positionMs)
                request.imdbId?.let { put("imdbId", it) }
                request.season?.let { put("season", it) }
                request.episode?.let { put("episode", it) }
                request.streamUrl?.takeIf { it.isNotBlank() }?.let { put("streamUrl", it) }
                request.audioTrack?.let { put("audioTrack", it) }
            }.toString().toRequestBody(jsonType)

            val httpRequest = Request.Builder()
                .url(base.newBuilder().addPathSegments("api/music/identify").build())
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            try {
                clientFor(httpRequest).newCall(httpRequest).execute().use { response ->
                    val text = response.body?.string().orEmpty()

                    // 429 is not a failure — it is the daily cap, which is a state
                    // the UI has its own copy for. Parse it like any other answer.
                    if (!response.isSuccessful && response.code != 429) {
                        return@use MusicIdentifyResult.Unavailable(
                            MusicUnavailableReason.NETWORK,
                            "HTTP ${response.code}"
                        )
                    }

                    val dto = identifyAdapter.fromJson(text)
                        ?: return@use MusicIdentifyResult.Unavailable(
                            MusicUnavailableReason.NETWORK,
                            "unreadable response"
                        )
                    MusicIdentifyResult.Answered(dto)
                }
            } catch (e: Exception) {
                Log.w(TAG, "identify failed: ${e.message}")
                MusicIdentifyResult.Unavailable(MusicUnavailableReason.NETWORK, e.message)
            }
        }

    /**
     * Keep a recognised track in the viewer's library.
     *
     * Fast where [identify] is slow — no provider is called and no audio is
     * fetched, because the answer is already in hand. Callers should still treat
     * it as a pending action with its own state rather than blocking the card.
     *
     * A 409 is the server saying the session has no user behind it. That is
     * deliberately its own reason and not folded into [MusicUnavailableReason.NETWORK]:
     * it is a pairing problem with a fix, and telling the viewer "something went
     * wrong" would hide the one thing they could actually do about it.
     */
    suspend fun saveToLibrary(request: MusicSaveRequest): MusicSaveResult =
        withContext(Dispatchers.IO) {
            val base = baseUrl()
                ?: return@withContext MusicSaveResult.Failed(MusicUnavailableReason.NOT_CONFIGURED)
            val token = authStore.currentToken()
                ?: return@withContext MusicSaveResult.Failed(MusicUnavailableReason.NOT_PAIRED)

            val body = JSONObject().apply {
                put("title", request.title)
                request.artist?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
                request.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                request.isrc?.takeIf { it.isNotBlank() }?.let { put("isrc", it) }
                request.artworkUrl?.takeIf { it.isNotBlank() }?.let { put("artworkUrl", it) }
                request.provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
                request.providerTrackId?.takeIf { it.isNotBlank() }?.let { put("providerTrackId", it) }
                request.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdbId", it) }
                request.season?.let { put("season", it) }
                request.episode?.let { put("episode", it) }
                request.positionMs?.let { put("positionMs", it) }
            }.toString().toRequestBody(jsonType)

            val httpRequest = Request.Builder()
                .url(base.newBuilder().addPathSegments("api/music/library").build())
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            try {
                clientFor(httpRequest).newCall(httpRequest).execute().use { response ->
                    val text = response.body?.string().orEmpty()

                    when {
                        response.isSuccessful -> MusicSaveResult.Stored(
                            runCatching { JSONObject(text).optBoolean("duplicate", false) }
                                .getOrDefault(false)
                        )

                        response.code == 409 -> MusicSaveResult.Failed(
                            MusicUnavailableReason.NOT_LINKED,
                            runCatching { JSONObject(text).optString("error").ifBlank { null } }.getOrNull()
                        )

                        else -> MusicSaveResult.Failed(
                            MusicUnavailableReason.NETWORK,
                            "HTTP ${response.code}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "saveToLibrary failed: ${e.message}")
                MusicSaveResult.Failed(MusicUnavailableReason.NETWORK, e.message)
            }
        }

    private companion object {
        const val TAG = "MusicClient"
    }
}
