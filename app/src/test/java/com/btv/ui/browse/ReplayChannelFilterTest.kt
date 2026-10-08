package com.btv.ui.browse

import com.btv.data.model.XtreamCategory
import com.btv.data.model.XtreamChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayChannelFilterTest {
    private val categories = listOf(
        XtreamCategory(categoryId = "10", categoryName = "|FR| GENERALISTES"),
        XtreamCategory(categoryId = "20", categoryName = "|AR| NEWS"),
        XtreamCategory(categoryId = "30", categoryName = "|FR| ADULTES")
    )

    private fun channel(name: String, categoryId: String? = "10") =
        XtreamChannel(streamId = name.hashCode().toString(), name = name, categoryId = categoryId, tvArchive = 1)

    private fun filter(
        hidden: Set<String> = emptySet(),
        revealed: Set<String> = emptySet(),
        disabled: Set<String> = emptySet()
    ) = ReplayChannelFilter(categories, hidden, revealed, disabled)

    @Test
    fun `disabled tag on the channel name hides it even inside an allowed category`() {
        val f = filter(disabled = setOf("BE"))
        assertFalse(f.isVisible(channel("|BE| AB1 FHD")))
        assertTrue(f.isVisible(channel("|FR| TF1 FHD")))
    }

    @Test
    fun `disabled tag on the live category hides its channels`() {
        assertFalse(filter(disabled = setOf("AR")).isVisible(channel("AL JAZEERA", categoryId = "20")))
    }

    @Test
    fun `hidden live category hides its channels`() {
        assertFalse(filter(hidden = setOf("10")).isVisible(channel("|FR| TF1 FHD")))
    }

    @Test
    fun `adult channels stay hidden until their category is unlocked`() {
        assertFalse(filter().isVisible(channel("|FR| CHAINE X", categoryId = "30")))
        assertTrue(filter(revealed = setOf("30")).isVisible(channel("|FR| CHAINE X", categoryId = "30")))
        assertFalse(filter().isVisible(channel("|FR| XXX TV", categoryId = null)))
    }

    @Test
    fun `search key leaves out tags`() {
        assertEquals("DAZN 1 FHD", replaySearchKey("|BE| DAZN 1 FHD (FR)"))
        assertEquals("FRANCE 2 FHD", replaySearchKey("|FR| FRANCE 2 FHD"))
        assertEquals("TF1", replaySearchKey("TF1"))
    }
}
