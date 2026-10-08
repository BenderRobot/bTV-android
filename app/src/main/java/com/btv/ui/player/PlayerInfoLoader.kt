package com.btv.ui.player

import com.btv.data.cache.CatalogCache
import com.btv.data.model.AuthSession
import com.btv.data.repository.AuthRepository

/** What the "Infos" panel of the player shows - only what the server says, nothing made up. */
data class PlayerInfo(
    val title: String,
    /** Episode line ("S02E05 · Titre"), null for a film. */
    val subtitle: String? = null,
    val meta: List<String> = emptyList(),
    val rating: String? = null,
    val plot: String? = null,
    val director: String? = null,
    val cast: String? = null,
    val posterUrl: String? = null
)

/**
 * Film: get_vod_info (through the Browse cache, often already loaded).
 * Episode: get_series_info - the series' details plus the episode's own synopsis.
 */
suspend fun loadPlayerInfo(
    authRepository: AuthRepository,
    session: AuthSession,
    type: String,
    streamId: String,
    seriesId: String?
): PlayerInfo? = when (type) {
    "VOD" -> CatalogCache.loadVodInfo(streamId, CatalogCache.generationToken()) {
        authRepository.getVodInfo(session, streamId)
    }.getOrNull()?.let { info ->
        PlayerInfo(
            title = "",
            meta = listOfNotNull(
                com.btv.util.extractYear(info.releaseDate.ifBlank { info.releaseDateAlt }),
                (info.duration.takeIf { it.isNotBlank() } ?: info.durationSecs.takeIf { it > 0 }?.let { "${it / 60} min" })
                    ?.let { com.btv.util.displayDuration(it) },
                info.genre, info.country
            ).filter { it.isNotBlank() },
            rating = info.rating.takeIf { it.isNotBlank() && it.toFloatOrNull() != 0f },
            plot = info.plot.ifBlank { info.description }.takeIf { it.isNotBlank() },
            director = info.director.takeIf { it.isNotBlank() },
            cast = info.cast.takeIf { it.isNotBlank() }
        )
    }
    "SERIES" -> seriesId?.let { id ->
        authRepository.getSeriesInfo(session, id).getOrNull()?.let { response ->
            val details = response.details()
            val episode = response.episodes.values.flatten().firstOrNull { it.id == streamId }
            PlayerInfo(
                title = "",
                meta = listOfNotNull(
                    details?.releaseDate?.let { com.btv.util.extractYear(it) },
                    episode?.info?.duration?.takeIf { it.isNotBlank() }?.let { com.btv.util.displayDuration(it) },
                    details?.genre
                ).filter { it.isNotBlank() },
                rating = details?.rating?.takeIf { it.isNotBlank() && it.toFloatOrNull() != 0f },
                // The episode's own synopsis first, else the series'.
                plot = episode?.info?.plot?.takeIf { it.isNotBlank() } ?: details?.plot?.takeIf { it.isNotBlank() },
                director = details?.director?.takeIf { it.isNotBlank() },
                cast = details?.cast?.takeIf { it.isNotBlank() }
            )
        }
    }
    else -> null
}
