package com.btv.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentColorTest {
    @Test fun storedKeysAreUniqueAndRoundTrip() {
        assertEquals(AccentColor.entries.size, AccentColor.entries.map { it.key }.distinct().size)
        AccentColor.entries.forEach { assertEquals(it, AccentColor.fromKey(it.key)) }
    }

    @Test fun unknownOrMissingKeyFallsBackToTheIconGreen() {
        assertEquals(AccentColor.GREEN, AccentColor.fromKey(null))
        assertEquals(AccentColor.GREEN, AccentColor.fromKey("chartreuse"))
    }

    @Test fun accentFollowsTheCurrentChoice() {
        BtvAccent.current = AccentColor.BLUE
        assertEquals(AccentColor.BLUE.main, BtvGreen)
        BtvAccent.current = AccentColor.GREEN
        assertEquals(AccentColor.GREEN.bright, BtvGreenBright)
    }
}
