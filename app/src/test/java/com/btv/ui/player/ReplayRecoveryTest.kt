package com.btv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayRecoveryTest {
    @Test
    fun `connection limit is waited out with a growing delay`() {
        val recovery = ReplayRecovery()
        val delays = (0 until 5).map { recovery.onFailure(nowMs = 1_000L + it, httpCode = 403, variantCount = 1, canSwitchFormat = true).delayMs }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), delays)
    }

    @Test
    fun `gives up once the outage outlasts the budget`() {
        val recovery = ReplayRecovery(budgetMs = 45_000L)
        assertFalse(recovery.onFailure(1_000L, null, 1, false).giveUp)
        assertFalse(recovery.onFailure(46_000L, null, 1, false).giveUp)
        assertTrue(recovery.onFailure(46_001L, null, 1, false).giveUp)
    }

    @Test
    fun `404 questions the url format first, then the quality`() {
        val recovery = ReplayRecovery()
        val first = recovery.onFailure(0L, 404, variantCount = 2, canSwitchFormat = true)
        assertTrue(first.switchFormat)
        assertEquals(0L, first.delayMs)
        val second = recovery.onFailure(1L, 404, variantCount = 2, canSwitchFormat = false)
        assertFalse(second.switchFormat)
        assertTrue(second.nextVariant)
    }

    @Test
    fun `repeated failures on one quality move to the next`() {
        val recovery = ReplayRecovery(failuresPerVariant = 2)
        assertFalse(recovery.onFailure(0L, 403, 3, false).nextVariant)
        assertTrue(recovery.onFailure(1L, 403, 3, false).nextVariant)
        assertFalse(recovery.onFailure(2L, 403, 3, false).nextVariant)
        // A single-quality channel just keeps retrying.
        val single = ReplayRecovery(failuresPerVariant = 2)
        repeat(4) { assertFalse(single.onFailure(it.toLong(), 403, 1, false).nextVariant) }
    }

    @Test
    fun `playing again starts a fresh outage`() {
        val recovery = ReplayRecovery(budgetMs = 10_000L)
        recovery.onFailure(0L, null, 1, false)
        recovery.reset()
        val decision = recovery.onFailure(60_000L, null, 1, false)
        assertFalse(decision.giveUp)
        assertEquals(1_000L, decision.delayMs)
    }
}
