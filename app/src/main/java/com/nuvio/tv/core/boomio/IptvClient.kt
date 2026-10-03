package com.nuvio.tv.core.boomio

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.dto.IptvChannelsDto
import com.nuvio.tv.data.remote.dto.IptvDeviceCodeDto
import com.nuvio.tv.data.remote.dto.IptvGuideDto
import com.nuvio.tv.data.remote.dto.IptvPollDto
import com.nuvio.tv.data.remote.dto.IptvTuneDto
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvNowNext
import com.nuvio.tv.domain.model.IptvProgramme
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
import javax.inject.Singleton

/**
 * The edge refused a tune because a live watch party owns the tuner and is on a
 * different channel. Carries the party's channel so the TV can say WHICH one,
 * rather than reporting an opaque HTTP 409.
 *
 * A refusal is a normal outcome, not a fault: the tuner is genuinely held by
 * someone else, and the right response is a message the viewer understands.
 */
class IptvTunerLockedException(
    val channelName: String?,
    val liveStreamId: String?
) : IllegalStateException("a live watch party is using the tuner")

/** Where a device-code poll stands. */
sealed interface IptvPollResult {
    data object Pending : IptvPollResult
    data object Expired : IptvPollResult
    data class Paired(val token: String, val displayName: String?) : IptvPollResult
    data class Failed(val message: String) : IptvPollResult
}

/** The pairing code a TV shows while it waits to be approved. */
data class IptvPairingRequest(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Int
)

data class IptvChannelsResult(
    val channels: List<IptvChannel> = emptyList(),
    /** True when the household follows no groups — a state, not a failure. */
    val unscoped: Boolean = false,
    val limited: Boolean = false,
    val stale: Boolean = false,
    /** Set when the edge rejected our session; the caller should re-pair. */
    val unauthorized: Boolean = false,
    val error: String? = null
)

/**
 * Client for the boomio live-IPTV edge (bss-iptv).
 *
 * The edge is session-gated: every call carries the `bs_ses_…` token that
 * pairing produced. A media player cannot set an Authorization header, so for
 * playback we do NOT hand the player a bare URL — [tune] returns a playlist
 * path that already carries `?session_token=`, which the edge then propagates
 * onto every segment URI it rewrites.
 *
 * Every call fails soft: the UI gets an [IptvChannelsResult] with an error
 * rather than an exception, so a dead edge degrades the section instead of
 * crashing the app.
 */
