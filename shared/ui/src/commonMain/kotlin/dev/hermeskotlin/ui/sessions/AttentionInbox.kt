package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography

/** All chats counted by the badge, including Bot Chats hidden from the ordinary session list. */
@Composable
internal fun AttentionInbox(rows: List<OtherChat>, onBack: () -> Unit, onOpen: (OtherChat) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SubpageHeader("Needs you", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) item {
                Text("Nothing needs you right now.", style = Theme[typography][body], color = Theme[colors][textSecondary])
            }
            items(rows, key = { it.id }) { chat ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                        .clickable(role = Role.Button, onClickLabel = "Open ${chat.title}") { onOpen(chat) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(chat.title, style = Theme[typography][body], color = Theme[colors][text], maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val kind = if (chat.bot != null) "Bot chat · " else ""
                    Text(kind + (chat.waiting?.label ?: "New reply"), style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
                }
            }
        }
    }
}
