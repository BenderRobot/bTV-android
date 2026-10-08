package com.btv.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newerReleaseIsAvailable() {
        assertEquals(
            UpdateStatus.Available("2026.10.09-0800"),
            compareVersions("2026.10.08-1901", "2026.10.09-0800")
        )
    }

    @Test
    fun sameOrOlderReleaseIsUpToDate() {
        assertEquals(UpdateStatus.UpToDate, compareVersions("2026.10.08-1901", "2026.10.08-1901"))
        assertEquals(UpdateStatus.UpToDate, compareVersions("2026.10.08-1901", "2026.09.30-2359"))
    }

    @Test
    fun unversionedBuildIsOlderAndOddTagIsUnknown() {
        assertEquals(UpdateStatus.Available("2026.10.08-1901"), compareVersions("dev", "2026.10.08-1901"))
        assertEquals(UpdateStatus.Unknown, compareVersions("2026.10.08-1901", "latest"))
    }

    @Test
    fun displayVersion_readsAsDate() {
        assertEquals("08/10/2026 19:01", displayVersion("2026.10.08-1901"))
        assertEquals("dev", displayVersion("dev"))
    }
}
