package com.btv.ui.browse

import com.btv.data.model.XtreamChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ReplayGroupsTest {
    private fun channel(id: String, name: String, category: String? = null, days: Int? = null) =
        XtreamChannel(streamId = id, name = name, categoryId = category, tvArchive = 1, tvArchiveDuration = days)

    @Test
    fun `qualities fold into one row played in full hd, rows ordered by category then name`() {
        val groups = groupReplayChannels(
            listOf(
                channel("1", "|FR| TF1 HD", "10", 3),
                channel("2", "|FR| TF1 FHD", "10", 7),
                channel("3", "|FR| CANAL+ SPORT FHD", "20"),
                channel("4", "|FR| ARTE HD", "10"),
                channel("5", "|BE| TF1 HD", null)
            ),
            mapOf("10" to "|FR| GENERALISTES", "20" to "|FR| SPORT")
        )
        assertEquals(listOf("|FR| ARTE", "|FR| TF1", "|FR| CANAL+ SPORT", "|BE| TF1"), groups.map { it.displayName })
        val tf1 = groups[1]
        assertEquals("2", tf1.representative.streamId)
        assertEquals(listOf("2", "1"), tf1.guideSources.map { it.streamId })
        assertEquals(7, tf1.archiveDays)
        assertEquals("7 j · |FR| GENERALISTES", tf1.sidebarSubtitle())
        assertEquals("1 j", groups[3].sidebarSubtitle())
    }

    @Test
    fun `day tabs are most recent first, with friendly labels`() {
        val zone = ZoneOffset.UTC
        val day = 24 * 3600_000L
        val base = LocalDate.of(2026, 10, 7).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = replayDays(listOf(base - day + 3600_000L, base + 7200_000L, base - 2 * day, base + 3600_000L), zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 5)), days)
        val today = LocalDate.of(2026, 10, 7)
        assertEquals("Aujourd'hui", replayDayLabel(today, today))
        assertEquals("Hier", replayDayLabel(today.minusDays(1), today))
        assertEquals("Lun. 5 oct.", replayDayLabel(LocalDate.of(2026, 10, 5), today))
    }

    @Test
    fun `panel offset is learned from a guide entry`() {
        // 2026-10-07 20:00:00 panel time = 18:00:00 UTC: the panel is UTC+2.
        assertEquals(2 * 3600_000L, panelOffsetMs("2026-10-07 20:00:00", "1791396000"))
        assertNull(panelOffsetMs(null, "1791396000"))
        assertNull(panelOffsetMs("2026-10-07 20:00:00", ""))
    }

    @Test
    fun `hourly slots cover ended hours of the archive window, in panel time`() {
        val hour = 3600_000L
        val now = 1791396000_000L + 30 * 60_000L // 18:30 UTC
        val slots = hourlySlots(now, archiveDays = 1, offsetMs = 2 * hour)
        assertEquals(23, slots.size) // 19:00 the day before (window starts 18:30) to 17:00
        assertEquals(1791396000_000L - hour, slots.first().startMs) // 17:00-18:00 UTC, the last whole hour
        assertEquals("2026-10-07 19:00:00", slots.first().panelStart)
        assertEquals(now - 24 * hour + 30 * 60_000L, slots.last().startMs)
    }
}
