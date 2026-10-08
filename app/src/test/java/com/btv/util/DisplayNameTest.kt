package com.btv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNameTest {
    @Test fun languagePrefixAndReleaseTagsBecomeChips() {
        assertEquals(DisplayName("\"Ippon\" Again!", listOf("FR", "VOST")), displayName("|FR| \"Ippon\" Again! (VOST)"))
        assertEquals(DisplayName("Carrie", listOf("FR", "MULTI")), displayName("|FR| Carrie (MULTI)"))
        assertEquals(DisplayName("Film", listOf("FR", "MULTI", "4K")), displayName("[FR] Film (MULTI) (4K)"))
        assertEquals(DisplayName("Arte", listOf("FR")), displayName("FR: Arte"))
        assertEquals(DisplayName("Arte", listOf("FR")), displayName("FR| Arte"))
    }

    @Test fun channelQualitySuffix() {
        assertEquals(DisplayName("BFM BUSINESS", listOf("FR", "FHD")), displayName("|FR| BFM BUSINESS FHD"))
        assertEquals(DisplayName("CNBC", listOf("US")), displayName("|US| CNBC"))
    }

    @Test fun keepsWhatIsNotATag() {
        assertEquals(DisplayName("CBS 2 NEW YORK (WCBS)", listOf("US")), displayName("|US| CBS 2 NEW YORK (WCBS)"))
        assertEquals(DisplayName("Dune (2021)", emptyList<String>()), displayName("Dune (2021)"))
        assertEquals(DisplayName("Up: Le film", emptyList<String>()), displayName("Up: Le film"))
        // Nothing left after cleaning: the raw name stays.
        assertEquals("|FR|", displayName("|FR|").title)
        assertEquals("HD", displayName("HD").title)
    }

    @Test fun categories() {
        assertEquals("ACTION · AVENTURE", displayCategory("|FR| ACTION | AVENTURE"))
        assertEquals("USA · TV", displayCategory("USA | TV"))
        assertEquals("FRANCE FHD · TV", displayCategory("FRANCE FHD | TV"))
        assertEquals("Tout afficher", displayCategory("Tout afficher"))
    }

    @Test fun durations() {
        assertEquals("2 h 36", displayDuration("02:36:21"))
        assertEquals("45 min", displayDuration("00:45:09"))
        assertEquals("1 h", displayDuration("01:00:10"))
        assertEquals("1 h 35", displayDuration("95:00"))
        assertEquals("1 h 35", displayDuration("95 min"))
        assertEquals("Épisode spécial", displayDuration("Épisode spécial"))
        assertEquals(null, displayDuration(""))
        assertEquals(null, displayDuration("00:00:10"))
    }

    @Test fun languageOfATitle() {
        assertEquals("IT", displayLanguage("|IT| Murdaugh Murders"))
        assertEquals("FR", displayLanguage("FR: Arte"))
        assertEquals(null, displayLanguage("South Park"))
        assertEquals(null, displayLanguage("|FR|"))
    }
}
