package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.settings.RunningSend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposerControlsTest {
    @Test
    fun choosingAModeKeepsTheDraftUntilSendIsPressed() {
        val modes = SendModeSelection()
        val sent = mutableListOf<RunningSend?>()
        modes.select(RunningSend.StopAndSend)
        assertEquals(emptyList(), sent)
        assertEquals(RunningSend.StopAndSend, modes.mode(true, RunningSend.Steer, false))
        modes.submit(true, RunningSend.Steer, false) { sent.add(it) }
        assertEquals(listOf<RunningSend?>(RunningSend.StopAndSend), sent)
        assertNull(modes.picked)
    }

    @Test
    fun attachmentsExplainTheQueueFallbackWithoutChangingTheSelection() {
        val modes = SendModeSelection()
        modes.select(RunningSend.Steer)
        assertEquals(RunningSend.Queue, modes.mode(true, RunningSend.Steer, true))
        assertEquals("Files can't steer a reply. Send will queue this message until the reply ends.", modes.explanation(true, RunningSend.Steer, true))
        assertEquals(RunningSend.Steer, modes.mode(true, RunningSend.Steer, false))
        assertNull(modes.explanation(false, RunningSend.Steer, true))
    }

    @Test
    fun anEmptyUnfocusedComposerStaysCompactEvenAtTheEnd() {
        assertEquals(0f, composerExpansion(focused = false, hasDraft = false, dictating = false, editing = false))
        assertEquals(1f, composerExpansion(focused = true, hasDraft = false, dictating = false, editing = false))
        assertEquals(1f, composerExpansion(focused = false, hasDraft = true, dictating = false, editing = false))
        assertEquals(1f, composerExpansion(focused = false, hasDraft = false, dictating = true, editing = false))
        assertEquals(1f, composerExpansion(focused = false, hasDraft = false, dictating = false, editing = true))
    }
}
