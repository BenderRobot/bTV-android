package com.btv.ui.player

import com.btv.data.cache.CatalogCache
import com.btv.data.model.AuthSession
import com.btv.data.repository.AuthRepository

/** Text that says something: some panels send invisible characters for an empty field. */
private fun String?.meaningful(): String? = this?.trim()?.takeIf { text -> text.any { it.isLetterOrDigit() } }

private fun String?.meaningfulRating(): String? = meaningful()?.takeIf { it.toFloatOrNull() != 0f }

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
            ).mapNotNull { it.meaningful() },
            rating = info.rating.meaningfulRating(),
            plot = info.plot.meaningful() ?: info.description.meaningful(),
            director = info.director.meaningful(),
            cast = info.cast.meaningful()
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
                ).mapNotNull { it.meaningful() },
                rating = details?.rating.meaningfulRating(),
                // The episode's own synopsis first, else the series'.
                plot = episode?.info?.plot.meaningful() ?: details?.plot.meaningful(),
                director = details?.director.meaningful(),
                cast = details?.cast.meaningful()
            )
        }
    }
    else -> null
}
