package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.like.resolveLikeTarget
import com.nuvio.tv.core.sync.LikeSyncService
import com.nuvio.tv.data.local.LikePreferences
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TastePick
import com.nuvio.tv.domain.model.TastePickType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Like-bootstrap state for the HOME path. The shared poster-options dialog
 * (search/catalog/hub screens) keeps its own copy in
 * `PosterOptionsController`; HomeScreen uses a private duplicate dialog, so the
 * like gate + actions are mirrored here as extension functions on
 * [HomeViewModel], keyed by the same [homeItemStatusKey] the library/watched
 * poster actions use. All like writes are optimistic + local-first, mirroring
 * [LikeSyncService] the same way the shared path does.
 */

/** Drives the profile gate (Like rows + first-run hint visibility) and clears
 *  resolved per-tile like state on profile switch. */
internal fun HomeViewModel.observeTasteState() {
    viewModelScope.launch {
        var lastProfileId: Int? = null
        combine(profileManager.profiles, profileManager.activeProfileId) { profiles, id ->
            profiles.find { it.id == id }
        }.collectLatest { profile ->
            val profileId = profile?.id
            val enabled = profile?.likeBootstrapEnabled == true
            val hint = profile?.needsTasteHint == true
            val profileSwitched = profileId != null && profileId != lastProfileId
            lastProfileId = profileId
            _uiState.update { state ->
                if (!profileSwitched && state.posterLikeVisible == enabled && state.showTasteHint == hint) {
                    state
                } else {
                    // Any profile switch clears resolved per-tile like state so one
                    // profile's dialog can't leak into the next; the gate turning off
                    // also drops it (kids/legacy profiles never render a stale Like).
                    val reset = profileSwitched || !enabled
                    state.copy(
                        posterLikeVisible = enabled,
                        showTasteHint = hint,
                        posterLikeTargets = if (reset) emptyMap() else state.posterLikeTargets,
                        posterLikeMembership = if (reset) emptyMap() else state.posterLikeMembership,
                        posterLikePending = if (reset) emptySet() else state.posterLikePending
                    )
                }
            }
        }
    }
}

/** Populates the like identity + membership for a just-opened poster dialog. Reads
 *  fresh from the local store so a tile liked elsewhere always reflects current state. */
fun HomeViewModel.refreshPosterLikeStatus(item: MetaPreview) {
    if (!_uiState.value.posterLikeVisible) return
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    viewModelScope.launch {
        val profileId = profileManager.activeProfileId.value
        runCatching {
            val pick = resolveLikeTarget(item, tmdbService)
            val liked = pick?.let {
                likePreferences.isLikedNow(it.pickType.wire, it.tmdbId, profileId)
            } ?: false
            _uiState.update { state ->
                if (pick == null) {
                    state.copy(posterLikeTargets = state.posterLikeTargets - statusKey)
                } else {
                    state.copy(
                        posterLikeTargets = state.posterLikeTargets + (statusKey to pick),
                        posterLikeMembership = state.posterLikeMembership + (statusKey to liked)
                    )
                }
            }
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to resolve like status for ${item.id}: ${error.message}")
        }
    }
}

/** Optimistically flips the like state, persists it locally, then mirrors it to
 *  the server. On a sync failure the local write is reverted (matches the shared
 *  poster-options controller — the operator watcher rebuilds home from the server pool). */
fun HomeViewModel.togglePosterLike(item: MetaPreview) {
    if (!_uiState.value.posterLikeVisible) return
    val statusKey = homeItemStatusKey(item.id, item.apiType)
    val pick = _uiState.value.posterLikeTargets[statusKey] ?: return
    if (statusKey in _uiState.value.posterLikePending) return
    val desired = _uiState.value.posterLikeMembership[statusKey] != true
    val profileId = profileManager.activeProfileId.value

    _uiState.update { state ->
        state.copy(
            posterLikePending = state.posterLikePending + statusKey,
            posterLikeMembership = state.posterLikeMembership + (statusKey to desired)
        )
    }
    viewModelScope.launch {
        likePreferences.setLiked(
            typeWire = pick.pickType.wire,
            tmdbId = pick.tmdbId,
            name = pick.name,
            liked = desired,
            profileId = profileId
        )
        likeSyncService.toggleLike(profileId, pick, desired).onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to sync like for ${pick.pickType.wire}:${pick.tmdbId}: ${error.message}")
            likePreferences.setLiked(
                typeWire = pick.pickType.wire,
                tmdbId = pick.tmdbId,
                name = pick.name,
                liked = !desired,
                profileId = profileId
            )
            _uiState.update { state ->
                state.copy(posterLikeMembership = state.posterLikeMembership + (statusKey to !desired))
            }
        }
        _uiState.update { state ->
            state.copy(posterLikePending = state.posterLikePending - statusKey)
        }
    }
}

