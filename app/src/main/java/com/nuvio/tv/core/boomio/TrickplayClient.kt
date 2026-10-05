package com.nuvio.tv.core.boomio

import android.util.Log
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One tile of a sprite sheet, and the stretch of the title it shows.
 *
 * [col]/[row] are already resolved from the index's `#xywh` rectangle, so the UI
 * never has to know the tiling arithmetic — it draws tile `(col, row)` and that
 * is the frame.
 */
data class TrickplayCue(
    val startMs: Long,
    val endMs: Long,
    val sheet: String,
    val col: Int,
    val row: Int
)

/**
 * A title's ready-made scrub preview: where the sheets are, how they are tiled,
 * and the index that maps a position to one tile.
 *
 * [baseUrl] is the token-free prefix bsc returned. The token is carried
 * separately and attached per request, because that is the contract on the
 * server side — `url` stays a clean prefix the client appends `/sprite-…` onto.
 *
 * [version] is a digest of the index this set was parsed from, and exists only
 * to version the image cache — see [indexVersion] and [sheetCacheKey].
 */
data class TrickplaySet(
    val baseUrl: String,
    val token: String,
    val tileW: Int,
    val tileH: Int,
    val cols: Int,
    val rows: Int,
    val cues: List<TrickplayCue>,
    val version: String = ""
) {
    /** The tile covering [positionMs], or the first one when the position precedes the index. */
    fun cueAt(positionMs: Long): TrickplayCue? {
        if (cues.isEmpty()) return null
        val p = positionMs.coerceAtLeast(0L)
        var lo = 0
        var hi = cues.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (cues[mid].startMs <= p) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (best >= 0) cues[best] else cues.first()
    }

    /** The sheet to fetch, with the stream token attached. */
    fun sheetUrl(cue: TrickplayCue): String = "$baseUrl/${cue.sheet}?token=$token"

    /**
     * Identity of one rendering of this reel: the token-free prefix plus the
     * index digest. Two `TrickplaySet`s that share it describe identical bytes.
     */
    val cacheNamespace: String
        get() = if (version.isEmpty()) baseUrl else "$baseUrl#$version"

    /**
     * A cache key that excludes the token but INCLUDES the index version.
     *
     * The token is left out because it rotates (it is IP-bound and re-minted per
     * session), and keying the image cache on the full URL would throw away
     * every decoded sheet on each rotation and re-download megabytes over the
     * LAN for bytes that cannot have changed.
     *
     * The version is left IN for the mirror-image reason: the sheet FILENAMES do
     * not change when a reel is re-rendered, so a key built from the name alone
     * keeps serving the previous render's pictures indefinitely. That is not
     * hypothetical — after a title's reel was regenerated server-side, this
     * cache went on painting the OLD `sprite-000.jpg` (a single frame of the
     * opening credits) over the first two minutes of the film, which reads
     * exactly like "the preview is broken". Folding the digest in retires every
     * cached sheet the moment the index changes, and costs nothing when it has
     * not.
     */
    fun sheetCacheKey(cue: TrickplayCue): String = "$cacheNamespace/${cue.sheet}"
}

/**
 * Client for bsc's trickplay lookup (`GET /tp/<mediaKey>?token=`).
 *
 * Deliberately the thinnest possible thing: one lookup, one index fetch, then
 * the images come from the ROLE EDGE directly (the two-hop contract — bsc
 * answers *which edge has these*, and never proxies the bytes; a scrub drag can
 * pull tens of megabytes and bsc is the playback-path relay).
 *
 * Every failure is `null`. This sits on the seek path: a title with no sprites
 * yet, a dead bsc, a 403, a malformed index — all of them mean "no thumbnail",
 * which is the ordinary state, not an error the viewer should ever see. Nothing
 * here may throw into the player or block a seek.
 *
 * The token is the viewer's own STREAM token — the same JWT already playing
 * through the edge — not the companion session token. The server verifies it
 * with `verifyStreamToken()`, exactly as `routes/http-proxy.js` does, so a token
 * that plays also previews.
 */
