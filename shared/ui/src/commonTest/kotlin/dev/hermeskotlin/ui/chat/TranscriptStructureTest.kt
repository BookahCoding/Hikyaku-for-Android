package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.GeneratedImage
import dev.hermeskotlin.core.chat.MESSAGE_AGENT_TOOL
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.core.chat.unservableEchoes
import dev.hermeskotlin.ui.bots.BotFaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TranscriptStructureTest {
    @Test
    fun streamingTextReasoningAndToolOutputReuseTheStructure() {
        val cache = TranscriptStructureCache()
        val start = ChatMessage.Assistant("a", text = "first", streaming = true,
            tools = listOf(ToolActivity("t", "exec", output = "one")))
        val before = cache.update(listOf(ChatMessage.User("u", "prompt"), start))
        val after = cache.update(listOf(ChatMessage.User("u", "prompt"), start.copy(
            text = "first final", reasoning = "thinking", tools = listOf(start.tools.single().copy(output = "two")))))
        assertSame(before, after)
    }

    @Test
    fun completionTimestampAndPendingPromptImmediatelyChangeUnreadStructure() {
        val cache = TranscriptStructureCache()
        val live = cache.update(listOf(ChatMessage.Assistant("a", streaming = true)))
        assertEquals("a", firstUnreadKey(live, 100.0))
        val complete = cache.update(listOf(ChatMessage.Assistant("a", text = "done", timestamp = 90.0)))
        assertNotSame(live, complete)
        assertEquals(null, firstUnreadKey(complete, 100.0))
        val pending = cache.update(listOf(ChatMessage.User("u", "sent", pending = true)))
        assertEquals(true, (pending.single() as ChatMessage.User).pending)
        assertNotSame(pending, cache.update(listOf(ChatMessage.User("u", "sent", pending = false))))
    }

    @Test
    fun generatedMediaAndBotTargetStayLiveWhileTheirTextChanges() {
        val cache = TranscriptStructureCache()
        val start = ChatMessage.Assistant("a", streaming = true)
        val before = cache.update(listOf(start))
        val image = GeneratedImage("/served.png", listOf("/served.png", "/sandbox.png"))
        val changed = start.copy(text = "![image](/sandbox.png)", tools = listOf(
            ToolActivity("image", "image_generate", generatedImage = image),
            ToolActivity("bot", MESSAGE_AGENT_TOOL, input = """{"target":"scribe"}"""),
        ))
        val after = cache.update(listOf(changed))
        assertNotSame(before, after)
        assertEquals(listOf(GeneratedImage("/served.png", listOf("/sandbox.png"))), unservableEchoes(after))
        assertEquals("scribe", exchangePartner(after.single(), BotFaces()))
        assertSame(after, cache.update(listOf(changed.copy(text = "finished text"))))
        val retargeted = changed.copy(tools = changed.tools.dropLast(1) + changed.tools.last().copy(input = """{"target":"writer"}"""))
        assertEquals("writer", exchangePartner(cache.update(listOf(retargeted)).single(), BotFaces()))
    }

    @Test
    fun replacementReorderingAndOlderPagesCannotReuseWrongRows() {
        val cache = TranscriptStructureCache()
        val initial = cache.update(listOf(ChatMessage.User("u", "prompt"), ChatMessage.Assistant("a")))
        val reordered = cache.update(listOf(ChatMessage.Assistant("a"), ChatMessage.User("u", "prompt")))
        assertEquals(listOf("a", "u"), reordered.map { it.key })
        assertNotSame(initial, reordered)
        val older = cache.update(listOf(ChatMessage.Notice("old", "history")) + reordered)
        assertEquals(listOf("old", "a", "u"), older.map { it.key })
        assertEquals(listOf("new"), cache.update(listOf(ChatMessage.User("new", "replacement"))).map { it.key })
    }
}