/** "Done for now": pushes the profile's full current like set through
 *  [com.nuvio.tv.core.sync.TastePickSyncService.pushTastePicks], which flips
 *  taste_completed server-side and locally. With 0 likes the push is skipped so
 *  onboarding is never burned on an empty set — the starter home stays and the
 *  hint returns later. On success a safe add-only addon re-list is re-fired over
 *  the next ~40s (see [refreshTasteRowsUntilPublished]) so the server's rebuilt
 *  /row/ addons are installed and their rows appear in place — no app restart,
 *  no wait on the home-refresh TTL. */
fun HomeViewModel.completeTasteOnboarding() {
    val profile = profileManager.activeProfile ?: return
    if (!profile.needsTasteHint || _uiState.value.tasteHintBusy) return
    val profileId = profileManager.activeProfileId.value
    _uiState.update { it.copy(tasteHintBusy = true) }
    viewModelScope.launch {
        var pushed = false
        try {
            val picks = buildLikedPicks(profileId)
            if (picks.isEmpty()) {
                Log.i(HomeViewModel.TAG, "Done for now with 0 likes — skipping push; hint stays for this profile")
            } else {
                tastePickSyncService.pushTastePicks(profileId, picks)
                    .onSuccess { pushed = true }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to push taste picks on Done: ${error.message}")
                    }
                // markProfileTasteCompleted happens inside pushTastePicks on server success;
                // on failure we intentionally stay on the hint so it returns later.
            }
        } catch (error: Exception) {
            Log.w(HomeViewModel.TAG, "Failed to complete taste onboarding: ${error.message}")
        } finally {
            _uiState.update { it.copy(tasteHintBusy = false) }
        }
        if (pushed) refreshTasteRowsUntilPublished()
    }
}

/** The Done RPC returns before the server's async personal build has published
 *  (pg_net -> :3977 builder, 1-min watcher as fallback), so a single refresh
 *  lands on the old rows. The rebuilt rows ride on /row/ addons the server
 *  publishes as NEW addon urls, so a plain catalog refresh can't see them — the
 *  addon list must be re-pulled. Fire the safe add-only re-list a few times over
 *  ~40s (bracketing the ~15-30s publish): once the server list contains the new
 *  row urls they are installed, the installed-addons flow re-emits, and Home's
 *  catalog pipeline loads them in place — no app restart, no wait on the 15-min
 *  resume TTL. Each tick also emits the manual catalog refresh. No-ops
 *  harmlessly if the user has left Home (the install still runs app-wide). */
private fun HomeViewModel.refreshTasteRowsUntilPublished() {
    viewModelScope.launch {
        longArrayOf(0L, 8000L, 16000L, 24000L, 32000L, 40000L).forEach { delayMs ->
            if (delayMs > 0) delay(delayMs)
            startupSyncService.requestAddonRelistNow()
        }
    }
}

/** Rebuilds [TastePick]s from the local liked map for the "Done" full-replace push. */
private suspend fun HomeViewModel.buildLikedPicks(profileId: Int): List<TastePick> =
    likePreferences.allLiked(profileId).mapNotNull { (key, name) ->
        val (typeWire, tmdbId) = LikePreferences.splitKey(key) ?: return@mapNotNull null
        val type = TastePickType.fromWire(typeWire) ?: return@mapNotNull null
        TastePick(pickType = type, tmdbId = tmdbId, name = name)
    }
