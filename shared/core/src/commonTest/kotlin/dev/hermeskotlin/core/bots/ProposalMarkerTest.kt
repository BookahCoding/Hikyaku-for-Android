package dev.hermeskotlin.core.bots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProposalMarkerTest {

    @Test
    fun readsTheNumberFromTheMarker() {
        assertEquals(12, ProposalMarker.numberIn("Shisha wants to restart jellyfin.\n[proposal:12]"))
        assertEquals(3, ProposalMarker.numberIn("[proposal:3] Turn off the night light?"))
        assertEquals(7, ProposalMarker.numberIn("[Proposal: 7 ]"))
    }

    @Test
    fun theSameMarkerTwiceIsStillOneProposal() {
        assertEquals(5, ProposalMarker.numberIn("[proposal:5] ... again [proposal:5]"))
    }

    @Test
    fun noButtonsWithoutAMarkerEvenWhenTheTextMentionsNumbers() {
        assertNull(ProposalMarker.numberIn("Proposal #12: restart jellyfin?"))
        assertNull(ProposalMarker.numberIn("Reply /proposal yes 12 to approve."))
        assertNull(ProposalMarker.numberIn("proposal 12"))
        assertNull(ProposalMarker.numberIn("[proposal]"))
        assertNull(ProposalMarker.numberIn("[proposal:abc]"))
        assertNull(ProposalMarker.numberIn(""))
    }

    @Test
    fun twoDifferentMarkersAreNotGuessedBetween() {
        assertNull(ProposalMarker.numberIn("[proposal:1] and [proposal:2]"))
    }

    @Test
    fun numbersThatCannotBeAProposalAreRefused() {
        assertNull(ProposalMarker.numberIn("[proposal:0]"))
        assertNull(ProposalMarker.numberIn("[proposal:-4]"))
        assertNull(ProposalMarker.numberIn("[proposal:1234567890]")) // ten digits: not one of ours
        assertNull(ProposalMarker.numberIn("[proposal:12.5]"))
    }

    @Test
    fun theCommandsAreExactlyThePluginsOwn() {
        assertEquals("/proposal yes 12", ProposalMarker.command(approve = true, number = 12))
        assertEquals("/proposal no 12", ProposalMarker.command(approve = false, number = 12))
    }

    @Test
    fun aCommandForNoProposalIsNeverBuilt() {
        assertFailsWith<IllegalArgumentException> { ProposalMarker.command(true, 0) }
        assertFailsWith<IllegalArgumentException> { ProposalMarker.command(false, -1) }
    }

    @Test
    fun onlyTheFirstLineCountsInAChat() {
        assertEquals(12, ProposalMarker.numberOnFirstLine("[proposal:12] Restart jellyfin?\nIt is idle."))
        assertEquals(12, ProposalMarker.numberOnFirstLine("\n\n[proposal:12]\nRestart jellyfin?"), "blank lines before it don't count")
        assertNull(ProposalMarker.numberOnFirstLine("Restart jellyfin?\n[proposal:12]"))
        assertNull(ProposalMarker.numberOnFirstLine("Restart jellyfin? Reply later.\nsee [proposal:12]"))
        assertNull(ProposalMarker.numberOnFirstLine("[proposal:1] [proposal:2]"))
        assertNull(ProposalMarker.numberOnFirstLine(""))
    }

    @Test
    fun aClosingMarkerNamesHowItEnded() {
        assertEquals(listOf(ProposalMarker.Closed(7, "approved")), ProposalMarker.closings("Done. [proposal:7:approved]"))
        assertEquals(
            listOf(ProposalMarker.Closed(7, "denied"), ProposalMarker.Closed(8, "expired")),
            ProposalMarker.closings("[proposal:7: Denied ] and [proposal:8:expired]"),
        )
        assertEquals(emptyList(), ProposalMarker.closings("[proposal:7]"))
        assertEquals(emptyList(), ProposalMarker.closings("[proposal:0:approved]"))
        assertEquals(emptyList(), ProposalMarker.closings("[proposal:7:]"))
    }

    @Test
    fun aClosingMarkerNeverAsksAnything() {
        assertNull(ProposalMarker.numberIn("[proposal:7:approved]"), "it would show buttons for an answered proposal")
        assertNull(ProposalMarker.numberOnFirstLine("[proposal:7:denied] Denied by the owner."))
    }

    @Test
    fun bothMarkersAreHiddenFromTheReader() {
        assertEquals("Restart jellyfin?\nIt is idle.", ProposalMarker.without("[proposal:12] Restart jellyfin?\nIt is idle."))
        assertEquals("Restart jellyfin?", ProposalMarker.without("Restart jellyfin? [proposal:12]"))
        assertEquals("Approved by the owner.", ProposalMarker.without("Approved by the owner. [proposal:12:approved]"))
    }

    @Test
    fun aTypedAnswerIsReadOnlyWhenItIsExactlyYesOrNoAndANumber() {
        assertEquals(true to 12, ProposalMarker.typedAnswer("yes 12"))
        assertEquals(false to 3, ProposalMarker.typedAnswer("  No   3 "))
        assertNull(ProposalMarker.typedAnswer("yes"))
        assertNull(ProposalMarker.typedAnswer("yes 0"))
        assertNull(ProposalMarker.typedAnswer("maybe 4"))
        assertNull(ProposalMarker.typedAnswer("yes 4 and more"))
        assertNull(ProposalMarker.typedAnswer(""))
    }
}
