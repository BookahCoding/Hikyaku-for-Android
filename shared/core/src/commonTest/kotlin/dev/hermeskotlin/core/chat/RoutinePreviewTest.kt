package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutinePreviewTest {
    private fun routine(body: String) = TranscriptEvent.Routine("mail-watch", body)

    @Test
    fun theFirstThreeNonBlankLines() {
        assertEquals("a\nb\nc", routine("\na\n\nb\nc\nd").preview())
    }

    @Test
    fun hasMoreWhenLinesAreLeftOut() {
        assertTrue(routine("a\nb\nc\nd").hasMore)
        assertFalse(routine("a\nb").hasMore)
    }

    @Test
    fun anEmptyBodyHasNothingToShow() {
        assertEquals("", routine("   \n ").preview())
        assertFalse(routine("").hasMore)
    }

    @Test
    fun aLongLineIsCut() {
        assertTrue(routine("x".repeat(5000)).preview().length <= 300)
    }
}
