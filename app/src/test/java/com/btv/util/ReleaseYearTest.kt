package com.btv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseYearTest {
    @Test fun readsTheYearWhateverTheDateFormat() {
        assertEquals("2025", extractYear("2025-10-01"))
        assertEquals("2025", extractYear("01 Oct 2025"))
        assertEquals("2023", extractYear("25 Sep 2023"))
        assertEquals("2019", extractYear("01/10/2019"))
        assertEquals("1999", extractYear("1999"))
    }

    @Test fun noYearGivesNull() {
        assertNull(extractYear(null))
        assertNull(extractYear(""))
        assertNull(extractYear("01 Oct"))
        assertNull(extractYear("123456"))
    }
}
