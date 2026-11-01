package dev.hermeskotlin.core.chat

private const val MAX_LINE = 100

private fun TranscriptEvent.Routine.nonBlankLines(): List<String> = body.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()

/** The start of a routine's output, shown on its card before it is opened. */
fun TranscriptEvent.Routine.preview(lines: Int = 3): String = nonBlankLines().take(lines).joinToString("\n") { it.take(MAX_LINE) }

/** Whether the card has more than [preview] shows. */
val TranscriptEvent.Routine.hasMore: Boolean get() = nonBlankLines().size > 3
