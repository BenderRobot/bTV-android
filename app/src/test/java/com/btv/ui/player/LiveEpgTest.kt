package com.btv.ui.player

import com.btv.data.cache.EpgProgramInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveEpgTest {
    private val guide = listOf(
        EpgProgramInfo("AVP: Alien vs. Predator", 21_30L, 23_30L),
        EpgProgramInfo("Face/Off", 18_30L, 21_30L),
        EpgProgramInfo("Punisher: War Zone", 23_30L, 25_30L)
    )

    @Test fun findsTheProgrammeAiringAndItsSuccessorWhateverTheOrder() {
        val program = nowAndNext(guide, 20_00L)!!
        assertEquals("Face/Off", program.title)
        assertEquals("AVP: Alien vs. Predator", program.nextTitle)
        assertEquals(21_30L, program.nextStartMs)
    }

    @Test fun lastProgrammeHasNoSuccessor() {
        assertNull(nowAndNext(guide, 24_00L)!!.nextTitle)
    }

    @Test fun nothingAiringOutsideTheGuide() {
        assertNull(nowAndNext(guide, 26_00L))
        assertNull(nowAndNext(emptyList(), 20_00L))
    }

    @Test fun progressIsClampedToTheProgramme() {
        val program = LiveProgram("Face/Off", 1_000L, 3_000L)
        assertEquals(0.5f, program.progress(2_000L))
        assertEquals(1f, program.progress(9_000L))
        assertEquals(0f, program.progress(0L))
    }
}
