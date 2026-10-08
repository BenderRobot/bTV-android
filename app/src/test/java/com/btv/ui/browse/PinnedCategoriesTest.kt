package com.btv.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedCategoriesTest {
    private val quick = listOf(
        BrowseCategory("recently_viewed", "Récemment consultés", isQuickAccess = true),
        BrowseCategory("show_all", "Tout afficher", isQuickAccess = true)
    )
    private val real = listOf(
        BrowseCategory("1", "AFRICA | TV"),
        BrowseCategory("2", "FRANCE FHD | TV"),
        BrowseCategory("3", "SPORTS | TV")
    )

    @Test fun pinnedComeRightAfterQuickAccessInPinOrder() {
        val arranged = arrangeCategories(quick, real, listOf("3", "2"))
        assertEquals(listOf("recently_viewed", "show_all", "3", "2", "1"), arranged.map { it.id })
        assertTrue(arranged[2].isPinned && arranged[3].isPinned && !arranged[4].isPinned)
    }

    @Test fun pinOfAHiddenCategoryIsIgnored() {
        val arranged = arrangeCategories(quick, real, listOf("99"))
        assertEquals(listOf("recently_viewed", "show_all", "1", "2", "3"), arranged.map { it.id })
    }

    @Test fun eachCategoryAppearsOnce() {
        val arranged = arrangeCategories(quick, real, listOf("2", "2"))
        assertEquals(arranged.size, arranged.map { it.id }.distinct().size)
    }
}
