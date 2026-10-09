package com.btv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InfoTextTest {

    @Test
    fun placeholdersAndEmptyValuesAreDropped() {
        listOf(null, "", "   ", "N/A", "n/a", "null", "Unknown", " - ", "​").forEach { raw ->
            assertNull("\"$raw\" should be dropped", infoText(raw))
        }
    }

    @Test
    fun realValuesAreKeptTrimmed() {
        assertEquals("Chuck Lorre", infoText("  Chuck Lorre "))
        assertEquals("Kevin Sussman, Lauren Lapkus", infoText("Kevin Sussman, Lauren Lapkus"))
        assertEquals("7.5", infoText("7.5"))
    }
}
