package com.nuvio.tv.core.boomio

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.dto.IptvChannelsDto
import com.nuvio.tv.data.remote.dto.IptvDeviceCodeDto
import com.nuvio.tv.data.remote.dto.IptvGuideDto
import com.nuvio.tv.data.remote.dto.IptvPollDto
import com.nuvio.tv.data.remote.dto.IptvTuneDto
import com.nuvio.tv.data.remote.dto.IptvResolveEpisodeDto
import com.nuvio.tv.data.remote.dto.IptvSearchDto
import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvChannelRef
import com.nuvio.tv.domain.model.IptvGuideHit
import com.nuvio.tv.domain.model.IptvGuideWindow
import com.nuvio.tv.domain.model.IptvProgramme
import com.nuvio.tv.domain.model.IptvProgrammeMatch
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
 * The outcome of asking which episode a programme is (the edge's Tier 4).
 *
 * [hasEpisode] false is the ordinary "just open the show" answer, and it is the
 * SAME outcome whether the model abstained, its pick failed verification, or the
 * call timed out — the owner's rule is that an unverified pick is a miss, not a
 * hedge. So there is deliberately no confidence grade to render and no `verified`
 * flag to branch on: the caller plays the programme either way.
 *
 * Null from [IptvClient.resolveEpisode] is different and means something else —
 * the call itself did not complete — but it lands on the same fallback, which is
 * why the caller can treat both identically.
 */
data class IptvEpisodeResolution(
    val imdbId: String? = null,
    val mediaType: String? = null,
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    val cached: Boolean = false
) {
    val hasEpisode: Boolean get() = season != null && episode != null

    /** Enough identity to open the show when no episode resolved. */
    val isNavigable: Boolean get() = !imdbId.isNullOrBlank() && !mediaType.isNullOrBlank()
}

/** Result of a guide-text search. Programme and channel hits stay separate. */
data class IptvGuideSearch(
    val programmes: List<IptvGuideHit> = emptyList(),
    val channels: List<IptvChannel> = emptyList(),
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
    private val resolveAdapter = moshi.adapter(IptvResolveEpisodeDto::class.java)
    private val searchAdapter = moshi.adapter(IptvSearchDto::class.java)

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
                    channels = dto.channels.orEmpty().mapNotNull { it.toDomain() },
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
     * The guide WINDOW for a list of channels — the programmes themselves, not a
     * collapsed now/next. The grid draws from this; now/next is derived locally.
     *
     * [fromMs] is epoch millis and [hours] is clamped by the edge to 1..336. The
     * returned window echoes what the edge could actually serve, which is not
     * always what was asked: the panel's schedule is short, so a caller sizing a
     * grid from its own request rather than [IptvGuideWindow.toMs] would draw
     * columns of nothing.
     *
     * Chunked because the edge caps a call at 300 channels and a household here
     * follows ~750; a single call would silently leave most of the grid blank,
     * which reads as "no guide data" rather than "we stopped asking".
     */
    suspend fun guideWindow(
        streamIds: List<String>,
        fromMs: Long = System.currentTimeMillis(),
        hours: Int = 6
    ): IptvGuideWindow = withContext(Dispatchers.IO) {
        val ids = streamIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return@withContext IptvGuideWindow(fromMs = fromMs, toMs = fromMs)
        val merged = ids.chunked(MAX_GUIDE_CHANNELS)
            .fold(IptvGuideWindow()) { acc, chunk -> acc.merge(windowChunk(chunk, fromMs, hours)) }
        // If no chunk answered, still report the requested span so the caller can
        // tell "nothing on" from "nothing asked for".
        if (merged.fromMs == 0L) merged.copy(fromMs = fromMs, toMs = maxOf(fromMs, merged.toMs))
        else merged
    }

    /** One /guide window call for at most [MAX_GUIDE_CHANNELS] ids. */
    private suspend fun windowChunk(ids: List<String>, fromMs: Long, hours: Int): IptvGuideWindow {
        val base = baseUrl() ?: return IptvGuideWindow()
        val token = authStore.currentToken() ?: return IptvGuideWindow()

        return runCatching {
            val request = Request.Builder()
                .url(
                    base.newBuilder()
                        .addPathSegments("iptv/guide")
                        .addQueryParameter("channels", ids.joinToString(","))
                        .addQueryParameter("from", fromMs.toString())
                        .addQueryParameter("hours", hours.toString())
                        .build()
                )
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    authStore.clearSession()
                    return@use IptvGuideWindow()
                }
                // 503 means the store has not completed a clean sync; the edge
                // refuses rather than serve a half-stale guide. That is "no guide
                // yet", not a failure the viewer can act on.
                if (!response.isSuccessful) return@use IptvGuideWindow()
                val dto = guideAdapter.fromJson(response.body?.string().orEmpty())
                    ?: return@use IptvGuideWindow()
                IptvGuideWindow(
                    fromMs = parseIsoMs(dto.from) ?: fromMs,
                    toMs = parseIsoMs(dto.to) ?: fromMs,
                    aheadHours = dto.horizon?.aheadHours,
                    byChannel = dto.channels.orEmpty().mapNotNull { col ->
                        val id = col.streamId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val programmes = col.programmes.orEmpty()
                            .map { it.toDomain() }
                            .sortedBy { it.startMs }
                        if (programmes.isEmpty()) null else id to programmes
                    }.toMap()
                )
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "iptv /guide window failed: ${e.message}")
            IptvGuideWindow()
        }
    }

    /**
     * Asks which EPISODE a programme is, on demand — the one call made when a
     * viewer actually presses play, never on focus. Resolving on focus would pay
     * for every cell scrolled past.
     *
     * The client names a PROGRAMME (`streamId` + `startMs`, both identifiers it
     * already holds from the guide) rather than sending text, so the spend stays
     * bounded by real programmes and this can never become a general-purpose
     * model proxy. `startMs` is epoch MILLISECONDS, the same unit the guide puts
     * on every row — passing seconds would ask about a programme in 1970.
     *
     * Returns null when the call did not complete. That is deliberately folded
     * into the same fallback as "no episode resolved": the caller opens the show.
     */
    suspend fun resolveEpisode(streamId: String, startMs: Long): IptvEpisodeResolution? =
        withContext(Dispatchers.IO) {
            val base = baseUrl() ?: return@withContext null
            val token = authStore.currentToken() ?: return@withContext null
            runCatching {
                val body = JSONObject()
                    .put("streamId", streamId)
                    .put("startMs", startMs)
                    .toString()
                    .toRequestBody(jsonType)
                val request = Request.Builder()
                    .url(base.newBuilder().addPathSegments("iptv/resolve-episode").build())
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        authStore.clearSession()
                        return@use null
                    }
                    if (!response.isSuccessful) return@use null
                    val dto = resolveAdapter.fromJson(response.body?.string().orEmpty())
                        ?: return@use null
                    IptvEpisodeResolution(
                        imdbId = dto.show?.imdbId?.takeIf { it.isNotBlank() },
                        mediaType = dto.show?.mediaType?.takeIf { it.isNotBlank() },
                        showTitle = dto.show?.title?.takeIf { it.isNotBlank() },
                        season = dto.episode?.season,
                        episode = dto.episode?.episode,
                        episodeName = dto.episode?.name?.takeIf { it.isNotBlank() },
                        cached = dto.cached == true
                    )
                }
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "iptv /resolve-episode failed: ${e.message}")
                null
            }
        }

    /**
     * FTS5 search across the guide — the fallback for long-pressing a programme
     * the pipeline could not identify, so the interaction is never dead.
     *
     * Programme hits and channel hits are returned separately because they answer
     * different questions and a merged list would return nothing for a channel
     * name (channels carry no programme text), which reads as broken.
     */
    suspend fun searchGuide(query: String, limit: Int = 50): IptvGuideSearch =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext IptvGuideSearch()
            val base = baseUrl() ?: return@withContext IptvGuideSearch(error = "IPTV is not configured")
            val token = authStore.currentToken() ?: return@withContext IptvGuideSearch()

            runCatching {
                val request = Request.Builder()
                    .url(
                        base.newBuilder()
                            .addPathSegments("iptv/search")
                            .addQueryParameter("q", q)
                            .addQueryParameter("limit", limit.toString())
                            .build()
                    )
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        authStore.clearSession()
                        return@use IptvGuideSearch()
                    }
                    if (!response.isSuccessful) {
                        return@use IptvGuideSearch(error = "HTTP ${response.code}")
                    }
                    val dto = searchAdapter.fromJson(response.body?.string().orEmpty())
                        ?: return@use IptvGuideSearch(error = "empty response")
                    IptvGuideSearch(
                        programmes = dto.results.orEmpty().map { hit ->
                            IptvGuideHit(
                                programme = IptvProgramme(
                                    title = hit.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                                    description = hit.description,
                                    startMs = hit.startMs ?: 0L,
                                    endMs = hit.stopMs ?: hit.startMs ?: 0L,
                                    match = hit.match?.toDomain()
                                ),
                                epgChannelId = hit.epgChannelId,
                                channels = hit.channels.orEmpty().mapNotNull { ref ->
                                    val id = ref.streamId?.takeIf { it.isNotBlank() }
                                        ?: return@mapNotNull null
                                    IptvChannelRef(id, ref.name?.takeIf { it.isNotBlank() } ?: id)
                                }
                            )
                        },
                        channels = dto.channels.orEmpty().mapNotNull { it.toDomain() }
                    )
                }
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "iptv /search failed: ${e.message}")
                IptvGuideSearch(error = e.message ?: "search failed")
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

