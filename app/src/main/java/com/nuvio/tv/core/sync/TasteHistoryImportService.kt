package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.remote.api.BsmApi
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.BsmHistoryImportRequestDto
import com.nuvio.tv.data.remote.dto.BsmHistoryRowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nuvio.tv.data.repository.TraktAuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TasteHistoryImport"

/**
 * Hands the TV's OWN Trakt history up to the core, so a profile's taste rows can be seeded
 * without an operator walking the dashboard.
 *
 * The direction is deliberate: the CLIENT is the sync source for provider data, not the server.
 * The TV already holds a Trakt token and already pages the synced watched endpoints for its own
 * continue-watching, so it is the one component that can read a user's history without the core
 * storing a provider credential. The server half (`bsm/routes/history-import.js`) takes the
 * account from the caller's Supabase token and never from the body, and re-derives every field it
 * stores — so nothing here is taken on trust at the other end.
 *
 * Registered beside [TastePickSyncService], which pushes the other half of the same pool: that
 * one sends the picks a user curated by hand through the Like picker, this one seeds the pool
 * they never curated. Both end at `taste_picks` for the active profile.
 */
@Singleton
class TasteHistoryImportService @Inject constructor(
    private val authManager: AuthManager,
    private val traktAuthService: TraktAuthService,
    private val traktApi: TraktApi,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val bsmApi: BsmApi,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val PROVIDER = "trakt"

        /** Trakt's own page size for the synced endpoints — the same 250 the progress sync uses. */
        const val PAGE_LIMIT = 250

        /** Upper bound on pages per list. 40 x 250 is far past any real library, and the row cap
         *  below is what actually binds. */
        const val MAX_PAGES = 40

        /**
         * Rows sent in one request, newest first.
         *
         * The route accepts 1 MB of JSON (`express.json({ limit: '1mb' })`) against a 20 000-row
         * semantic cap, so the body size is what a heavy library hits first. 4000 rows is roughly
         * 350 KB with a long title in every one — comfortably inside the limit, and still more
         * titles than any profile needs to characterise its taste.
         */
        const val MAX_ROWS = 4_000
    }

    /**
     * Import [profileId]'s Trakt history, if it needs it.
     *
     * Returns whether a request was actually made and accepted — `false` covers every skip: the
     * profile is not a candidate, Trakt is not connected, or the history produced no usable rows.
     * The route allows only 6 imports per 15 minutes per account, so every one of those skips has
     * to happen locally rather than as a refused request.
     *
     * Never throws: a failure is a [Result.failure] for the caller to log, not something that
     * should interrupt a startup sync.
     */
    suspend fun importIfNeeded(profileId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // The gate is the profile's own server-owned flags, pulled with the profile itself.
            // `needsTasteHint` is exactly "taste-enabled, not kids, not yet completed", which are
            // the two conditions the route refuses with 409 plus the one that means "already done".
            // Checking here is what keeps a kids profile from spending a rate-limit slot on every
            // single startup only to be refused identically each time.
            val profile = profileManager.profiles.value.find { it.id == profileId }
                ?: return@withContext Result.success(false)
            if (!profile.needsTasteHint) return@withContext Result.success(false)

            val traktState = traktAuthDataStore.getCurrentState(profileId)
            if (!traktState.isAuthenticated) return@withContext Result.success(false)

            val movies = fetchAllPages("watched-movies") { page ->
                traktAuthService.executeAuthorizedRequest { authHeader ->
                    traktApi.getWatched(
                        authorization = authHeader,
                        type = "movies",
                        page = page,
                        limit = PAGE_LIMIT
                    )
                }
            } ?: return@withContext Result.success(false)

            val shows = fetchAllPages("watched-shows") { page ->
                traktAuthService.executeAuthorizedRequest { authHeader ->
                    traktApi.getWatchedShows(
                        authorization = authHeader,
                        page = page,
                        limit = PAGE_LIMIT
                    )
                }
            } ?: return@withContext Result.success(false)

            // Newest first, so the cap drops the oldest titles rather than the ones that say most
            // about what this profile watches now.
            val rows = (movieRows(movies) + showRows(shows))
                .sortedByDescending { it.tsMs }
                .take(MAX_ROWS)

            if (rows.isEmpty()) {
                Log.d(TAG, "Trakt history produced no usable rows for profile $profileId")
                return@withContext Result.success(false)
            }

            val token = authManager.currentAccessToken()
            if (token.isNullOrBlank()) {
                Log.w(TAG, "No Supabase session; skipping taste history import")
                return@withContext Result.success(false)
            }

            val response = bsmApi.importHistory(
                authorization = "Bearer $token",
                body = BsmHistoryImportRequestDto(
                    profileIndex = profileId,
                    provider = PROVIDER,
                    watched = rows
                )
            )

            if (!response.isSuccessful) {
                // A refusal is an answer, not a failure: 409 is kids/not-taste-enabled, 429 is the
                // rate limiter, 404 is a profile the server does not have. Log the server's own
                // wording and leave the profile alone — the next startup will try again.
                Log.w(
                    TAG,
                    "history-import refused: code=${response.code()} body=${response.errorBody()?.string()}"
                )
                return@withContext Result.success(false)
            }

            val body = response.body()
            Log.d(
                TAG,
                "history-import ok for profile $profileId: mode=${body?.mode} liked=${body?.liked} " +
                    "inserted=${body?.inserted} skippedNoId=${body?.skippedNoId} dropped=${body?.dropped}"
            )
            // The server flips taste_completed inside the same call that inserted the rows, so a
            // real insert means the profile has stopped needing the first-run taste hint. Mirror it
            // now instead of waiting for the next profile pull.
            if (body?.tasteCompleted == true) {
                profileManager.markProfileTasteCompleted(profileId)
            }
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Taste history import failed for profile $profileId", e)
            Result.failure(e)
        }
    }

    /**
     * Page a synced Trakt list to the end, or null if any page cannot be read.
     *
     * Trakt reports the total page count in `X-Pagination-Page-Count`, so the loop trusts the
     * header when it is present and otherwise keeps going until a short page comes back.
     */
    private suspend fun <T> fetchAllPages(
        label: String,
        fetch: suspend (page: Int) -> Response<List<T>>?
    ): List<T>? {
        val items = mutableListOf<T>()
        var page = 1
        while (page <= MAX_PAGES) {
            val response = fetch(page) ?: run {
                Log.w(TAG, "$label: request returned null on page=$page")
                return null
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "$label: non-success code=${response.code()} on page=$page")
                return null
            }
            val pageItems = response.body().orEmpty()
            if (pageItems.isEmpty()) break
            items.addAll(pageItems)
            val pageCount = response.headers()["X-Pagination-Page-Count"]?.toIntOrNull()
            if (pageCount != null && page >= pageCount) break
            page += 1
        }
        if (page > MAX_PAGES) {
            Log.w(TAG, "$label: stopped at the $MAX_PAGES-page ceiling")
        }
        return items
    }

    private fun movieRows(items: List<TraktWatchedMovieItemDto>): List<BsmHistoryRowDto> =
        items.mapNotNull { item ->
            val movie = item.movie ?: return@mapNotNull null
            val tmdbId = movie.ids?.tmdb ?: return@mapNotNull null
            val title = movie.title?.trim().orEmpty()
            if (tmdbId <= 0 || title.isEmpty()) return@mapNotNull null
            BsmHistoryRowDto(
                kind = "movie",
                tmdbId = tmdbId,
                title = title,
                year = movie.year,
                tsMs = orderedAt(item.lastWatchedAt)
            )
        }

    private fun showRows(items: List<TraktWatchedShowItemDto>): List<BsmHistoryRowDto> =
        items.mapNotNull { item ->
            val show = item.show ?: return@mapNotNull null
            val tmdbId = show.ids?.tmdb ?: return@mapNotNull null
            val title = show.title?.trim().orEmpty()
            if (tmdbId <= 0 || title.isEmpty()) return@mapNotNull null
            BsmHistoryRowDto(
                kind = "series",
                tmdbId = tmdbId,
                title = title,
                year = show.year,
                tsMs = orderedAt(item.lastWatchedAt)
            )
        }

    /**
     * Trakt's ISO-8601 timestamp as an ordering key.
     *
     * Deliberately not `parseIsoToMillis`, which substitutes the current time for an absent value.
     * That is right for a "last watched" label and wrong here: the server orders these oldest
     * first and gives the newest row the highest taste position, so stamping "now" on a title whose
     * real time we do not have would push it to the front of the profile's rows. The sanitizer's
     * own default for a missing time is 0, and this matches it.
     */
    private fun orderedAt(value: String?): Long =
        if (value.isNullOrBlank()) 0L
        else runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
}
