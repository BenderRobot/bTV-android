package com.btv.ui.browse

import com.btv.data.store.LiveQualityChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelGroupsTest {
    private fun channel(id: String, name: String, badge: String? = null) =
        ContentItem(id = id, name = name, streamUrl = "http://panel/live/u/p/$id.ts", badge = badge)

    private val list = listOf(
        channel("1", "|FR| CANAL+ FHD"),
        channel("2", "|FR| CANAL+ HD"),
        channel("3", "|FR| CANAL+ SD", badge = "16:29–18:52  Nuremberg"),
        channel("4", "|FR| CANAL+ UHD (DD+ 5.1)"),
        channel("5", "|FR| CANAL+ UHD HDR (DD+ 5.1)"),
        channel("6", "|FR| TF1 HD")
    )

    @Test fun siblingsFoldIntoOneRowInListOrder() {
        val groups = groupLiveChannels(list, emptyMap(), emptySet())
        assertEquals(listOf("|FR| CANAL+", "|FR| TF1 HD"), groups.map { it.row.name })
        assertEquals(listOf("4", "5", "1", "2", "3"), groups[0].variants.map { it.id })
        assertEquals(listOf("UHD · DD+ 5.1", "UHD HDR · DD+ 5.1", "FHD", "HD", "SD"), groups[0].qualityLabels)
    }

    @Test fun okPlaysFullHdUnlessAQualityWasRemembered() {
        assertEquals("1", groupLiveChannels(list, emptyMap(), emptySet())[0].launchVariant.id)
        val remembered = mapOf("FR CANAL+" to LiveQualityChoice("3", "|FR| CANAL+ SD"))
        assertEquals("3", groupLiveChannels(list, remembered, emptySet())[0].launchVariant.id)
    }

    @Test fun rowBorrowsTheGuideOfWhicheverSiblingHasOne() {
        val group = groupLiveChannels(list, emptyMap(), emptySet())[0]
        assertEquals("1", group.representative.id)
        assertEquals("16:29–18:52  Nuremberg", group.row.badge)
    }

    @Test fun favouriteSiblingKeepsTheRowIdentity() {
        val group = groupLiveChannels(list, emptyMap(), setOf("2"))[0]
        assertEquals("2", group.representative.id)
        assertTrue(group.contains("5"))
    }
}
