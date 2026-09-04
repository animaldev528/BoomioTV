package com.nuvio.tv.domain.model

data class UserProfile(
    val id: Int,
    val name: String,
    val avatarColorHex: String,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false,
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    val profileBackgroundId: String? = null,
    val profileBackgroundUrl: String? = null,
    // Like-bootstrap flags. Server-owned, pulled with the profile from
    // sync_pull_profiles and persisted locally like every other field:
    //   taste_enabled   this profile participates in like-bootstrap (long-press
    //                   Like + the first-run hint are offered);
    //   is_kids         kids profile — Like/hint are hard-hidden regardless;
    //   taste_completed flipped true only by "Done for now" (sync_push_taste_picks
    //                   on the backend), after which the profile gets a
    //                   personalized home built from its likes;
    //   curated_enabled legacy operator-opt-in marker (kept for back-compat; no
    //                   longer drives any picker gate).
    val curatedEnabled: Boolean = false,
    val tasteCompleted: Boolean = false,
    val tasteEnabled: Boolean = false,
    val isKids: Boolean = false
) {
    val isPrimary: Boolean get() = id == 1

    /** A profile the like-bootstrap UI applies to: server opted-in and not kids. */
    val likeBootstrapEnabled: Boolean get() = tasteEnabled && !isKids

    /** First-run hint is up until the profile hits "Done for now". */
    val needsTasteHint: Boolean get() = likeBootstrapEnabled && !tasteCompleted
}
