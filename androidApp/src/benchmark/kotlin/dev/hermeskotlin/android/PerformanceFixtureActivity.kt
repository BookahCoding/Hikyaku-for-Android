package dev.hermeskotlin.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.hermeskotlin.core.chat.Attachment
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.ui.preview.PerformanceChatPreview
import kotlinx.coroutines.delay

/** Present only in benchmark/profile variants. No repositories, credentials or gateway data. */
class PerformanceFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "long"
        val dark = intent.getBooleanExtra("dark", true)
        setContent {
            var session by remember { mutableIntStateOf(0) }
            var operation by remember { mutableIntStateOf(0) }
            var status by remember { mutableStateOf("Ready") }
            var state by remember { mutableStateOf(fixtureState(session, scenario)) }
            LaunchedEffect(operation) {
                if (operation == 0) return@LaunchedEffect
                status = "Streaming"
                val correctness = scenario == "correctness"
                val source = if (correctness) RACE_SOURCE else richReply(99)
                val history = if (correctness) emptyList() else fixtureMessages(session).dropLast(1)
                val steps = if (correctness) 40 else 240
                repeat(steps) { step ->
                    state = state.copy(running = true, messages = history + ChatMessage.Assistant(
                        "live", source.take(source.length * (step + 1) / steps), streaming = true,
                    ))
                    delay(if (correctness) 2L else 20L)
                }
                state = state.copy(running = false, messages = history + ChatMessage.Assistant(
                    "live", if (correctness) FINAL_REPLY else source, outcome = TurnOutcome.Complete,
                ))
                status = "Complete"
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(Color.White)) {
                    BasicText(status, Modifier.padding(8.dp))
                    when (scenario) {
                        "switch" -> BasicText("Next session", Modifier.clickable {
                            session = (session + 1) % 3
                            state = fixtureState(session, scenario)
                        }.padding(8.dp))
                        "stream", "correctness" -> BasicText("Start stream", Modifier.clickable {
                            operation++
                        }.padding(8.dp))
                    }
                    if (scenario == "correctness") BasicText("Replace source", Modifier.clickable {
                        // Two different long sources on consecutive frames, while async work may be pending.
                        state = state.copy(messages = listOf(ChatMessage.Assistant("live", RACE_SOURCE, streaming = true)))
                        status = "Replacing"
                    }.padding(8.dp))
                }
                LaunchedEffect(status) {
                    if (status == "Replacing") {
                        delay(16)
                        state = state.copy(running = false, messages = listOf(ChatMessage.Assistant(
                            "live", REPLACEMENT_REPLY, outcome = TurnOutcome.Complete,
                        )))
                        status = "Replaced"
                    }
                }
                Box(Modifier.weight(1f)) { PerformanceChatPreview(state, dark) }
            }
        }
    }
}

private fun fixtureState(session: Int, scenario: String) = ChatState(
    storedSessionId = "synthetic-$session", title = "Synthetic session $session", historyLoaded = true,
    attachment = Attachment.Attached("synthetic"),
    messages = if (scenario == "correctness") listOf(ChatMessage.Assistant("live", "Waiting for synthetic reply."))
    else fixtureMessages(session),
)

private fun fixtureMessages(session: Int): List<ChatMessage> = (0 until 60).flatMap { turn ->
    listOf(
        ChatMessage.User("$session-u$turn", "Synthetic question $turn in session $session: explain this sample."),
        ChatMessage.Assistant("$session-a$turn", if (turn % 10 == 9) richReply(turn)
            else "Synthetic answer $turn. " + "A local sample paragraph for scrolling and session changes. ".repeat(5),
            outcome = TurnOutcome.Complete),
    )
}

private fun richReply(turn: Int): String = buildString {
    append("## Synthetic report $turn\n\n")
    repeat(8) { section ->
        append("### Section $section\n\n")
        append("A **local sample** with prose, a list and a small formula. ".repeat(4))
        append("\n\n- Check the sample\n- Keep the result repeatable\n\n")
        append("```kotlin\nfun sample$section(value: Int): Int {\n    return value * ${section + 1}\n}\n```\n\n")
        append("The sample equation is ${'$'}x^2 + y^2 = z^2${'$'}.\n\n")
    }
}

// Deliberately one plain paragraph >2,048 chars: UiAutomator can compare the entire rendered text.
private val FINAL_REPLY = "FINAL_REPLY: " + "The complete synthetic response is exact and current. ".repeat(45) + "FINAL_END."
private val REPLACEMENT_REPLY = "REPLACEMENT_REPLY: " + "The replacement contains only the new synthetic source. ".repeat(45) + "REPLACEMENT_END."
private val RACE_SOURCE = "OBSOLETE_SOURCE: " + ("Old synthetic content ${'$'}x^2${'$'} must disappear.\n\n").repeat(120)
