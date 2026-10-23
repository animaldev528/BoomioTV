package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape

fun MetaPreviewDto.toDomainOrNull(catalogType: String, sourceAddonBaseUrl: String? = null): MetaPreview? {
    val resolvedId = id?.takeIf { it.isNotBlank() } ?: return null
    val resolvedName = name?.takeIf { it.isNotBlank() } ?: return null
    val resolvedType = type?.takeIf { it.isNotBlank() } ?: catalogType
    return MetaPreview(
        id = resolvedId,
        type = ContentType.fromString(resolvedType),
        rawType = resolvedType,
        name = resolvedName,
        poster = poster,
        posterShape = PosterShape.fromString(posterShape),
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        // The kids profile's rating gate reads this and hides a tile it cannot
        // rate, so an addon that omits it costs the profile the whole row.
        ageRating = ageRating?.trim()?.takeIf { it.isNotBlank() },
        imdbRating = imdbRating?.toFloatOrNull(),
        genres = genres ?: emptyList(),
        runtime = runtime,
        status = status?.trim()?.takeIf { it.isNotBlank() },
        released = released,
        country = country,
        imdbId = imdbId,
        slug = slug,
        landscapePoster = landscapePoster,
        rawPosterUrl = rawPosterUrl,
        director = coerceStringList(director),
        writer = coerceStringList(writer).ifEmpty { coerceStringList(writers) },
        links = links?.mapNotNull { it.toDomain() } ?: emptyList(),
        behaviorHints = mapBehaviorHints(behaviorHints),
        trailers = mapTrailers(trailers, trailerStreams),
        trailerYtIds = collectTrailerYtIds(trailers, trailerStreams),
        sourceAddonBaseUrl = sourceAddonBaseUrl
    )
}
