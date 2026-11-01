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

/** The messages the agents pushed to this phone, newest first; one with a chat opens it, and any shows its text. */
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
                    .clickable(enabled = entry.bot != null) { onOpen(entry) }
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
