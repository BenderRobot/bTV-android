package com.btv.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newerReleaseIsAvailable() {
        assertEquals(UpdateStatus.Available("2.4.1"), compareVersions("2.4.0", "2.4.1"))
        assertEquals(UpdateStatus.Available("3.0.0"), compareVersions("2.9.9", "3.0.0"))
    }

    @Test
    fun numbersCompareAsNumbersNotText() {
        assertEquals(UpdateStatus.Available("2.10.0"), compareVersions("2.9.0", "2.10.0"))
        assertEquals(UpdateStatus.UpToDate, compareVersions("2.10.0", "2.9.0"))
    }

    @Test
    fun sameOrOlderReleaseIsUpToDate() {
        assertEquals(UpdateStatus.UpToDate, compareVersions("2.4.0", "2.4.0"))
        assertEquals(UpdateStatus.UpToDate, compareVersions("2.4.0", "2.3.5"))
    }

    @Test
    fun datedOrUnversionedBuildIsOlderAndOddTagIsUnknown() {
        assertEquals(UpdateStatus.Available("2.4.0"), compareVersions("2026.10.08-1901", "2.4.0"))
        assertEquals(UpdateStatus.Available("2.4.0"), compareVersions("dev", "2.4.0"))
        assertEquals(UpdateStatus.Unknown, compareVersions("2.4.0", "latest"))
        assertEquals(UpdateStatus.Unknown, compareVersions("2.4.0", "2026.10.08-1901"))
    }

    @Test
    fun displayVersion_readsAsVersionOrDate() {
        assertEquals("v2.4.0", displayVersion("2.4.0"))
        assertEquals("08/10/2026 19:01", displayVersion("2026.10.08-1901"))
        assertEquals("dev", displayVersion("dev"))
    }
}
