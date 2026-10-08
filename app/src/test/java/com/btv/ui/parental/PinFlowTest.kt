package com.btv.ui.parental

import com.btv.data.store.PinStore
import com.btv.data.store.isAdultCategoryName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinFlowTest {
    private class FakeStore(var pin: String? = null) : PinStore {
        override suspend fun hasPin() = pin != null
        override suspend fun verify(pin: String) = pin == this.pin
        override suspend fun setPin(pin: String) { this.pin = pin }
    }

    // Unconfined: every launch runs to completion inline, the fake never suspends.
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test fun firstUseCreatesThePinTwiceThenProceeds() {
        val store = FakeStore()
        val flow = PinFlow(store, scope)
        var played = false
        flow.require("Chaîne réservée aux adultes.") { played = true }
        assertEquals(PinStep.CREATE, flow.prompt.value?.step)
        flow.submit("1234")
        assertEquals(PinStep.CONFIRM, flow.prompt.value?.step)
        flow.submit("1234")
        assertTrue(played)
        assertEquals("1234", store.pin)
        assertNull(flow.prompt.value)
    }

    @Test fun mismatchedConfirmationStartsOver() {
        val store = FakeStore()
        val flow = PinFlow(store, scope)
        flow.require("") {}
        flow.submit("1234")
        flow.submit("4321")
        assertEquals(PinStep.CREATE, flow.prompt.value?.step)
        assertEquals("Les deux codes ne correspondent pas", flow.prompt.value?.error)
        assertNull(store.pin)
    }

    @Test fun wrongPinIsRefusedAndRetryable() {
        val flow = PinFlow(FakeStore("1234"), scope)
        var played = false
        flow.require("") { played = true }
        val first = flow.prompt.value!!
        flow.submit("0000")
        assertFalse(played)
        assertEquals("Code incorrect", flow.prompt.value?.error)
        assertTrue(flow.prompt.value!!.serial != first.serial) // the dialog clears its digits
        flow.submit("1234")
        assertTrue(played)
    }

    @Test fun changeAsksTheOldPinFirst() {
        val store = FakeStore("1234")
        val flow = PinFlow(store, scope)
        flow.change()
        assertEquals(PinStep.VERIFY, flow.prompt.value?.step)
        flow.submit("1234")
        flow.submit("5678")
        flow.submit("5678")
        assertEquals("5678", store.pin)
    }

    @Test fun cancelNeverRunsTheProtectedAction() {
        val flow = PinFlow(FakeStore("1234"), scope)
        var played = false
        flow.require("") { played = true }
        flow.cancel()
        flow.submit("1234")
        assertFalse(played)
    }

    @Test fun repeatedWrongPinsPauseEntry() {
        val clock = longArrayOf(0L)
        val flow = PinFlow(FakeStore("1234"), scope) { clock[0] }
        var played = false
        flow.require("") { played = true }
        repeat(PIN_MAX_ATTEMPTS) { flow.submit("0000") }
        assertEquals("Trop d'essais. Réessayez dans 30 s.", flow.prompt.value?.error)
        flow.submit("1234") // even the right PIN waits out the pause
        assertFalse(played)
        clock[0] += PIN_LOCKOUT_MS
        flow.submit("1234")
        assertTrue(played)
    }

    @Test fun adultCategoryNamesAreRecognised() {
        assertTrue(isAdultCategoryName("FOR ADULTS"))
        assertTrue(isAdultCategoryName("|FR| ADULTES"))
        assertTrue(isAdultCategoryName("XXX | TV"))
        assertTrue(isAdultCategoryName("+18 Channels"))
        assertFalse(isAdultCategoryName("|FR| SPORTS"))
        assertFalse(isAdultCategoryName("|US| ADULTSWIM")) // whole words only
    }
}
