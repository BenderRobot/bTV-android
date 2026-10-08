package com.btv.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesInfoParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun readsTheSeriesDetails() {
        val response = json.decodeFromString<XtreamSeriesInfoResponse>(
            """{"seasons":[],"episodes":{},"info":{"name":"Dark","plot":"Un enfant disparaît.","cast":"Louis Hofmann",
               "director":"Baran bo Odar","genre":"Drame","releaseDate":"2017-12-01","rating":8.7,
               "episode_run_time":"55","backdrop_path":["https://img/backdrop.jpg"]}}"""
        )
        val details = response.details()!!
        assertEquals("Un enfant disparaît.", details.plot)
        assertEquals("8.7", details.rating)
        assertEquals("2017-12-01", details.releaseDate)
        assertEquals("55", details.episodeRunTime)
        assertEquals("https://img/backdrop.jpg", details.backdropUrl)
    }

    @Test fun anEmptyArrayInsteadOfDetailsKeepsTheEpisodes() {
        val response = json.decodeFromString<XtreamSeriesInfoResponse>(
            """{"seasons":[],"episodes":{"1":[]},"info":[]}"""
        )
        assertNull(response.details())
        assertEquals(setOf("1"), response.episodes.keys)
    }
}
