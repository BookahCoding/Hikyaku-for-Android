package dev.hermeskotlin.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.push.PushLogEntry
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import dev.hermeskotlin.core.inbox.keepLineBreaks
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.ui.components.messageTime
import dev.hermeskotlin.ui.components.uses24HourClock
import dev.hermeskotlin.ui.sessions.SubpageHeader

/** One pushed message in full, as Markdown, with a way into the chat it came from when it has one. */
@Composable
internal fun MessageView(entry: PushLogEntry, name: String, onBack: () -> Unit, onOpenChat: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SubpageHeader(name, onBack = onBack, subtitle = messageTime(entry.at.toDouble(), uses24HourClock()))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            MarkdownText(remember(entry.text) { keepLineBreaks(entry.text) })
        }
        if (entry.bot != null) {
            // Bottom padding leaves room for the sidebar's New chat button, which lies over the page's foot.
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
                Button("Open chat", onClick = onOpenChat, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Secondary, size = ButtonSize.Medium)
            }
        }
    }
}

/** The messages the agents pushed to this phone, newest first; tapping one reads it in full. */
@Composable
internal fun MessageLogView(
    entries: List<PushLogEntry>,
    read: Set<String>,
    nameOf: (PushLogEntry) -> String,
    onOpen: (PushLogEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            "Messages your agents push to this phone appear here, so a notification can always be found again.",
            style = Theme[typography][bodySmall],
            color = Theme[colors][textTertiary],
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(entries, key = { it.id }) { entry ->
            val unread = entry.id !in read
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Read ${nameOf(entry)}") { onOpen(entry) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    nameOf(entry),
                    style = Theme[typography][bodySmall],
                    color = if (unread) Theme[colors][text] else Theme[colors][textSecondary],
                )
                Text(
                    entry.text,
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
