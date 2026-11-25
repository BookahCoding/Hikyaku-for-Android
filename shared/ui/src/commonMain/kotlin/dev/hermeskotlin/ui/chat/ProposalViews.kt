package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.bots.ProposalChat
import dev.hermeskotlin.core.bots.ProposalView
import dev.hermeskotlin.core.bots.ProposalsState
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/** What the chat needs to answer proposals in place: who the bot is, what's answered, and how to answer. */
interface ProposalInline {
    /** The name proposals are filed under: the chat's profile. */
    val bot: String

    /** Answered and being-answered proposals, shared with the notifications' buttons. */
    val state: StateFlow<ProposalsState>

    /** Why the last try for a proposal failed, by `bot/number`. */
    val errors: StateFlow<Map<String, String>>

    /** Approve ([approve] true) or Deny proposal [number]; Approve needs the phone unlocked. */
    fun answer(number: Int, approve: Boolean)
}

/**
 * Under a message that asks about a proposal: Approve and Deny, then, once answered here or elsewhere, what came of it
 * in their place. Nothing for a message that doesn't ask. [later] are the messages after it, where an ending may be said.
 */
@Composable
internal fun ProposalButtons(message: ChatMessage, later: List<ChatMessage>, inline: ProposalInline?) {
    if (inline == null) return
    val number = remember(message) { ProposalChat.numberOf(message) } ?: return
    val state by inline.state.collectAsState()
    val errors by inline.errors.collectAsState()
    val now = remember(message) { Clock.System.now().toEpochMilliseconds() / 1000.0 }
    val view = ProposalChat.view(inline.bot, number, ProposalChat.timestampOf(message), now, later, state)
    Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (view) {
            ProposalView.Ask -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("Approve", onClick = { inline.answer(number, true) }, size = ButtonSize.Small)
                    Button("Deny", onClick = { inline.answer(number, false) }, size = ButtonSize.Small, variant = ButtonVariant.Outline)
                }
                errors["${inline.bot}/$number"]?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger]) }
            }
            ProposalView.Sending -> Text("Sending…", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            is ProposalView.Answered -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when (view.approve) {
                    true -> Lucide.CircleCheck to Theme[colors][success]
                    false -> Lucide.X to Theme[colors][textSecondary]
                    null -> Lucide.CircleAlert to Theme[colors][textTertiary]
                }
                UnstyledIcon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text(view.text, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
            }
        }
    }
}
