package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProposalAnswersTest {

    private fun TestScope.ledger(store: KeyValueStore = InMemoryKeyValueStore(), keep: Int = 200) = ProposalAnswers(store, backgroundScope, keep)

    @Test
    fun onlyOneCallerSendsTheAnswer() = runTest {
        val answers = ledger()
        answers.ready()
        assertTrue(answers.claim("default", 12))
        assertFalse(answers.claim("default", 12), "a second tap while it is being sent")
        assertTrue(answers.answered("default", 12))
    }

    @Test
    fun anAnsweredProposalStaysAnswered() = runTest {
        val answers = ledger()
        assertTrue(answers.claim("default", 12))
        answers.settle("default", 12, true, "Approved")
        assertFalse(answers.claim("default", 12), "Deny after Approve, or the buttons coming back")
        assertEquals(ProposalOutcome(true, "Approved"), answers.outcome("default", 12))
    }

    @Test
    fun aFailedSendMayBeTriedAgain() = runTest {
        val answers = ledger()
        assertTrue(answers.claim("default", 12))
        answers.release("default", 12)
        assertFalse(answers.answered("default", 12))
        assertTrue(answers.claim("default", 12))
    }

    @Test
    fun proposalsAreKeptApartPerBotAndNumber() = runTest {
        val answers = ledger()
        assertTrue(answers.claim("default", 12))
        assertTrue(answers.claim("default", 13))
        assertTrue(answers.claim("other", 12))
        assertFalse(answers.answered("third", 12))
    }

    @Test
    fun anAnswerIsStillThereAfterTheAppIsReopened() = runTest {
        val store = InMemoryKeyValueStore()
        val first = ledger(store)
        first.ready()
        first.settle("default", 12, false, "Denied")
        runCurrent()

        val reopened = ledger(store)
        reopened.ready()

        assertEquals(ProposalOutcome(false, "Denied"), reopened.outcome("default", 12))
        assertFalse(reopened.claim("default", 12), "answered twice after a restart")
    }

    @Test
    fun aSendCutShortIsNotRemembered() = runTest {
        val store = InMemoryKeyValueStore()
        val first = ledger(store)
        first.ready()
        assertTrue(first.claim("default", 12))
        first.settle("default", 13, true, "Approved")
        runCurrent()

        val reopened = ledger(store)
        reopened.ready()

        assertFalse(reopened.answered("default", 12), "being sent says nothing about whether it went")
        assertTrue(reopened.claim("default", 12))
        assertTrue(reopened.answered("default", 13))
    }

    @Test
    fun anAnswerSeenElsewhereNeverReplacesOneAlreadyKnown() = runTest {
        val answers = ledger()
        answers.settle("default", 12, true, "Approved")
        answers.record("default", 12, false, "Denied")
        assertEquals(ProposalOutcome(true, "Approved"), answers.outcome("default", 12))

        answers.record("default", 13, null, "Expired")
        assertEquals(ProposalOutcome(null, "Expired"), answers.outcome("default", 13))
        assertFalse(answers.claim("default", 13), "it ended elsewhere")
    }

    @Test
    fun whatIsSeenElsewhereIsKeptToo() = runTest {
        val store = InMemoryKeyValueStore()
        val first = ledger(store)
        first.record("default", 14, true, "Done")
        runCurrent()

        val reopened = ledger(store)
        reopened.ready()
        assertEquals(ProposalOutcome(true, "Done"), reopened.outcome("default", 14))
    }

    @Test
    fun theOldestAnswersAreForgottenPastTheLimit() = runTest {
        val answers = ledger(keep = 2)
        for (n in 1..3) answers.settle("default", n, true, "Approved")
        assertNull(answers.outcome("default", 1))
        assertTrue(answers.answered("default", 2))
        assertTrue(answers.answered("default", 3))
    }

    @Test
    fun unreadableKeptDataStartsEmptyAndDoesNotBlock() = runTest {
        val store = InMemoryKeyValueStore()
        store.put("proposals.answers.v1", "{not json")
        val answers = ledger(store)
        answers.ready()
        assertTrue(answers.claim("default", 1))
    }

    @Test
    fun withoutAStoreItIsPlainMemory() = runTest {
        val answers = ProposalAnswers()
        answers.ready()
        assertTrue(answers.claim("default", 1))
        answers.settle("default", 1, true, "Approved")
        assertEquals("Approved", answers.outcome("default", 1)?.text)
    }
}
