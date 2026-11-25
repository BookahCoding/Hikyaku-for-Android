package dev.hermeskotlin.android.notify

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.ProposalMarker
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.InputAnswers
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.SessionWatcher
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.wear.WatchRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** The buttons and inline replies of [ChatNotifications], applied to the chat [ChatHost] has open or to one [SessionWatcher] follows. */
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {

    private val host: ChatHost by inject()
    private val connection: GatewayConnection by inject()
    private val notifications: ChatNotifications by inject()
    private val scope: CoroutineScope by inject()
    private val bots: BotsApi by inject()
    private val push: PushSetup by inject()
    private val watcher: SessionWatcher by inject()
    private val relay: WatchRelay by inject()

    /** Whether the phone was locked when the button was pressed; fail closed when Android can't say. */
    private var phoneLocked = true

    override fun onReceive(context: Context, intent: Intent) {
        phoneLocked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true
        val pending = goAsync()
        scope.launch {
            try {
                handle(intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(intent: Intent) {
        val session = host.session.value
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        when (intent.action) {
            ACTION_DISCONNECT -> {
                // Notifications anywhere is what keeps the quiet notification up between turns.
                withTimeoutOrNull(RECEIVER_BUDGET_MS) { push.disable() }
            }
            ACTION_APPROVE -> {
                val choice = ApprovalChoice.fromWire(intent.getStringExtra(EXTRA_CHOICE).orEmpty()) ?: return
                answer(session, intent.getStringExtra(EXTRA_REQUEST_ID) ?: return) { InputAnswers.approval(choice) }
            }
            ACTION_CLARIFY -> {
                if (text.isNullOrEmpty()) return
                answer(session, intent.getStringExtra(EXTRA_REQUEST_ID) ?: return) { request ->
                    (request as? InputRequest.Clarify)?.let { InputAnswers.clarify(it, listOf(listOf(text))) }
                }
            }
            ACTION_DECLINE_CALL -> intent.getStringExtra(EXTRA_REQUEST_ID)?.let(notifications::decline)
            ACTION_BOT_CLEARED -> intent.getStringExtra(EXTRA_BOT)?.let(notifications::forgetBot)
            ACTION_ROOM_CLEARED -> intent.getStringExtra(ChatNotifications.EXTRA_OPEN_ROOM)?.let(notifications::forgetRoom)
            ACTION_BOT_REPLY -> {
                val bot = intent.getStringExtra(EXTRA_BOT) ?: return
                val storedId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                if (text.isNullOrEmpty()) return
                notifications.cancelBot(bot)
                awaitConnection()
                // The bot's answer comes back as its next notification.
                val sent = scope.async { runCatching { bots.sendToBot(bot, storedId, text) }.isSuccess }
                if (withTimeoutOrNull(RECEIVER_BUDGET_MS) { sent.await() } == false) {
                    notifications.postFailure(bot, ChatNotifications.BOT_ID, "Couldn't send it. Open Hikyaku to try again.")
                }
            }
            ACTION_PROPOSAL_YES -> answerProposal(intent, approve = true)
            ACTION_PROPOSAL_NO -> answerProposal(intent, approve = false)
            ACTION_REPLY -> {
                val storedId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
                if (text.isNullOrEmpty()) return
                // Clears the inline reply's spinner; a failure posts in its place.
                notifications.cancelReply(storedId)
                if (session == null || session.state.value.storedSessionId != storedId) {
                    // A chat followed without being open takes the reply straight to its live session.
                    if (storedId !in watcher.chats.value) {
                        notifications.postFailure(storedId, ChatNotifications.REPLY_ID, "That chat isn't open anymore. Open Hikyaku to reply.")
                        return
                    }
                    awaitConnection()
                    val sent = scope.async { watcher.reply(storedId, text) }
                    if (withTimeoutOrNull(RECEIVER_BUDGET_MS) { sent.await() } == false) {
                        notifications.postFailure(storedId, ChatNotifications.REPLY_ID, "Couldn't send it. Open Hikyaku to send it again.")
                    }
                    return
                }
                awaitConnection()
                // The send itself outlives this receiver's few seconds; only the verdict is waited for.
                val sent = scope.async { session.send(text) }
                if (withTimeoutOrNull(RECEIVER_BUDGET_MS) { sent.await() } == false) {
                    notifications.postFailure(storedId, ChatNotifications.REPLY_ID, session.state.value.error ?: "Open Hikyaku to send it again.")
                }
            }
        }
    }

    /**
     * Approve or Deny on a proposal's notification: runs `/proposal yes|no N` in the bot's chat as a command, the way
     * the composer does ([BotsApi.runBotCommand], `slash.exec`), never as a prompt the model could read. Approve needs the phone unlocked: the button asks Android for that
     * (setAuthenticationRequired, Android 12+), and this checks again, so an older Android or a button that got
     * through anyway doesn't approve for whoever holds a locked phone.
     */
    private suspend fun answerProposal(intent: Intent, approve: Boolean) {
        val bot = intent.getStringExtra(EXTRA_BOT) ?: return
        val number = intent.getIntExtra(EXTRA_PROPOSAL, 0).takeIf { it >= 1 } ?: return
        if (approve && phoneLocked) {
            // The proposal's notification stays as it is, with its buttons.
            notifications.postFailure("$bot/proposal-locked/$number", ChatNotifications.REQUEST_ID, "Unlock your phone to approve proposal #$number.")
            return
        }
        // One answer per proposal: a second tap, or the other button, while it is on its way or after it went.
        notifications.proposals.ready()
        if (!notifications.proposals.claim(bot, number)) {
            // Answered already (here, in the chat, or in flight): say how, instead of leaving the tap without an answer.
            val said = notifications.proposals.outcome(bot, number)?.text ?: "Being answered."
            notifications.postProposalStatus(bot, number, "Proposal #$number", "Already answered: $said", done = true)
            return
        }
        val verb = if (approve) "Approval" else "Denial"
        notifications.postProposalStatus(bot, number, "Proposal #$number", "Sending ${verb.lowercase()}…")
        awaitConnection()
        val sent = scope.async {
            val printed = runCatching {
                // A cron run has no chat of its own: the bot's Bot Chat, where the proposals job delivers.
                val session = intent.getStringExtra(EXTRA_SESSION_ID) ?: bots.botChat(bot)?.openId ?: error("no chat")
                bots.runBotCommand(bot, session, ProposalMarker.command(approve, number))
            }
            if (printed.isSuccess) {
                // What the plugin answered, which may also be a refusal ("no such proposal"); else just the word.
                val said = printed.getOrNull().orEmpty().take(MAX_PROPOSAL_ANSWER).ifBlank { if (approve) "Approved" else "Denied" }
                notifications.proposals.settle(bot, number, approve, said)
                notifications.postProposalStatus(bot, number, "Proposal #$number", said, done = true)
            } else {
                notifications.proposals.release(bot, number)
                notifications.postProposalFailure(bot, number, "Couldn't send. Open Hikyaku to answer.")
            }
        }
        // The send outlives this receiver's few seconds; only the wait is capped.
        withTimeoutOrNull(RECEIVER_BUDGET_MS) { sent.await() }
    }

    private suspend fun answer(open: ChatSession?, requestId: String, result: (InputRequest) -> kotlinx.serialization.json.JsonObject?) {
        // The open chat's, else the watch's chat's (it has its own session).
        val session = listOfNotNull(open, relay.session.value).firstOrNull { chat -> chat.state.value.inputRequests.any { it.id == requestId } }
        val request = session?.state?.value?.inputRequests?.firstOrNull { it.id == requestId }
        if (session == null || request == null) {
            // A chat that isn't open but is watched; else answered elsewhere, or the turn is over.
            val watched = watcher.find(requestId)?.second
            val answer = watched?.let(result)
            if (watched == null || answer == null) {
                notifications.cancelRequest(requestId)
                return
            }
            awaitConnection()
            if (watcher.answer(requestId, answer)) {
                notifications.cancelRequest(requestId)
            } else {
                notifications.postFailure(requestId, ChatNotifications.REQUEST_ID, "Not connected to the gateway. Open Hikyaku to answer.")
            }
            return
        }
        val answer = result(request) ?: return
        awaitConnection()
        if (session.answer(request, answer)) {
            notifications.cancelRequest(requestId)
        } else {
            notifications.postFailure(requestId, ChatNotifications.REQUEST_ID, "Not connected to the gateway. Open Hikyaku to answer.")
        }
    }

    /** After a while in the background the socket may be down; give it one quick chance to come back. */
    private suspend fun awaitConnection() {
        if (connection.state.value is ConnectionState.Connected) return
        connection.retry()
        withTimeoutOrNull(RECONNECT_BUDGET_MS) { connection.state.first { it is ConnectionState.Connected } }
    }

    companion object {
        const val ACTION_APPROVE = "dev.hermeskotlin.action.APPROVE"
        const val ACTION_CLARIFY = "dev.hermeskotlin.action.CLARIFY"
        const val ACTION_REPLY = "dev.hermeskotlin.action.REPLY"
        const val ACTION_DISCONNECT = "dev.hermeskotlin.action.DISCONNECT"
        const val ACTION_BOT_REPLY = "dev.hermeskotlin.action.BOT_REPLY"
        const val ACTION_BOT_CLEARED = "dev.hermeskotlin.action.BOT_CLEARED"
        const val ACTION_ROOM_CLEARED = "dev.hermeskotlin.action.ROOM_CLEARED"
        const val ACTION_DECLINE_CALL = "dev.hermeskotlin.action.DECLINE_CALL"
        const val ACTION_PROPOSAL_YES = "dev.hermeskotlin.action.PROPOSAL_YES"
        const val ACTION_PROPOSAL_NO = "dev.hermeskotlin.action.PROPOSAL_NO"
        const val EXTRA_BOT = "bot"
        const val EXTRA_PROPOSAL = "proposal"

        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_SESSION_ID = "session_id"
        const val KEY_TEXT = "text"

        private const val RECONNECT_BUDGET_MS = 5_000L
        private const val RECEIVER_BUDGET_MS = 4_000L
        private const val MAX_PROPOSAL_ANSWER = 300
    }
}