@Singleton
class TrickplayClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    /**
     * mediaKey → the resolved set. Bounded by the number of titles watched in
     * one session, and cleared by nothing — a TV watches a handful of episodes
     * at a time, and each entry is a few hundred cues, not pixels.
     */
    private val cache = ConcurrentHashMap<String, TrickplaySet>()

    /** Configured only when the companion host was compiled in. */
    fun isConfigured(): Boolean = baseUrl() != null

    /**
     * Resolve a title's sprite set, or null when it has none.
     *
     * Cached per mediaKey: the lookup and the index are two round trips, and a
     * scrub can ask for a new tile dozens of times a second. Only the FIRST ask
     * for a title pays them.
     */
    suspend fun lookup(mediaKey: String, streamToken: String): TrickplaySet? =
        withContext(Dispatchers.IO) {
            fun bail(why: String): TrickplaySet? {
                Log.d(TAG, "lookup $mediaKey -> $why")
                return null
            }

            cache[mediaKey]?.takeIf { it.token == streamToken }?.let {
                Log.d(TAG, "lookup $mediaKey -> cache hit")
                return@withContext it
            }

            val base = baseUrl() ?: return@withContext bail("not configured")
            val lookupUrl = base.newBuilder()
                .addPathSegments("tp/$mediaKey")
                .addQueryParameter("token", streamToken)
                .build()

            val body = get(lookupUrl) ?: return@withContext bail("no lookup body")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext bail("bad json")

            val url = json.optString("url").takeIf { it.isNotBlank() }
                ?: return@withContext bail("no url in $body")
            val tileW = json.optInt("tileW", 0)
            val tileH = json.optInt("tileH", 0)
            val cols = json.optInt("cols", 0)
            val rows = json.optInt("rows", 0)
            if (tileW <= 0 || tileH <= 0) {
                return@withContext bail("bad tile ${tileW}x$tileH")
            }

            val indexUrl = url.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegment("index.vtt")
                ?.addQueryParameter("token", streamToken)
                ?.build()
                ?: return@withContext bail("bad index url $url")

            val vtt = get(indexUrl) ?: return@withContext bail("no index at $indexUrl")
            val cues = parseIndex(vtt, tileW, tileH)
            if (cues.isEmpty()) {
                return@withContext bail("empty index (${vtt.length} chars)")
            }

            TrickplaySet(
                baseUrl = url.trimEnd('/'),
                token = streamToken,
                tileW = tileW,
                tileH = tileH,
                // The index's own rectangles are authoritative for the tile shape;
                // cols/rows are only used to size the sheet image. Falling back to
                // 1 keeps a bad answer from producing a zero-sized image request.
                cols = cols.coerceAtLeast(1),
                rows = rows.coerceAtLeast(1),
                cues = cues,
                version = indexVersion(vtt)
            ).also {
                Log.d(
                    TAG,
                    "resolved $mediaKey cues=${cues.size} v=${it.version} " +
                        "tile=${tileW}x${tileH} grid=${cols}x$rows first=${cues.first().startMs} " +
                        "last=${cues.last().startMs}"
                )
                cache[mediaKey] = it
            }
        }

    /** Drop a cached set, e.g. after a 403 says the token it holds has expired. */
    fun invalidate(mediaKey: String) {
        cache.remove(mediaKey)
    }

    // ── transport ────────────────────────────────────────────────────────────

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

    /**
     * One GET, or null. Never throws, never logs the token — the query carries a
     * bearer credential, so only the path is ever written down.
     */
    private fun get(url: HttpUrl): String? = try {
        okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else {
                Log.d(TAG, "no sprites: HTTP ${response.code} for ${url.encodedPath}")
                null
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "lookup failed for ${url.encodedPath}: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "TrickplayClient"

        /**
         * A content digest of the index — FNV-1a, as a hex string.
         *
         * This is the ONLY thing that tells two renderings of the same title
         * apart on the client. The edge serves sheets at stable, unversioned
         * paths (`<url>/sprite-000.jpg`), so filename and URL are identical
         * across renders while the pixels behind them are not; the index is what
         * actually changes, so hashing it is both sufficient and free — the
         * index is already in hand before the first sheet is requested.
         */
        fun indexVersion(vtt: String): String {
            var h = java.lang.Long.parseUnsignedLong("cbf29ce484222325", 16)
            for (b in vtt.encodeToByteArray()) {
                h = h xor (b.toLong() and 0xffL)
                h *= 0x100000001b3L
            }
            return java.lang.Long.toHexString(h)
        }

        /**
         * The frozen `#xywh` index shape (lib/trickplay.js `buildVttFromTimes`):
         *
         *     WEBVTT
         *
         *     00:00:00.000 --> 00:00:01.600
         *     sprite-000.jpg#xywh=0,0,320,180
         *
         * Parsed by scanning for cue lines rather than by splitting on blank
         * lines: the generator omits the blank line after the last cue, so a
         * split-based parser drops the final tile — which is the one covering the
         * end of the title, exactly where a viewer scrubs to check the ending.
         */
        fun parseIndex(vtt: String, tileW: Int, tileH: Int): List<TrickplayCue> {
            val lines = vtt.split('\n')
            val cues = ArrayList<TrickplayCue>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                val arrow = line.indexOf("-->")
                if (arrow <= 0) {
                    i++
                    continue
                }
                val start = parseTimestamp(line.substring(0, arrow))
                val end = parseTimestamp(line.substring(arrow + 3))

                var j = i + 1
                while (j < lines.size && lines[j].isBlank()) j++
                val payload = if (j < lines.size) lines[j].trim() else ""

                val parts = payload.substringAfter("#xywh=", "").split(',')
                if (start != null && parts.size == 4) {
                    val x = parts[0].trim().toIntOrNull()
                    val y = parts[1].trim().toIntOrNull()
                    val sheet = payload.substringBefore("#xywh=").trim()
                    if (x != null && y != null && sheet.isNotEmpty()) {
                        cues += TrickplayCue(
                            startMs = start,
                            endMs = end ?: start,
                            sheet = sheet,
                            col = x / tileW,
                            row = y / tileH
                        )
                    }
                }
                i = j + 1
            }
            return cues.sortedBy { it.startMs }
        }

        /** `HH:MM:SS.mmm`, `MM:SS.mmm` — and tolerate trailing cue settings. */
        fun parseTimestamp(raw: String): Long? {
            val stamp = raw.trim().substringBefore(' ').trim()
            val parts = stamp.split(':')
            if (parts.isEmpty() || parts.size > 3) return null
            val seconds = parts.last().toDoubleOrNull() ?: return null
            var total = seconds
            if (parts.size >= 2) {
                val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
                total += minutes * 60
            }
            if (parts.size == 3) {
                val hours = parts[0].toLongOrNull() ?: return null
                total += hours * 3600
            }
            return (total * 1000).toLong()
        }
    }
}
