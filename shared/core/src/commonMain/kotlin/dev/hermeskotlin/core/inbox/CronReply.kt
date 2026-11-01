package dev.hermeskotlin.core.inbox

import dev.hermeskotlin.core.chat.ComposeDraft

/** What a reply to a cron run starts with: which run it is about, and the start of what it said, as plain text. */
object CronReply {
    /** How much of the run's text is quoted, counting each quoted line's `> ` and line break. */
    private const val QUOTE_BUDGET = 1500
    private const val QUOTE_OVERHEAD = 3
    private const val MAX_TITLE = 64

    fun text(run: InboxRun): String {
        val head = "Re: ${plainTitle(run.title)}${if (run.failed) " (failed)" else ""} · ${whenLabel(run.run)}"
        val lines = run.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val quote = mutableListOf<String>()
        var left = QUOTE_BUDGET
        var cut = false
        for (line in lines) {
            val room = left - QUOTE_OVERHEAD
            if (room <= 0) { cut = true; break }
            if (line.length > room) {
                // Never split a surrogate pair.
                val end = if (line[room - 1].isHighSurrogate()) room - 1 else room
                quote += "> ${line.take(end)}"
                cut = true
                break
            }
            quote += "> $line"
            left -= line.length + QUOTE_OVERHEAD
        }
        if (cut) quote += "> …"
        return (listOf(head) + quote).joinToString("\n") + "\n\n"
    }

    /** The job's name on one line (control characters and runs of whitespace become one space), cut to [MAX_TITLE]. */
    private fun plainTitle(title: String): String =
        title.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("")
            .replace(Regex(" {2,}"), " ").trim().take(MAX_TITLE).trimEnd()

    fun draft(run: InboxRun): ComposeDraft = ComposeDraft(text = text(run))

    /** `2026-11-01_17-16-21` → `2026-11-01 17:16`; anything else as it came. */
    private fun whenLabel(run: String): String {
        val m = Regex("""^(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-\d{2}$""").matchEntire(run) ?: return run
        return "${m.groupValues[1]} ${m.groupValues[2]}:${m.groupValues[3]}"
    }
}
