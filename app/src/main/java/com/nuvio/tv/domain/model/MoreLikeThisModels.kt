package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * One hidden curated facet list the pressed title belongs to (collection / studio /
 * network / genre). Surfaced only as the wall's caption chips — never as rows.
 */
@Immutable
data class MoreLikeThisList(
    val key: String,
    val kind: String,
    val name: String,
    val count: Int
)

/**
 * One page of the More-like-this wall: the facet lists (re-sent on every page; the
 * client only needs them on page 0) plus the approved co-member previews for the
 * requested skip offset. [hasMore] is true while the server returns a full page.
 */
@Immutable
data class MoreLikeThisPage(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val itemType: String,
    val lists: List<MoreLikeThisList>,
    val items: List<MetaPreview>,
    val hasMore: Boolean
)
