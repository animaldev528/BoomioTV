package com.nuvio.tv.core.profile

import com.nuvio.tv.data.remote.api.BsmApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side soft rating gate: fetches each Nuvio profile's content-rating ceiling
 * from BSM (`GET /api/nuvio/profile-ratings`) and exposes the active profile's ceiling
 * as a [StateFlow]. When BSM is unreachable (e.g. off-LAN) there is no ceiling and the
 * gate fails open; once a ceiling is in force a title whose rating we cannot place on
 * the scale is hidden, because "unrecognised" must not read as "safe".
 */
@Singleton
class BsmRatingGate @Inject constructor(
    private val bsmApi: BsmApi,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ceilings = MutableStateFlow<Map<Int, String>>(emptyMap())

    /**
     * Content-rating ceiling for the currently active profile (null = no ceiling).
     *
     * A kids profile's own flag is authoritative and does not depend on BSM being
     * reachable: the edge does not currently route the ratings endpoint at all, and
     * a LAN-only fetch would otherwise fail open. PG-13 is the ceiling that keeps
     * family films like Junior while dropping R titles like Predator.
     */
    val activeCeiling: StateFlow<String?> = combine(
        profileManager.activeProfileId,
        profileManager.profiles,
        _ceilings
    ) { profileId, profiles, ceilings ->
        if (profiles.firstOrNull { it.id == profileId }?.isKids == true) KIDS_CEILING
        else ceilings[profileId]
    }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            profileManager.activeProfileId.collect { refresh() }
        }
    }

    fun refresh() {
        scope.launch {
            try {
                val resp = bsmApi.getProfileRatings()
                if (resp.isSuccessful) {
                    val body = resp.body().orEmpty()
                    _ceilings.value = body.associate { dto ->
                        dto.profileIndex to (dto.contentRating ?: "G")
                    }
                }
            } catch (_: Throwable) {
                // BSM is LAN-only; leave ceilings empty and fail open.
            }
        }
    }

    fun isAllowed(ageRating: String?): Boolean =
        RatingOrdinal.isAllowed(ageRating, activeCeiling.value)

    companion object {
        const val KIDS_CEILING = "PG-13"
    }
}
