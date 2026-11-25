package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TranscriptEvent
import dev.hermeskotlin.core.voice.DeviceLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What to show under a message that asks about a proposal. */
sealed interface ProposalView {
    /** Approve and Deny. */
    data object Ask : ProposalView

    /** An answer is on its way. */
    data object Sending : ProposalView

    /** Answered, here or elsewhere: [text] in place of the buttons. */
    data class Answered(val text: String, val approve: Boolean?) : ProposalView
}

/** Reading proposals in a chat: which messages ask, and what shows under them. Pure, so it is testable. */
object ProposalChat {

    /**
     * The proposal [message] asks about, or null: only a finished reply of the bot or a delivery (a routine's output)
     * with the marker on its first line. Never one still streaming, and never the person's own words.
     */
    fun numberOf(message: ChatMessage): Int? = when (message) {
        is ChatMessage.Assistant -> if (message.streaming) null else ProposalMarker.numberOnFirstLine(message.text)
        is ChatMessage.Event -> (message.event as? TranscriptEvent.Routine)?.let { ProposalMarker.numberOnFirstLine(it.body) }
        else -> null
    }

    /** A proposal lives at most 72 hours (the plugin's own limit): a question older than that can't be answered. */
    const val LIFETIME_SECONDS = 72 * 3600.0

    /** When [message] was written, in epoch seconds, when it says. */
    fun timestampOf(message: ChatMessage): Double? = when (message) {
        is ChatMessage.Assistant -> message.timestamp
        is ChatMessage.Event -> message.timestamp
        else -> null
    }

    /**
     * What shows for [number] of [bot], asked [askedAt] (epoch seconds, null when unknown) and seen at [nowSeconds]: the
     * answer this phone gave or saw, else an answer being sent, else the ending a later delivery ([later]) says, else
     * "Expired" once it is older than a proposal lives, else the buttons.
     */
    fun view(bot: String, number: Int, askedAt: Double?, nowSeconds: Double, later: List<ChatMessage>, state: ProposalsState): ProposalView {
        val k = "$bot/$number"
        state.done[k]?.let { return ProposalView.Answered(it.text, it.approve) }
        if (k in state.sending) return ProposalView.Sending
        closedIn(number, later)?.let { return ProposalView.Answered(it.text, it.approve) }
        if (askedAt != null && nowSeconds - askedAt > LIFETIME_SECONDS) return ProposalView.Answered("Expired", null)
        return ProposalView.Ask
    }

    /** The first message in [later] that says how [number] ended, if any. */
    private fun closedIn(number: Int, later: List<ChatMessage>): ProposalOutcome? {
        for (message in later) {
            val text = deliveryOf(message) ?: continue
            if (!text.contains("[proposal", ignoreCase = true)) continue
            ProposalMarker.closings(text).firstOrNull { it.number == number }?.let { return outcomeOf(it.state) }
        }
        return null
    }

    /** Every ending [messages] say, by proposal number, for the chat to keep. */
    fun endings(messages: List<ChatMessage>): List<Pair<Int, ProposalOutcome>> = messages.flatMap { message ->
        val text = deliveryOf(message)?.takeIf { it.contains("[proposal", ignoreCase = true) } ?: return@flatMap emptyList()
        ProposalMarker.closings(text).map { it.number to outcomeOf(it.state) }
    }

    /**
     * What a scheduled job delivered (the proposals job's own channel), the only place an ending is believed: a reply
     * of the model, another bot or a notice could quote or invent one, and an ending is kept and shown as fact.
     */
    private fun deliveryOf(message: ChatMessage): String? = ((message as? ChatMessage.Event)?.event as? TranscriptEvent.Routine)?.body

    /** A closing marker's word as an outcome: "approved" and "done" are yes, "denied" is no, the rest only what it says. */
    fun outcomeOf(state: String): ProposalOutcome = when (state.lowercase()) {
        "approved", "yes", "accepted" -> ProposalOutcome(true, "Approved")
        "done", "executed" -> ProposalOutcome(true, "Done")
        "denied", "no", "declined", "rejected" -> ProposalOutcome(false, "Denied")
        else -> ProposalOutcome(null, state.replaceFirstChar { it.uppercase() })
    }
}

/**
 * Answers a proposal from the chat, the way the notification's buttons do: `/proposal yes N` or `/proposal no N` run as
 * a command in the bot's chat ([BotsApi.runBotCommand], `slash.exec`), never as a prompt. Approve needs the phone
 * unlocked ([deviceLock]); one answer per proposal, whichever button or place it comes from. Runs in [scope], which
 * should outlive the chat so a send isn't cut off by leaving it.
 */
class ProposalAnswerer(
    private val answers: ProposalAnswers,
    private val bots: BotsApi,
    private val deviceLock: DeviceLock,
    private val scope: CoroutineScope,
) {

    private val failures = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Why the last try for a proposal didn't go out, by `bot/number`; gone once it is tried again or answered. */
    val errors: StateFlow<Map<String, String>> = failures.asStateFlow()

    /** The job of the answer on its way, or null when nothing was started. */
    fun answer(bot: String, storedSessionId: String?, number: Int, approve: Boolean): Job? {
        val k = "$bot/$number"
        if (approve && deviceLock.locked()) {
            failures.update { it + (k to "Unlock your phone to approve.") }
            return null
        }
        if (storedSessionId == null) {
            failures.update { it + (k to "This chat isn't saved yet.") }
            return null
        }
        return scope.launch {
            answers.ready()
            if (!answers.claim(bot, number)) return@launch
            failures.update { it - k }
            try {
                val printed = bots.runBotCommand(bot, storedSessionId, ProposalMarker.command(approve, number))
                answers.settle(bot, number, approve, printed.ifBlank { if (approve) "Approved" else "Denied" })
            } catch (e: CancellationException) {
                answers.release(bot, number)
                throw e
            } catch (e: Exception) {
                answers.release(bot, number)
                failures.update { it + (k to "Couldn't send. Try again.") }
            }
        }
    }
}
