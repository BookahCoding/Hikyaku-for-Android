package dev.hermeskotlin.core.inbox

import dev.hermeskotlin.core.chat.ComposeDraft

/** What a reply to a cron run starts with: which run it is about, and the start of what it said, as plain text. */
object CronReply {
    private const val QUOTED_LINES = 3
    private const val MAX_LINE = 120

    fun text(run: InboxRun): String {
        val head = "Re: ${run.title}${if (run.failed) " (failed)" else ""} · ${whenLabel(run.run)}"
        val lines = run.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val quote = lines.take(QUOTED_LINES).map { "> ${it.take(MAX_LINE)}" } + if (lines.size > QUOTED_LINES) listOf("> …") else emptyList()
        return (listOf(head) + quote).joinToString("\n") + "\n\n"
    }

    fun draft(run: InboxRun): ComposeDraft = ComposeDraft(text = text(run))

    /** `2026-11-01_17-16-21` → `2026-11-01 17:16`; anything else as it came. */
    private fun whenLabel(run: String): String {
        val m = Regex("""^(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-\d{2}$""").matchEntire(run) ?: return run
        return "${m.groupValues[1]} ${m.groupValues[2]}:${m.groupValues[3]}"
    }
}
