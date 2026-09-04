package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile-scoped local store of "things the user likes" for like-bootstrap.
 *
 * Canonical keys are `movie:<tmdbId>` / `series:<tmdbId>` (matches the backend
 * `taste_picks` PK of (pick_type, tmdb_id)); the stored value is the display name,
 * needed to rebuild [com.nuvio.tv.domain.model.TastePick]s for the "Done for now"
 * full-replace push and for the per-toggle RPC. Mirrors [LibraryPreferences]: one
 * DataStore per profile via [ProfileDataStoreFactory].
 *
 * Likes are pure input signal — nothing renders a browsable "liked" shelf. Local
 * writes are optimistic; [com.nuvio.tv.core.sync.LikeSyncService] mirrors them to
 * the server with the profile index, and the operator watcher re-samples home from
 * the server-side pool.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LikePreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val likedMapKey = stringPreferencesKey("liked_map")
    private val likedMapType = object : TypeToken<Map<String, String>>() {}.type

    /** Canonical key / component helpers shared with the RPC + UI layers. */
    companion object {
        fun keyFor(typeWire: String, tmdbId: Long): String = "$typeWire:$tmdbId"

        fun splitKey(key: String): Pair<String, Long>? {
            val sep = key.indexOf(':')
            if (sep <= 0) return null
            val type = key.substring(0, sep)
            val id = key.substring(sep + 1).toLongOrNull() ?: return null
            return type to id
        }
    }

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    /** Canonical keys (movie:123 / series:123) of everything the ACTIVE profile likes. */
    val likedKeys: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { prefs -> prefs.likedMap().keys }
    }

    /** True when the active profile currently likes (typeWire, tmdbId). */
    fun isLiked(typeWire: String, tmdbId: Long): Flow<Boolean> {
        val key = keyFor(typeWire, tmdbId)
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            store(profileId).data.map { prefs -> prefs.likedMap().containsKey(key) }
        }
    }

    suspend fun isLikedNow(
        typeWire: String,
        tmdbId: Long,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean = store(profileId).data.first().likedMap().containsKey(keyFor(typeWire, tmdbId))

    /**
     * Flips the like state for one title and returns whether it is now liked.
     * Optimistic and instant; sync is a separate concern.
     */
    suspend fun toggle(
        typeWire: String,
        tmdbId: Long,
        name: String,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean {
        val key = keyFor(typeWire, tmdbId)
        var liked = false
        store(profileId).edit { prefs ->
            val map = prefs.likedMap().toMutableMap()
            liked = !map.containsKey(key)
            if (liked) map[key] = name else map.remove(key)
            prefs[likedMapKey] = gson.toJson(map)
        }
        return liked
    }

    /** Explicit add/remove (keeps dialog + optimistic RPC in lockstep on retry). */
    suspend fun setLiked(
        typeWire: String,
        tmdbId: Long,
        name: String,
        liked: Boolean,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val key = keyFor(typeWire, tmdbId)
        store(profileId).edit { prefs ->
            val map = prefs.likedMap().toMutableMap()
            if (liked) map[key] = name else map.remove(key)
            prefs[likedMapKey] = gson.toJson(map)
        }
    }

    /** key -> name for everything the given (default active) profile likes. */
    suspend fun allLiked(profileId: Int = profileManager.activeProfileId.value): Map<String, String> =
        store(profileId).data.first().likedMap()

    private fun Preferences.likedMap(): Map<String, String> {
        val raw = this[likedMapKey] ?: return emptyMap()
        return try {
            gson.fromJson<Map<String, String>>(raw, likedMapType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private companion object {
        const val FEATURE = "like_preferences"
    }
}
