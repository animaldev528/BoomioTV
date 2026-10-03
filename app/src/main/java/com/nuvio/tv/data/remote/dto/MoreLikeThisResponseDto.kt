package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response of the rec-addon more-like-this endpoint:
 *   {base}/more-like-this/{movie|series}/{ttId}.json?skip=N
 *
 * `lists` names the hidden curated facet lists the pressed title sits on (tightest
 * first — collection before studio before genre), and `metas` carries the approved
 * co-members of those lists, same StremioMetaPreview shape as the walls.
 */
@JsonClass(generateAdapter = true)
data class MoreLikeThisResponseDto(
    @Json(name = "lists") val lists: List<MoreLikeThisListDto>? = emptyList(),
    @Json(name = "metas") val metas: List<MetaPreviewDto?>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class MoreLikeThisListDto(
    @Json(name = "key") val key: String? = null,
    @Json(name = "kind") val kind: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "count") val count: Int? = null
)
