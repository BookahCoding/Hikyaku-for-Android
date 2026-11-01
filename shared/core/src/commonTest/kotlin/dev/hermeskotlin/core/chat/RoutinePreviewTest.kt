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
        val shown = routine("x".repeat(5000)).preview()
        assertTrue(shown.length <= 300)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun aSingleLongLineHasMore() {
        assertTrue(routine("x".repeat(5000)).hasMore)
    }

    @Test
    fun trailingBlankLinesAreNotMore() {
        assertFalse(routine("a\nb\nc\n\n   \n").hasMore)
    }
}