@Singleton
class IptvClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val authStore: IptvAuthStore
) {
    private val deviceCodeAdapter = moshi.adapter(IptvDeviceCodeDto::class.java)
    private val pollAdapter = moshi.adapter(IptvPollDto::class.java)
    private val channelsAdapter = moshi.adapter(IptvChannelsDto::class.java)
    private val guideAdapter = moshi.adapter(IptvGuideDto::class.java)
    private val tuneAdapter = moshi.adapter(IptvTuneDto::class.java)

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** The IPTV seam is configured only when a base URL was compiled in. */
    fun isConfigured(): Boolean = BuildConfig.BOOMIO_IPTV_URL.isNotBlank()

    private fun baseUrl(): HttpUrl? =
        BuildConfig.BOOMIO_IPTV_URL.trim().trimEnd('/').toHttpUrlOrNull()

    // ── pairing ────────────────────────────────────────────────────────────

    /**
     * Starts device-code pairing. Requesting a code is unauthenticated (the
     * code is useless until a human approves it), so this works before we hold
     * any session.
     */
    suspend fun requestPairing(): Result<IptvPairingRequest> = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext Result.failure(
            IllegalStateException("IPTV is not configured on this build")
        )
        runCatching {
            val deviceId = authStore.deviceId()
            val body = """{"device_id":${deviceId.json()},"platform":"androidtv","name":"Boomio TV"}"""
                .toRequestBody(jsonType)
            val request = Request.Builder()
                .url(base.newBuilder().addPathSegments("api/v1/auth/device/request").build())
                .post(body)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val dto = deviceCodeAdapter.fromJson(text) ?: error("empty response")
                IptvPairingRequest(
                    deviceCode = dto.deviceCode.orEmpty(),
                    userCode = dto.userCode.orEmpty(),
                    verificationUri = dto.verificationUri.orEmpty(),
                    intervalSeconds = (dto.interval ?: 5).coerceAtLeast(1)
                )
            }
        }
    }

    /** One poll of the pairing state. Callers drive the interval themselves. */
    suspend fun pollPairing(deviceCode: String): IptvPollResult = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext IptvPollResult.Failed("IPTV is not configured")
        runCatching {
            val request = Request.Builder()
                .url(
                    base.newBuilder()
                        .addPathSegments("api/v1/auth/device/poll")
                        .addQueryParameter("dc", deviceCode)
                        .build()
                )
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use IptvPollResult.Failed("HTTP ${response.code}")
                val dto = pollAdapter.fromJson(text) ?: return@use IptvPollResult.Pending
                when (dto.status) {
                    "ok" -> {
                        val token = dto.sessionToken ?: dto.token
                        if (token.isNullOrBlank()) IptvPollResult.Failed("no token returned")
                        else IptvPollResult.Paired(token, dto.displayName ?: dto.username)
                    }
                    "expired" -> IptvPollResult.Expired
                    else -> IptvPollResult.Pending
                }
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            IptvPollResult.Failed(e.message ?: "poll failed")
        }
    }

    // ── reads ──────────────────────────────────────────────────────────────

    /**
     * The household's followed channels. [limit] defaults to the edge's own
     * maximum (1000) on purpose: the live selection is ~750 channels, so a
     * smaller cap would silently hide most of the list with no indication.
     */
    suspend fun channels(limit: Int = MAX_CHANNELS): IptvChannelsResult = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext IptvChannelsResult(
            error = "IPTV is not configured on this build"
        )
        val token = authStore.currentToken()
        if (token.isNullOrBlank()) return@withContext IptvChannelsResult(unauthorized = true)

        runCatching {
            val request = Request.Builder()
                .url(
                    base.newBuilder()
                        .addPathSegments("iptv/channels")
                        .addQueryParameter("limit", limit.toString())
                        .build()
                )
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    authStore.clearSession()
                    return@use IptvChannelsResult(unauthorized = true)
                }
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use IptvChannelsResult(error = "HTTP ${response.code}")
                }
                val dto = channelsAdapter.fromJson(text)
                    ?: return@use IptvChannelsResult(error = "empty response")
                IptvChannelsResult(
                    channels = dto.channels.orEmpty().mapNotNull { c ->
                        val id = c.streamId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        IptvChannel(
                            streamId = id,
                            name = c.name?.takeIf { it.isNotBlank() } ?: id,
                            rawName = c.rawName,
                            categoryId = c.categoryId,
                            icon = c.icon,
                            epgChannelId = c.epgChannelId,
                            hasEpg = c.hasEpg == true
                        )
                    },
                    unscoped = dto.unscoped == true,
                    limited = dto.limited == true,
                    stale = dto.selectionStale == true
                )
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "iptv /channels failed: ${e.message}")
            IptvChannelsResult(error = e.message ?: "request failed")
        }
    }

    /**
     * Now/next for a list of channels, batched.
     *
     * The guide is short and covers only a fraction of channels, so a channel
     * with no entry is routine: it is simply absent from the returned map and
     * the UI shows "No guide data" rather than implying the channel is broken.
     *
     * A 503 means the edge's guide store has not completed a clean sync. The
     * edge refuses rather than serve a half-stale guide — so that is also
     * "no guide", never an error the viewer has to act on.
     */
    suspend fun nowNextFor(streamIds: List<String>, hours: Int = 6): Map<String, IptvNowNext> =
        withContext(Dispatchers.IO) {
            val ids = streamIds.filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) return@withContext emptyMap()
            // The edge caps /guide at 300 channels per call and a household here
            // follows ~750, so a single call would leave most of the list with no
            // now/next — which reads as "broken", not "not enough guide". Chunk
            // and merge rather than silently truncate.
            ids.chunked(MAX_GUIDE_CHANNELS)
                .fold(emptyMap<String, IptvNowNext>()) { acc, chunk ->
                    acc + guideChunk(chunk, hours)
                }
        }

    /** One /guide call for at most [MAX_GUIDE_CHANNELS] ids. */
    private suspend fun guideChunk(ids: List<String>, hours: Int): Map<String, IptvNowNext> {
        val base = baseUrl() ?: return emptyMap()
        val token = authStore.currentToken() ?: return emptyMap()

        return runCatching {
            val request = Request.Builder()
                .url(
                    base.newBuilder()
                        .addPathSegments("iptv/guide")
                        .addQueryParameter("channels", ids.joinToString(","))
                        .addQueryParameter("hours", hours.toString())
                        .build()
                )
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    authStore.clearSession()
                    return@use emptyMap<String, IptvNowNext>()
                }
                if (!response.isSuccessful) return@use emptyMap<String, IptvNowNext>()
                val dto = guideAdapter.fromJson(response.body?.string().orEmpty())
                    ?: return@use emptyMap<String, IptvNowNext>()
                val now = System.currentTimeMillis()
                dto.channels.orEmpty().mapNotNull { col ->
                    val id = col.streamId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val programmes = col.programmes.orEmpty()
                        .map { it.toDomain() }
                        .sortedBy { it.startMs }
                    if (programmes.isEmpty()) return@mapNotNull null
                    id to IptvNowNext(
                        now = programmes.firstOrNull { now >= it.startMs && now < it.endMs },
                        next = programmes.firstOrNull { it.startMs > now }
                    )
                }.toMap()
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "iptv /guide failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Reserves the upstream tuner slot for [streamId] and returns an ABSOLUTE
     * playlist URL for the player.
     *
     * The path the edge returns is relative and already carries
     * `?session_token=…`; it is resolved here so the player never needs to know
     * about the edge's auth contract.
     */
    suspend fun tune(streamId: String): Result<String> = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext Result.failure(
            IllegalStateException("IPTV is not configured")
        )
        val token = authStore.currentToken() ?: return@withContext Result.failure(
            IllegalStateException("not paired")
        )
        runCatching {
            val request = Request.Builder()
                .url(base.newBuilder().addPathSegments("iptv/tune/$streamId").build())
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    authStore.clearSession()
                    error("session rejected")
                }
                // A live watch party owns the tuner. Distinguished from a generic
                // failure so the caller can name the channel instead of surfacing
                // a bare status code.
                if (response.code == 409) {
                    val body = runCatching { JSONObject(text) }.getOrNull()
                    if (body?.optString("error") == "tuner_locked") {
                        throw IptvTunerLockedException(
                            channelName = body.optString("channelName").takeIf { it.isNotBlank() },
                            liveStreamId = body.optString("liveStreamId").takeIf { it.isNotBlank() }
                        )
                    }
                }
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val dto = tuneAdapter.fromJson(text) ?: error("empty response")
                val path = dto.playlist?.takeIf { it.isNotBlank() } ?: error("no playlist")
                val origin = base.toString().trimEnd('/')
                if (path.startsWith("http")) path else origin + (if (path.startsWith("/")) path else "/$path")
            }
        }
    }

    private companion object {
        const val TAG = "IptvClient"

        /** The edge caps /guide at 300 channels per call. */
        const val MAX_GUIDE_CHANNELS = 300

        /** The edge caps /channels at 1000. */
        const val MAX_CHANNELS = 1000
    }
}

/** Minimal JSON string escaping — these values are ids we generate ourselves. */
private fun String.json(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun com.nuvio.tv.data.remote.dto.IptvProgrammeDto.toDomain(): IptvProgramme =
    IptvProgramme(
        title = title?.takeIf { it.isNotBlank() } ?: "Untitled",
        description = description,
        startMs = startMs ?: 0L,
        endMs = stopMs ?: startMs ?: 0L
    )
