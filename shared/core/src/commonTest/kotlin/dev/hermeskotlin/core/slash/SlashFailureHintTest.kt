package dev.hermeskotlin.core.slash

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlashFailureHintTest {
    private val raw = "Heartbeats unavailable (no active session)."

    @Test
    fun heartbeatWithTheWorkerBugGetsAnExplanation() {
        val hint = slashFailureHint("heartbeat", raw)!!
        assertContains(hint, "/heartbeat")
        assertContains(hint, "147596")
    }

    @Test
    fun loopGetsItToo() {
        assertContains(slashFailureHint("loop", "Loops unavailable (no active session).")!!, "/loop")
    }

    @Test
    fun otherCommandsAndOtherOutputAreLeftAlone() {
        assertNull(slashFailureHint("status", raw))
        assertNull(slashFailureHint("heartbeat", "♥ Heartbeat set (every 10m): test"))
        assertEquals(null, slashFailureHint("heartbeat", ""))
    }
}
