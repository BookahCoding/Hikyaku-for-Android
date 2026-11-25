package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.voice.DeviceLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProposalAnswererTest {

    private class Rig(val answerer: ProposalAnswerer, val ledger: ProposalAnswers, val calls: MutableList<Pair<String, JsonObject>>)

    /** [reply] answers `slash.exec` and `command.dispatch`; `session.resume` always finds the chat. */
    private suspend fun TestScope.rig(locked: Boolean = false, reply: (String) -> String = { """{"type":"exec","output":"Proposal 12 approved."}""" }): Rig {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val gateway = FakeGateway(backgroundScope, reconnects = false)
        gateway.answer = { call ->
            if (call.method != "client.capabilities") calls += call.method to call.params
            if (call.method == "session.resume") """{"session_id":"runtime-1"}""" else reply(call.method)
        }
        val ledger = ProposalAnswers()
        val answerer = ProposalAnswerer(ledger, BotsApi(gateway.start()), DeviceLock { locked }, backgroundScope)
        return Rig(answerer, ledger, calls)
    }

    private fun Rig.commands() = calls.filter { it.first == "slash.exec" }.map { it.second["command"]?.jsonPrimitive?.contentOrNull }

    @Test
    fun aLockedPhoneApprovesNothing() = runTest {
        val r = rig(locked = true)

        assertNull(r.answerer.answer("default", "stored-9", 12, approve = true))

        assertEquals(emptyList(), r.calls, "something went out from a locked phone")
        assertEquals("Unlock your phone to approve.", r.answerer.errors.value["default/12"])
        assertFalse(r.ledger.answered("default", 12))
    }

    @Test
    fun denyNeedsNoUnlock() = runTest {
        val r = rig(locked = true)

        r.answerer.answer("default", "stored-9", 12, approve = false)!!.join()

        assertEquals(listOf("/proposal no 12"), r.commands())
    }

    @Test
    fun theExactCommandGoesToTheRightChatAsASlashCommand() = runTest {
        val r = rig()

        r.answerer.answer("work", "stored-9", 12, approve = true)!!.join()

        assertEquals(listOf("session.resume", "slash.exec"), r.calls.map { it.first })
        assertEquals("stored-9", r.calls[0].second["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("work", r.calls[0].second["profile"]?.jsonPrimitive?.contentOrNull)
        assertEquals("runtime-1", r.calls[1].second["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(listOf("/proposal yes 12"), r.commands())
        assertFalse(r.calls.any { it.first == "prompt.submit" })
    }

    @Test
    fun thePluginsOwnMessageReplacesTheButtons() = runTest {
        val r = rig()

        r.answerer.answer("default", "stored-9", 12, approve = true)!!.join()

        assertEquals(ProposalOutcome(true, "Proposal 12 approved."), r.ledger.outcome("default", 12))
    }

    @Test
    fun aRefusalIsShownAsTheResultToo() = runTest {
        val r = rig { """{"type":"exec","output":"No such proposal."}""" }

        r.answerer.answer("default", "stored-9", 999999, approve = false)!!.join()

        assertEquals(ProposalOutcome(false, "No such proposal."), r.ledger.outcome("default", 999999))
    }

    @Test
    fun whenThePluginPrintsNothingTheWordIsShown() = runTest {
        val r = rig { """{"type":"exec","output":""}""" }

        r.answerer.answer("default", "stored-9", 1, approve = true)!!.join()
        r.answerer.answer("default", "stored-9", 2, approve = false)!!.join()

        assertEquals("Approved", r.ledger.outcome("default", 1)?.text)
        assertEquals("Denied", r.ledger.outcome("default", 2)?.text)
    }

    @Test
    fun aDoubleTapAndTheOtherButtonSendOnlyOnce() = runTest {
        val r = rig()

        val first = r.answerer.answer("default", "stored-9", 12, approve = true)
        val second = r.answerer.answer("default", "stored-9", 12, approve = true)
        val other = r.answerer.answer("default", "stored-9", 12, approve = false)
        listOfNotNull(first, second, other).forEach { it.join() }
        r.answerer.answer("default", "stored-9", 12, approve = false)?.join()

        assertEquals(listOf("/proposal yes 12"), r.commands(), "answered more than once")
    }

    @Test
    fun anAnswerAlreadyKnownSendsNothing() = runTest {
        val r = rig()
        r.ledger.settle("default", 12, true, "Approved")

        r.answerer.answer("default", "stored-9", 12, approve = false)!!.join()

        assertEquals(emptyList(), r.calls)
    }

    @Test
    fun aFailureBringsTheButtonsBackWithAnError() = runTest {
        var failing = true
        val r = rig { if (failing) "error:5026" else """{"type":"exec","output":"Proposal 12 denied."}""" }

        r.answerer.answer("default", "stored-9", 12, approve = false)!!.join()

        assertFalse(r.ledger.answered("default", 12), "a failed send must leave the buttons")
        assertEquals("Couldn't send. Try again.", r.answerer.errors.value["default/12"])

        failing = false
        r.answerer.answer("default", "stored-9", 12, approve = false)!!.join()

        assertEquals("Proposal 12 denied.", r.ledger.outcome("default", 12)?.text)
        assertNull(r.answerer.errors.value["default/12"], "the old error stayed after a good answer")
    }

    @Test
    fun aReplyThatWouldBecomeAPromptIsNotAnAnswer() = runTest {
        val r = rig { """{"type":"send","message":"yes 12"}""" }

        r.answerer.answer("default", "stored-9", 12, approve = true)!!.join()

        assertFalse(r.ledger.answered("default", 12))
        assertNotNull(r.answerer.errors.value["default/12"])
        assertFalse(r.calls.any { it.first == "prompt.submit" })
    }

    @Test
    fun aChatNotYetSavedHasNothingToAnswerIn() = runTest {
        val r = rig()

        assertNull(r.answerer.answer("default", null, 12, approve = false))

        assertEquals(emptyList(), r.calls)
        assertTrue(r.answerer.errors.value.containsKey("default/12"))
    }
}
