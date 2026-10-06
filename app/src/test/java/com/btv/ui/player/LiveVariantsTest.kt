package com.btv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVariantsTest {
    private fun item(id: String, name: String) = ZapItem(id, name, null, "http://panel/live/u/p/$id.ts")

    private val canalFamily = listOf(
        item("1", "|FR| CANAL+ FHD"),
        item("2", "|FR| CANAL+ HD"),
        item("3", "|FR| CANAL+ SD"),
        item("4", "|FR| CANAL+ UHD (DD+ 5.1)"),
        item("5", "|FR| CANAL+ UHD HDR (DD+ 5.1)"),
        item("6", "|FR| CANAL+ BOX OFFICE FHD"),
        item("7", "|FR| CANAL+ SPORT 360 HD"),
        item("8", "|BE| CANAL+ HD")
    )

    @Test fun keyIgnoresQualityAndAudioTagsButKeepsChannelAndLanguage() {
        assertEquals("FR CANAL+", liveChannelKey("|FR| CANAL+ UHD HDR (DD+ 5.1)"))
        assertEquals("FR CANAL+", liveChannelKey("|FR| CANAL+ SD"))
        assertEquals("FR CANAL+ BOX OFFICE", liveChannelKey("|FR| CANAL+ BOX OFFICE FHD"))
        assertEquals("BE CANAL+", liveChannelKey("|BE| CANAL+ HD"))
    }

    @Test fun qualityTokensMatchWholeWordsOnly() {
        assertEquals(LiveQuality.UHD, liveQualityOf("|FR| CANAL+ UHD HDR"))
        assertEquals(LiveQuality.FHD, liveQualityOf("TF1 FULL HD"))
        assertEquals(LiveQuality.UNKNOWN, liveQualityOf("|FR| CHDTV"))
        assertTrue(isHdrVariant("CANAL+ UHD HDR (DD+ 5.1)"))
        assertFalse(isHdrVariant("CANAL+ UHD (DD+ 5.1)"))
    }

    @Test fun fallbackGoesDownInQualityFirstThenUpWithHdrLast() {
        val chain = buildLiveFallbackChain(canalFamily[0], canalFamily)
        assertEquals(listOf("1", "2", "3", "4", "5"), chain.map { it.id })
    }

    @Test fun sdChoiceClimbsToTheClosestQualityFirst() {
        val chain = buildLiveFallbackChain(canalFamily[2], canalFamily)
        assertEquals(listOf("3", "2", "1", "4", "5"), chain.map { it.id })
    }

    @Test fun channelWithoutSiblingsIsAlone() {
        val chain = buildLiveFallbackChain(canalFamily[6], canalFamily)
        assertEquals(listOf("7"), chain.map { it.id })
    }

    @Test fun displayNameDropsOnlyTheEncodingTags() {
        assertEquals("|FR| CANAL+", liveDisplayName("|FR| CANAL+ UHD HDR (DD+ 5.1)"))
        assertEquals("|FR| CANAL+ BOX OFFICE", liveDisplayName("|FR| CANAL+ BOX OFFICE FHD"))
    }

    @Test fun duplicateQualityTagsGetNumbered() {
        assertEquals(listOf("HD 1", "HD 2", "SD"), liveQualityLabels(listOf("A HD", "A 720p", "A SD")) { it })
    }

    @Test fun rememberedStreamUrlSwapsOnlyTheStreamId() {
        assertEquals("http://panel/live/u/p/99.ts", liveUrlForStreamId("http://panel/live/u/p/42.ts", "99"))
        assertEquals("http://panel/live/u/p/99.ts?t=1", liveUrlForStreamId("http://panel/live/u/p/42.ts?t=1", "99"))
    }

    @Test fun onlyXtreamLiveTsUrlsAreTreatedAsEndless() {
        assertTrue(isXtreamLiveTsPath("/live/user/pass/123.ts"))
        assertFalse(isXtreamLiveTsPath("/live/user/pass/123.m3u8"))
        assertFalse(isXtreamLiveTsPath("/movie/user/pass/123.mkv"))
        assertFalse(isXtreamLiveTsPath("/timeshift/user/pass/60/2026-10-06:16-00/123.ts"))
        assertFalse(isXtreamLiveTsPath(null))
    }
}
