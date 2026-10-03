package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.TastePick
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TastePickSyncService"

/**
 * Saves a curated profile's taste picks to the Nuvio server. Mirrors
 * [ProfileSyncService]: the TV writes through its own Supabase session via the
 * SECURITY DEFINER RPC `sync_push_taste_picks` (nuvio-server migration
 * 00000000000011), which validates the caller + profile + `curated_enabled`,
 * full-replaces the picks, and flips `taste_completed = true`. No direct table
 * access — RLS + grants keep `taste_picks` off anon/authenticated entirely.
 */
@Singleton
class TastePickSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileManager: ProfileManager
) {
    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /**
     * Full-replacement save of [picks] for [profileId]. On success the server has
     * already flipped `taste_completed`, so we mirror that locally — the picker
     * exits without waiting on a profile pull.
     */
    suspend fun pushTastePicks(profileId: Int, picks: List<TastePick>): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val params = buildJsonObject {
                    put("p_profile_index", profileId)
                    put("p_picks", buildJsonArray {
                        picks.forEach { pick ->
                            addJsonObject {
                                put("pick_type", pick.pickType.wire)
                                put("tmdb_id", pick.tmdbId)
                                put("name", pick.name)
                            }
                        }
                    })
                }
                withJwtRefreshRetry {
                    postgrest.rpc("sync_push_taste_picks", params)
                }
                Log.d(TAG, "Pushed ${picks.size} taste picks for profile $profileId")
                profileManager.markProfileTasteCompleted(profileId)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push taste picks", e)
                Result.failure(e)
            }
        }
}
