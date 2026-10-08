package com.btv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLanguageTest {
    @Test fun sameLanguageUnderAnotherLabel() {
        assertTrue(labelMatchesLanguage("Français", "fre"))
        assertTrue(labelMatchesLanguage("FRE", "fr"))
        assertTrue(labelMatchesLanguage("French (AAC 5.1)", "fra"))
        assertTrue(labelMatchesLanguage("FR", "fr"))
        assertTrue(labelMatchesLanguage("English", "eng"))
        assertTrue(labelMatchesLanguage("ENG", "en"))
    }

    @Test fun otherLanguagesAndUnknownTracksDoNotMatch() {
        assertFalse(labelMatchesLanguage("Français", "eng"))
        assertFalse(labelMatchesLanguage("ENG", "fr"))
        assertFalse(labelMatchesLanguage("Désactivés", null))
        assertFalse(labelMatchesLanguage("Piste 1", "und"))
        assertFalse(labelMatchesLanguage("Français", ""))
    }
}
