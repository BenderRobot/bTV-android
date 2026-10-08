package com.btv.ui.home

import com.btv.data.db.entities.HistoryEntity
import com.btv.data.db.entities.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingTest {
    private fun progress(id: String, type: String, at: Long, percent: Float = 40f, durationMs: Long = 3_600_000L) =
        PlaybackProgressEntity("acc", id, type, (durationMs * percent / 100).toLong(), durationMs, lastProgressedAt = at)

    private fun history(id: String, type: String, name: String, at: Long = 0L, category: String = "Cat", seriesId: String? = null) =
        HistoryEntity(accountKey = "acc", streamId = id, type = type, name = name, categoryId = "c", categoryName = category,
            posterUrl = "poster-$id", seriesId = seriesId, lastViewedAt = at)

    private fun build(progress: Map<String, List<PlaybackProgressEntity>>, history: Map<String, List<HistoryEntity>>) =
        buildContinueItems(
            ContinueSources(progress, history),
            streamUrl = { type, id, _ -> "url/$type/$id" },
            replayUrl = { channel, startMs, durationMs -> "replay/$channel/$startMs/$durationMs" }
        )

    @Test fun mixesEverythingMostRecentFirst() {
        val items = build(
            progress = mapOf(
                "VOD" to listOf(progress("m1", "VOD", at = 100)),
                "REPLAY" to listOf(progress("42_1700000000", "REPLAY", at = 300))
            ),
            history = mapOf(
                "VOD" to listOf(history("m1", "VOD", "Film 1")),
                "REPLAY" to listOf(history("42_1700000000", "REPLAY", "Le journal", category = "TF1")),
                "LIVE" to listOf(history("7", "LIVE", "France 2", at = 200))
            )
        )
        assertEquals(listOf("REPLAY:42_1700000000", "LIVE:7", "VOD:m1"), items.map { it.key })
        assertEquals(0.4f, items.last().progress!!, 0.001f)
        assertNull(items[1].progress)
        assertEquals("replay/42/1700000000000/3600000", items.first().request.streamUrl)
        assertEquals("REPLAY", items.first().request.progressType)
    }

    @Test fun oneCardPerSeriesWithItsLatestEpisode() {
        val items = build(
            progress = mapOf("SERIES" to listOf(progress("e1", "SERIES", at = 10), progress("e2", "SERIES", at = 20))),
            history = mapOf("SERIES" to listOf(
                history("e1", "SERIES", "S01E01", category = "Dark", seriesId = "s"),
                history("e2", "SERIES", "S01E02", category = "Dark", seriesId = "s")
            ))
        )
        assertEquals(1, items.size)
        assertEquals("Dark", items.single().title)
        assertEquals("S01E02", items.single().subtitle)
        assertEquals("s", items.single().request.seriesId)
    }

    @Test fun seriesWatchedLastComesFirstEvenWhenItsEpisodeIsFinished() {
        // South Park: e1 started long ago, e2 watched to the end just now (no progress row left).
        val items = build(
            progress = mapOf(
                "VOD" to listOf(progress("m1", "VOD", at = 500)),
                "SERIES" to listOf(progress("e1", "SERIES", at = 10))
            ),
            history = mapOf(
                "VOD" to listOf(history("m1", "VOD", "Coyote", at = 500)),
                "SERIES" to listOf(
                    history("e1", "SERIES", "S04E05", at = 10, category = "South Park", seriesId = "sp"),
                    history("e2", "SERIES", "S04E06", at = 900, category = "South Park", seriesId = "sp")
                )
            )
        )
        assertEquals(listOf("SERIES:e1", "VOD:m1"), items.map { it.key })
    }

    @Test fun seriesNameIsNeverTheListItWasStartedFrom() {
        val items = build(
            progress = mapOf("SERIES" to listOf(progress("e2", "SERIES", at = 20))),
            history = mapOf("SERIES" to listOf(
                history("e1", "SERIES", "S02E00", at = 5, category = "Futurama", seriesId = "f"),
                history("e2", "SERIES", "S02E01", at = 20, category = "Continuer à regarder", seriesId = "f")
            ))
        )
        assertEquals("Futurama", items.single().title)
    }

    @Test fun nothingWithoutHistoryAndNoAdultChannels() {
        val items = build(
            progress = mapOf("VOD" to listOf(progress("orphan", "VOD", at = 1))),
            history = mapOf("LIVE" to listOf(
                history("1", "LIVE", "XXX Channel", at = 5),
                history("2", "LIVE", "Chaîne", category = "|FR| ADULTES", at = 4),
                history("3", "LIVE", "Arte", at = 3)
            ))
        )
        assertEquals(listOf("LIVE:3"), items.map { it.key })
    }

    @Test fun liveKeepsTheLastFewAndZapsBetweenThem() {
        val live = (1..6).map { history("$it", "LIVE", "Ch $it", at = it.toLong()) }
        val items = build(progress = emptyMap(), history = mapOf("LIVE" to live))
        assertEquals(CONTINUE_MAX_LIVE, items.size)
        assertEquals("LIVE:6", items.first().key)
        assertTrue(items.all { it.request.zapList.size == CONTINUE_MAX_LIVE })
    }
}
