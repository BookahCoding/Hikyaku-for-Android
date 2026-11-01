package dev.hermeskotlin.core.chat

private const val MAX_LINE = 100
private const val PREVIEW_LINES = 3

private fun TranscriptEvent.Routine.nonBlankLines(): List<String> = body.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()

private fun String.cut(): String = if (length > MAX_LINE) take(MAX_LINE) + "…" else this

/** The start of a routine's output, shown on its card before it is opened. */
fun TranscriptEvent.Routine.preview(lines: Int = PREVIEW_LINES): String = nonBlankLines().take(lines).joinToString("\n") { it.cut() }

/** Whether the card has more than [preview] shows: lines left out, or a shown line cut short. */
val TranscriptEvent.Routine.hasMore: Boolean
    get() = nonBlankLines().let { all -> all.size > PREVIEW_LINES || all.any { it.length > MAX_LINE } }
