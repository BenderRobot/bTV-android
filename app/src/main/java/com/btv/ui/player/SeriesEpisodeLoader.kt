package com.btv.ui.player

import com.btv.data.model.AuthSession
import com.btv.data.repository.AuthRepository
import com.btv.util.resolveExtension

/** All of a series' episodes, grouped by season number, as playable zap items. */
data class SeriesEpisodesBySeason(val episodesBySeason: Map<Int, List<ZapItem>>)

/**
 * Fetches and maps a series' full episode list (js/data.js loadSeriesInfo).
 * Used to rebuild the player's zap list from the authoritative season data
 * regardless of entry point (js/browse.js buildSeasonZapList: a series
 * episode opened from "Continuer à regarder"/"Consulté récemment" mixes
 * unrelated shows in that list, so it must never be used as the player's
 * zap list directly), and to find the next season after the current one.
 */
suspend fun loadSeriesEpisodesBySeason(
    authRepository: AuthRepository,
    session: AuthSession,
    seriesId: String
): SeriesEpisodesBySeason? {
    val info = authRepository.getSeriesInfo(session, seriesId).getOrNull() ?: return null
    val episodesBySeason = info.episodes.mapNotNull { (seasonKey, episodesRaw) ->
        val seasonNum = seasonKey.toIntOrNull() ?: return@mapNotNull null
        val episodes = episodesRaw.map { ep ->
            val ext = resolveExtension(ep.containerExtension, session.userInfo.allowedOutputFormats, "mp4")
            ZapItem(
                id = ep.id,
                name = ep.title?.takeIf { it.isNotBlank() } ?: "Épisode ${ep.episodeNum}",
                posterUrl = ep.info?.movieImage,
                streamUrl = authRepository.buildStreamUrl(session, ep.id, "series", ext)
            )
        }
        seasonNum to episodes
    }.toMap()
    return SeriesEpisodesBySeason(episodesBySeason)
}

/** The season after [currentSeasonNum] in sorted numeric order (not simply +1 - specials like season 0 make season numbers non-contiguous), or null if there is none. */
fun SeriesEpisodesBySeason.nextSeasonAfter(currentSeasonNum: Int): NextSeasonResult? {
    val seasonNums = episodesBySeason.keys.sorted()
    val currentIdx = seasonNums.indexOf(currentSeasonNum)
    if (currentIdx == -1 || currentIdx >= seasonNums.size - 1) return null
    val nextSeasonNum = seasonNums[currentIdx + 1]
    val episodes = episodesBySeason[nextSeasonNum]
    if (episodes.isNullOrEmpty()) return null
    return NextSeasonResult(nextSeasonNum, episodes)
}

/** Result of [SeriesEpisodesBySeason.nextSeasonAfter]: the season that was found, and its episodes as a playable zap list. */
data class NextSeasonResult(val seasonNum: Int, val episodes: List<ZapItem>)
