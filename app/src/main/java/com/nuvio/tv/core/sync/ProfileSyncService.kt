package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseProfileLockState
import com.nuvio.tv.data.remote.supabase.SupabaseProfile
import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileSyncService"
private const val PROFILE_PULL_MIN_INTERVAL_MS = 10_000L

internal data class ProfilePullFreshness(
    val userId: String? = null,
    val pulledAtMs: Long = 0L
) {
    fun isRecent(candidateUserId: String, nowMs: Long): Boolean {
        return userId == candidateUserId &&
            pulledAtMs > 0L &&
            nowMs >= pulledAtMs &&
            nowMs - pulledAtMs < PROFILE_PULL_MIN_INTERVAL_MS
    }
}

sealed class SetProfilePinResult {
    object Success : SetProfilePinResult()
    object CurrentPinRequired : SetProfilePinResult()
    data class Failure(val throwable: Throwable) : SetProfilePinResult()
}

@Singleton
class ProfileSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val profileDataStore: ProfileDataStore,
    private val profileManager: ProfileManager,
    private val syncClientIdentity: SyncClientIdentity
) {
    private val pullMutex = Mutex()
    private var pullFreshness = ProfilePullFreshness()
    private var lastPulledProfiles = emptyList<UserProfile>()

    /**
     * Account id whose empty remote profile list we already seeded from the local store, so the
     * seed push runs at most once per signed-in account per app session. See
     * [seedRemoteFromLocalIfEmpty] for why this must never run against a non-empty remote range.
     */
    @Volatile
    private var seededEmptyRemoteForUserId: String? = null

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profiles = profileManager.profiles.value

            val params = buildJsonObject {
                put("p_client_max_profiles", ProfileManager.MAX_PROFILES)
                put("p_profiles", buildJsonArray {
                    profiles.forEach { profile ->
                        addJsonObject {
                            put("profile_index", profile.id)
                            put("name", profile.name)
                            put("avatar_color_hex", profile.avatarColorHex)
                            put("uses_primary_addons", profile.usesPrimaryAddons)
                            put("uses_primary_plugins", profile.usesPrimaryPlugins)
                            put("is_kids", profile.isKids)
                            put("avatar_id", if (profile.avatarUrl.isNullOrBlank()) profile.avatarId else null)
                            put("avatar_url", profile.avatarUrl?.takeIf { it.isNotBlank() })
                            put("profile_background_id", profile.profileBackgroundId)
                            put("profile_background_url", profile.profileBackgroundUrl?.takeIf { it.isNotBlank() })
                        }
                    }
                })
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_push_profiles", params)
            }

            Log.d(TAG, "Pushed ${profiles.size} profiles to remote")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push profiles to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(force: Boolean = false): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        pullMutex.withLock {
            val userId = (authManager.authState.value as? AuthState.FullAccount)?.userId
            val now = SystemClock.elapsedRealtime()
            if (!force && userId != null && pullFreshness.isRecent(userId, now)) {
                return@withLock Result.success(lastPulledProfiles)
            }
            try {
                val response = withJwtRefreshRetry {
                    postgrest.rpc("sync_pull_profiles")
                }
                val remote = response.decodeList<SupabaseProfile>()

                Log.d(TAG, "pullFromRemote: fetched ${remote.size} profiles from Supabase")

                val profiles = remote.map { entry ->
                    UserProfile(
                        id = entry.profileIndex,
                        name = entry.name,
                        avatarColorHex = entry.avatarColorHex,
                        usesPrimaryAddons = entry.usesPrimaryAddons,
                        usesPrimaryPlugins = entry.usesPrimaryPlugins,
                        avatarId = entry.avatarId,
                        avatarUrl = entry.avatarUrl,
                        profileBackgroundId = entry.profileBackgroundId,
                        profileBackgroundUrl = entry.profileBackgroundUrl,
                        curatedEnabled = entry.curatedEnabled,
                        tasteCompleted = entry.tasteCompleted,
                        tasteEnabled = entry.tasteEnabled,
                        isKids = entry.isKids,
                        // Parent-set (BSM) ceilings; null means "not chosen".
                        kidsRatingMovie = entry.kidsRatingMovie,
                        kidsRatingTv = entry.kidsRatingTv
                    )
                }

                // A brand-new account has no profile rows server-side yet, and the local store
                // synthesises a default "Profile 1" that nothing ever pushes (pushToRemote() is
                // only called from the profile create/save screens). Seed the server from the
                // local list the first time we see the account's remote list genuinely empty, so
                // profile 1 materialises server-side on first login instead of waiting for
                // someone to open Manage Profiles. Guarded and latched in the helper below.
                var effectiveProfiles = profiles
                if (profiles.isNotEmpty()) {
                    profileDataStore.replaceAllProfiles(profiles)
                    Log.d(TAG, "Merged ${profiles.size} remote profiles into local store")
                } else if (seedRemoteFromLocalIfEmpty(userId)) {
                    // The remote range is no longer empty, so report the list we just seeded from
                    // rather than the stale empty pull result.
                    effectiveProfiles = profileManager.profiles.value
                }

                val currentUserId = (authManager.authState.value as? AuthState.FullAccount)?.userId
                if (userId != null && currentUserId == userId) {
                    lastPulledProfiles = effectiveProfiles
                    // Only a pull that actually carried the SERVER's rows may satisfy the
                    // freshness window. When the remote range was empty we seeded it from the
                    // LOCAL list, whose taste/kids flags still hold client defaults and are not
                    // what the server ends up storing (sync_push_profiles omits those columns, so
                    // the row takes the column default). Reporting that local list as "fresh"
                    // serves it to every pull for the next PROFILE_PULL_MIN_INTERVAL_MS — and on a
                    // first login the whole onboarding sequence (retried pulls, then
                    // requestSyncNow) lands inside that window, so the account would run its
                    // entire first session without its server-side flags: no like-bootstrap, no
                    // taste hint, until a cold start re-pulled. Leaving the window unstamped
                    // forces the next pull to fetch the row the seed just created.
                    pullFreshness = if (profiles.isNotEmpty()) {
                        ProfilePullFreshness(
                            userId = userId,
                            pulledAtMs = SystemClock.elapsedRealtime()
                        )
                    } else {
                        ProfilePullFreshness()
                    }
                }
                Result.success(effectiveProfiles)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull profiles from remote", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Seeds the server with the local profile list when a pull came back genuinely empty for a
     * signed-in account.
     *
     * DATA-LOSS GUARD — do not weaken. The `sync_push_profiles` RPC DELETEs every profile row in
     * the caller's profile range that is not present in the pushed payload. Pushing a stale or
     * partial local list over a remote range that already holds profiles would therefore destroy
     * them. This function is only reachable from the branch of [pullFromRemote] where
     * `sync_pull_profiles` returned an empty list for this call, so the push can only ever run
     * against an empty range — where the RPC has nothing to delete. Never call it as a retry, and
     * never call it when the remote list was non-empty.
     *
     * Idempotent: latched per account id, so realtime "profiles" events, repeated startup pulls
     * and recompositions do not push again. The latch is only set after a successful push, so a
     * transient failure may be retried on a later pull — still only while the remote list is
     * empty, which keeps the guard above intact.
     *
     * Non-fatal: failures are logged and swallowed so startup continues against the local profile
     * exactly as it does today.
     *
     * Called while [pullMutex] is held; [pushToRemote] does not take that mutex, so no deadlock.
     *
     * @return true when the local profiles were pushed successfully.
     */
    private suspend fun seedRemoteFromLocalIfEmpty(userId: String?): Boolean {
        // null means the auth state is not a FullAccount (signed out / loading): never push.
        if (userId == null) return false
        if (seededEmptyRemoteForUserId == userId) return false

        val localProfiles = profileManager.profiles.value
        if (localProfiles.isEmpty()) {
            Log.w(TAG, "Skipping profile seed for $userId: local profile list is empty")
            return false
        }

        return pushToRemote()
            .onSuccess {
                seededEmptyRemoteForUserId = userId
                Log.i(
                    TAG,
                    "Remote profile list was empty for $userId; seeded ${localProfiles.size} local profile(s)"
                )
            }
            .onFailure { error ->
                Log.e(
                    TAG,
                    "Failed to seed remote profiles for $userId, continuing with local profiles",
                    error
                )
            }
            .isSuccess
    }

    suspend fun deleteProfileData(profileId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                putSyncOriginClientId(syncClientIdentity)
            }
            withJwtRefreshRetry {
                postgrest.rpc("sync_delete_profile_data", params)
            }

            Log.d(TAG, "Deleted remote data for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote profile data for profile $profileId", e)
            Result.failure(e)
        }
    }

    suspend fun pullProfileLockStates(): Result<Map<Int, Boolean>> = withContext(Dispatchers.IO) {
        try {
            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_profile_locks")
            }
            val remote = response.decodeList<SupabaseProfileLockState>()
            val result = remote.associate { it.profileIndex to it.pinEnabled }
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull profile lock states", e)
            Result.failure(e)
        }
    }

    suspend fun setProfilePin(profileId: Int, pin: String, currentPin: String? = null): SetProfilePinResult = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            withJwtRefreshRetry {
                postgrest.rpc("set_profile_pin", params)
            }
            SetProfilePinResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set profile PIN", e)
            if (isCurrentPinRequiredError(e)) {
                SetProfilePinResult.CurrentPinRequired
            } else {
                SetProfilePinResult.Failure(e)
            }
        }
    }

    private fun isCurrentPinRequiredError(e: Throwable): Boolean =
        e.message?.contains("Current PIN is required", ignoreCase = true) == true

    suspend fun clearProfilePin(profileId: Int, currentPin: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                if (!currentPin.isNullOrBlank()) {
                    put("p_current_pin", currentPin)
                }
            }
            withJwtRefreshRetry {
                postgrest.rpc("clear_profile_pin", params)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear profile PIN", e)
            Result.failure(e)
        }
    }

    suspend fun verifyProfilePin(profileId: Int, pin: String): Result<SupabaseProfilePinVerifyResult> = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_pin", pin)
            }
            val response = withJwtRefreshRetry {
                postgrest.rpc("verify_profile_pin", params)
            }
            val decoded = response.decodeList<SupabaseProfilePinVerifyResult>().firstOrNull()
                ?: SupabaseProfilePinVerifyResult(unlocked = false, retryAfterSeconds = 0)
            Result.success(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify profile PIN", e)
            Result.failure(e)
        }
    }
}
