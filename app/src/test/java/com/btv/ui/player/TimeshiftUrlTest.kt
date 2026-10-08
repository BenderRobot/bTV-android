package com.btv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeshiftUrlTest {
    private val pathUrl = "http://panel.example:8080/timeshift/user/p@ss/90/2026-10-07:23-30/1234.ts"

    @Test
    fun `path url round-trips`() {
        val url = TimeshiftUrl.parse(pathUrl)!!
        assertEquals("1234", url.streamId)
        assertEquals("p@ss", url.password)
        assertEquals(90, url.durationMinutes)
        assertEquals(TimeshiftFormat.PATH, url.format)
        assertEquals(pathUrl, url.build())
    }

    @Test
    fun `offset moves the start and shortens the duration, across midnight`() {
        val url = TimeshiftUrl.parse(pathUrl)!!
        assertEquals(
            "http://panel.example:8080/timeshift/user/p@ss/45/2026-10-08:00-15/1234.ts",
            url.build(offsetMinutes = 45)
        )
    }

    @Test
    fun `offset never goes past the last minute`() {
        val url = TimeshiftUrl.parse(pathUrl)!!
        assertEquals(
            "http://panel.example:8080/timeshift/user/p@ss/1/2026-10-08:00-59/1234.ts",
            url.build(offsetMinutes = 500)
        )
    }

    @Test
    fun `another stream and the php format`() {
        val url = TimeshiftUrl.parse(pathUrl)!!
        val php = url.build(offsetMinutes = 10, streamId = "99", format = TimeshiftFormat.PHP)
        assertEquals(
            "http://panel.example:8080/streaming/timeshift.php?username=user&password=p%40ss&stream=99&start=2026-10-07:23-40&duration=80",
            php
        )
        val reparsed = TimeshiftUrl.parse(php)!!
        assertEquals(TimeshiftFormat.PHP, reparsed.format)
        assertEquals("99", reparsed.streamId)
        assertEquals(url.accountKey, reparsed.accountKey)
    }

    @Test
    fun `non archive urls are rejected`() {
        assertNull(TimeshiftUrl.parse("http://panel/live/u/p/1.ts"))
        assertNull(TimeshiftUrl.parse("http://panel/movie/u/p/1.mp4"))
        assertNull(TimeshiftUrl.parse("http://panel/timeshift/u/p/x/2026-10-07:23-30/1.ts"))
        assertTrue(isTimeshiftPath("/timeshift/u/p/90/2026-10-07:23-30/1.ts"))
        assertTrue(isTimeshiftPath("/streaming/timeshift.php"))
        assertFalse(isTimeshiftPath("/live/u/p/1.ts"))
    }
}
