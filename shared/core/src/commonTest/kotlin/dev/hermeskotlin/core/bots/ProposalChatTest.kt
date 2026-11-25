package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TranscriptEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProposalChatTest {

    private fun reply(text: String, streaming: Boolean = false, at: Double? = null) = ChatMessage.Assistant("a", text = text, streaming = streaming, timestamp = at)
    private fun delivery(body: String, at: Double? = null) = ChatMessage.Event("e", TranscriptEvent.Routine("proposals-tick", body), at)
    private val none = ProposalsState()
    private val now = 1_000_000.0

    private fun view(number: Int = 12, askedAt: Double? = null, later: List<ChatMessage> = emptyList(), state: ProposalsState = none) =
        ProposalChat.view("default", number, askedAt, now, later, state)

    @Test
    fun aFinishedReplyWithTheMarkerOnItsFirstLineAsks() {
        assertEquals(12, ProposalChat.numberOf(reply("[proposal:12] Restart jellyfin?")))
        assertNull(ProposalChat.numberOf(reply("Restart jellyfin?\n[proposal:12]")), "not the first line")
        assertNull(ProposalChat.numberOf(reply("Restart jellyfin?")))
    }

    @Test
    fun aDeliveryFromTheProposalsJobAsksToo() {
        assertEquals(7, ProposalChat.numberOf(delivery("[proposal:7] Turn on the sofa light?")))
        assertEquals(7, ProposalChat.numberOf(delivery("Turn on the sofa light? [proposal:7]")), "the end of the first line is still the first line")
        assertNull(ProposalChat.numberOf(delivery("Turn on the sofa light?\n[proposal:7]")))
    }

    @Test
    fun nothingAsksWhileItIsStillBeingWritten() {
        assertNull(ProposalChat.numberOf(reply("[proposal:12] Restart", streaming = true)))
    }

    @Test
    fun theUsersOwnWordsNeverAsk() {
        assertNull(ProposalChat.numberOf(ChatMessage.User("u", "[proposal:12] approve please")))
        assertNull(ProposalChat.numberOf(ChatMessage.Notice("n", "[proposal:12]")))
        assertNull(ProposalChat.numberOf(ChatMessage.Command("c", "/proposal", "[proposal:12]", running = false)))
    }

    @Test
    fun anUnansweredProposalShowsTheButtons() {
        assertEquals(ProposalView.Ask, view())
    }

    @Test
    fun anAnswerOnItsWayShowsSending() {
        assertEquals(ProposalView.Sending, view(state = ProposalsState(sending = setOf("default/12"))))
    }

    @Test
    fun anAnswerGivenOnThisPhoneReplacesTheButtonsWithTheResult() {
        val state = ProposalsState(done = mapOf("default/12" to ProposalOutcome(true, "Proposal 12 approved.")))
        assertEquals(ProposalView.Answered("Proposal 12 approved.", true), view(state = state))
    }

    @Test
    fun anAnswerForAnotherProposalOrBotDoesNotCount() {
        val state = ProposalsState(done = mapOf("default/13" to ProposalOutcome(true, "x"), "other/12" to ProposalOutcome(true, "y")))
        assertEquals(ProposalView.Ask, view(state = state))
    }

    @Test
    fun aLaterDeliverySayingHowItEndedClosesTheButtons() {
        assertEquals(ProposalView.Answered("Approved", true), view(later = listOf(delivery("The owner approved it. [proposal:12:approved]"))))
        assertEquals(ProposalView.Answered("Denied", false), view(later = listOf(delivery("Denied elsewhere [proposal:12:denied]"))))
        assertEquals(ProposalView.Answered("Expired", null), view(later = listOf(delivery("[proposal:12:expired]"))))
    }

    @Test
    fun anEndingForAnotherProposalLeavesTheButtons() {
        assertEquals(ProposalView.Ask, view(later = listOf(delivery("[proposal:13:approved]"))))
    }

    @Test
    fun aRepliesClaimToAnEndingIsNotBelieved() {
        // The model, another bot or a notice can quote or invent a marker; only the job's own delivery counts.
        val quoted = listOf(
            reply("The old one said [proposal:12:approved]"),
            ChatMessage.Event("f", TranscriptEvent.FromBot("other", null, "[proposal:12:approved]")),
            ChatMessage.Notice("n", "[proposal:12:approved]"),
        )
        assertEquals(ProposalView.Ask, view(later = quoted))
        assertEquals(emptyList(), ProposalChat.endings(quoted), "an invented ending would be kept as fact")
    }

    @Test
    fun whatThisPhoneKnowsBeatsALaterDelivery() {
        val state = ProposalsState(done = mapOf("default/12" to ProposalOutcome(false, "Denied")))
        assertEquals(ProposalView.Answered("Denied", false), view(later = listOf(delivery("[proposal:12:approved]")), state = state))
    }

    @Test
    fun aQuestionOlderThanAProposalLivesShowsExpired() {
        val old = now - ProposalChat.LIFETIME_SECONDS - 60
        assertEquals(ProposalView.Answered("Expired", null), view(askedAt = old))
    }

    @Test
    fun aRecentQuestionOrOneWithoutATimeStillAsks() {
        assertEquals(ProposalView.Ask, view(askedAt = now - 3600))
        assertEquals(ProposalView.Ask, view(askedAt = null))
    }

    @Test
    fun anAnswerIsShownEvenForAnOldQuestion() {
        val old = now - ProposalChat.LIFETIME_SECONDS - 60
        val state = ProposalsState(done = mapOf("default/12" to ProposalOutcome(true, "Approved")))
        assertEquals(ProposalView.Answered("Approved", true), view(askedAt = old, state = state))
    }

    @Test
    fun theTimeComesFromTheReplyOrTheDeliveryOnly() {
        assertEquals(5.0, ProposalChat.timestampOf(reply("x", at = 5.0)))
        assertEquals(6.0, ProposalChat.timestampOf(delivery("x", at = 6.0)))
        assertNull(ProposalChat.timestampOf(ChatMessage.User("u", "x")))
    }

    @Test
    fun endingsAreCollectedForKeepingFromDeliveriesOnly() {
        val messages = listOf(
            delivery("[proposal:1] Ask?"),
            delivery("[proposal:1:approved] and [proposal:2:expired]"),
            ChatMessage.User("u", "[proposal:3:approved]"),
            reply("[proposal:4:approved]"),
        )
        assertEquals(
            listOf(1 to ProposalOutcome(true, "Approved"), 2 to ProposalOutcome(null, "Expired")),
            ProposalChat.endings(messages),
        )
    }
}
