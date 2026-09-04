package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.domain.model.TastePick
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LikeSyncService"

/**
 * Mirrors a single like/unlike to the Nuvio server for like-bootstrap profiles.
 *
 * Writes through the SECURITY DEFINER RPC `sync_toggle_taste_pick` (nuvio-server
 * migration 00000000000012): it upserts on `p_liked=true` / deletes on false,
 * never flips `taste_completed` (that only happens on "Done for now" via
 * [TastePickSyncService]). Local state is written optimistically by the UI first;
 * this is the fire-and-mirror step in the same JWT-refresh pattern as the other
 * sync services. A failed toggle is logged and retried on the next user action —
 * the operator watcher rebuilds shelves on the server-side pool regardless.
 */
@Singleton
class LikeSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest
) {
    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun toggleLike(profileId: Int, pick: TastePick, liked: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val params = buildJsonObject {
                    put("p_profile_index", profileId)
                    put("p_liked", liked)
                    put("p_pick", buildJsonObject {
                        put("pick_type", pick.pickType.wire)
                        put("tmdb_id", pick.tmdbId)
                        put("name", pick.name)
                    })
                }
                withJwtRefreshRetry {
                    postgrest.rpc("sync_toggle_taste_pick", params)
                }
                Log.d(TAG, "Toggled ${pick.pickType.wire}:${pick.tmdbId} liked=$liked for profile $profileId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle like for profile $profileId", e)
                Result.failure(e)
            }
        }
}