/**
 * The edge echoes ISO-8601 in `from`/`to`/`horizon`, unlike the per-programme
 * fields, which are already epoch millis. Parsing is best-effort: a value we
 * cannot read costs the caller the edge's own span, and the requested span is
 * used instead — never a crash over a display bound.
 */
private fun parseIsoMs(value: String?): Long? {
    val s = value?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
}

/** Merge two chunked windows: maps union, span keeps the first non-zero answer. */
private fun IptvGuideWindow.merge(other: IptvGuideWindow): IptvGuideWindow =
    IptvGuideWindow(
        fromMs = if (fromMs != 0L) fromMs else other.fromMs,
        toMs = maxOf(toMs, other.toMs),
        aheadHours = aheadHours ?: other.aheadHours,
        byChannel = byChannel + other.byChannel
    )

/**
 * A channel with no usable stream id is dropped rather than defaulted: an id we
 * invented would tune nothing and would key the guide map to a channel that does
 * not exist.
 */
private fun com.nuvio.tv.data.remote.dto.IptvChannelDto.toDomain(): IptvChannel? {
    val id = streamId?.takeIf { it.isNotBlank() } ?: return null
    return IptvChannel(
        streamId = id,
        name = name?.takeIf { it.isNotBlank() } ?: id,
        rawName = rawName,
        categoryId = categoryId,
        icon = icon,
        epgChannelId = epgChannelId,
        hasEpg = hasEpg == true
    )
}

private fun com.nuvio.tv.data.remote.dto.IptvProgrammeDto.toDomain(): IptvProgramme =
    IptvProgramme(
        title = title?.takeIf { it.isNotBlank() } ?: "Untitled",
        description = description,
        startMs = startMs ?: 0L,
        endMs = stopMs ?: startMs ?: 0L,
        match = match?.toDomain()
    )

private fun com.nuvio.tv.data.remote.dto.IptvProgrammeMatchDto.toDomain(): IptvProgrammeMatch =
    IptvProgrammeMatch(
        imdbId = imdbId?.takeIf { it.isNotBlank() },
        tmdbId = tmdbId,
        mediaType = mediaType?.takeIf { it.isNotBlank() },
        year = year,
        canonical = canonical?.takeIf { it.isNotBlank() },
        confidence = confidence,
        source = source,
        season = season,
        episode = episode,
        episodeName = episodeName?.takeIf { it.isNotBlank() },
        episodeSource = episodeSource
    )
