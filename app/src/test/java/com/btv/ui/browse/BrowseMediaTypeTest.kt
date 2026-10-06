package com.btv.ui.browse

import org.junit.Assert.*
import org.junit.Test

class BrowseMediaTypeTest {
    @Test fun favoritesPreserveTheSelectedMediaType() {
        for (type in listOf(ContentType.VOD, ContentType.LIVE, ContentType.SERIES)) {
            val state = BrowseUiState(contentType = ContentType.FAVORITES,
                categories = listOf(BrowseCategory("selected", "Favoris", type = type)), selectedCategoryId = "selected")
            assertEquals(type, state.mediaType)
        }
    }

    @Test fun normalSectionDoesNotUseCategoryDefaultType() {
        assertEquals(ContentType.LIVE, BrowseUiState(contentType = ContentType.LIVE,
            categories = listOf(BrowseCategory("live", "Direct")), selectedCategoryId = "live").mediaType)
    }

    @Test fun onlySeriesRootsAreFavoritableInSeries() {
        assertTrue(ContentItem("series", "Série", contentKind = ContentKind.SERIES).canFavorite(ContentType.SERIES))
        assertFalse(ContentItem("season", "Saison", contentKind = ContentKind.SEASON).canFavorite(ContentType.SERIES))
        assertFalse(ContentItem("episode", "Épisode", seriesId = "series").canFavorite(ContentType.SERIES))
        assertFalse(ContentItem("replay", "Archive").canFavorite(ContentType.REPLAY))
    }

    @Test fun onlyMoviesAndEpisodesCanBeMarkedWatched() {
        assertTrue(ContentItem("movie", "Film", streamUrl = "movie.mp4").canMarkWatched(ContentType.VOD))
        assertTrue(ContentItem("episode", "Episode", streamUrl = "episode.mkv", seriesId = "show")
            .canMarkWatched(ContentType.SERIES))
        assertFalse(ContentItem("show", "Serie", contentKind = ContentKind.SERIES)
            .canMarkWatched(ContentType.SERIES))
        assertFalse(ContentItem("live", "Direct", streamUrl = "live.ts").canMarkWatched(ContentType.LIVE))
    }
}
