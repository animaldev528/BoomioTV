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
    val isKids: Boolean = false,
    // Kids rating ceilings, chosen by the parent in BSM's Kids Content Manager
    // and pulled with the profile (null = not chosen; the kids filter then
    // keeps its previous PG-13 default). Movies and TV are separate axes: a
    // five-year-old is usually fine with TV-PG and not with a PG-13 film.
    val kidsRatingMovie: String? = null,
    val kidsRatingTv: String? = null
) {
    val isPrimary: Boolean get() = id == 1

    /**
     * A profile the like-bootstrap UI applies to: server opted-in.
     *
     * Kids are included. Their Home is built server-side over the parent's BSM
     * approval set (kidgate), so every title they can like — and so every title
     * that comes back as a shelf — is already approved. The old `&& !isKids`
     * guard predates kids having a Home at all.
     */
    val likeBootstrapEnabled: Boolean get() = tasteEnabled

    /** First-run hint is up until the profile hits "Done for now". */
    val needsTasteHint: Boolean get() = likeBootstrapEnabled && !tasteCompleted
}
