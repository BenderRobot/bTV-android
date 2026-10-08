package com.btv.ui.player

import com.btv.data.cache.CatalogCache
import com.btv.data.model.AuthSession
import com.btv.data.model.TmdbCastPerson
import com.btv.data.repository.AuthRepository
import com.btv.data.repository.TmdbRepository

/** Text that says something: some panels send invisible characters for an empty field. */
private fun String?.meaningful(): String? = this?.trim()?.takeIf { text -> text.any { it.isLetterOrDigit() } }

private fun String?.meaningfulRating(): String? = meaningful()?.takeIf { it.toFloatOrNull() != 0f }

/** Cast photos, same source and cache policy as the catalogue's synopsis band. */
private val tmdbRepository = TmdbRepository()

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
    val posterUrl: String? = null,
    /** Faces of the cast when TMDB knows the title; else the [cast] text alone. */
    val castPhotos: List<TmdbCastPerson> = emptyList()
)

/**
 * Film: get_vod_info (through the Browse cache, often already loaded).
 * Episode: get_series_info - the series' details plus the episode's own synopsis.
 * [title] (film or series name) finds the cast photos.
 */
suspend fun loadPlayerInfo(
    authRepository: AuthRepository,
    session: AuthSession,
    type: String,
    streamId: String,
    seriesId: String?,
    title: String
): PlayerInfo? {
    var year: String? = null
    val info = when (type) {
        "VOD" -> CatalogCache.loadVodInfo(streamId, CatalogCache.generationToken()) {
            authRepository.getVodInfo(session, streamId)
        }.getOrNull()?.let { info ->
            year = com.btv.util.extractYear(info.releaseDate.ifBlank { info.releaseDateAlt })
            PlayerInfo(
                title = "",
                meta = listOfNotNull(
                    year,
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
                year = details?.releaseDate?.let { com.btv.util.extractYear(it) }
                PlayerInfo(
                    title = "",
                    meta = listOfNotNull(
                        year,
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
    } ?: return null
    // Only once the server named actors: never faces for a cast it did not give.
    if (info.cast == null) return info
    val photos = try {
        tmdbRepository.fetchCast(if (type == "SERIES") "tv" else "movie", title, year).orEmpty()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
    return info.copy(castPhotos = photos)
}
