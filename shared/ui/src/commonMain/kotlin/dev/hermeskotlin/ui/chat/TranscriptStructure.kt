package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.MESSAGE_AGENT_TOOL
import dev.hermeskotlin.core.chat.ToolActivity

/**
 * The dependencies of bot rails, generated-image echoes and unread placement, without growing prose.
 * Only the latest projection is retained. Rows still draw the original messages, including every delta.
 */
internal class TranscriptStructureCache {
    private var sources: List<ChatMessage> = emptyList()
    private var previous: List<ChatMessage> = emptyList()

    fun update(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages === sources) return previous
        val structure = messages.mapIndexed { index, message ->
            // Immutable, unchanged history needs neither new row objects nor another walk of its tools.
            if (sources.getOrNull(index) === message) previous[index] else when (message) {
                is ChatMessage.User -> ChatMessage.User(message.key, "", pending = message.pending, timestamp = message.timestamp)
                is ChatMessage.Assistant -> ChatMessage.Assistant(
                    message.key,
                    repliedTo = message.repliedTo,
                    timestamp = message.timestamp,
                    tools = message.tools.mapNotNull { tool ->
                        if (tool.name != MESSAGE_AGENT_TOOL && tool.generatedImage == null) null
                        else ToolActivity(
                            tool.id, tool.name,
                            input = tool.input.takeIf { tool.name == MESSAGE_AGENT_TOOL },
                            generatedImage = tool.generatedImage,
                        )
                    },
                )
                is ChatMessage.Event -> message
                is ChatMessage.Notice -> ChatMessage.Notice(message.key, "")
                is ChatMessage.Command -> ChatMessage.Command(message.key, "")
            }
        }
        if (structure != previous) previous = structure
        sources = messages
        return previous
    }
}

internal enum class TranscriptContentType { User, Assistant, Event, Notice, Command }

internal val ChatMessage.transcriptContentType: TranscriptContentType
    get() = when (this) {
        is ChatMessage.User -> TranscriptContentType.User
        is ChatMessage.Assistant -> TranscriptContentType.Assistant
        is ChatMessage.Event -> TranscriptContentType.Event
        is ChatMessage.Notice -> TranscriptContentType.Notice
        is ChatMessage.Command -> TranscriptContentType.Command
    }
