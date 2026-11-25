package dev.hermeskotlin.core.bots

/**
 * The machine markers the proposals plugin puts in its messages. `[proposal:12]` on the first line of the message that
 * asks about a proposal; `[proposal:12:approved]` (or `denied`, `expired`, `done`…) in a later message that says
 * how it ended. Buttons appear only when exactly one opening marker is there, because a button that answers the wrong
 * proposal is worse than no button. Nothing else in the text is read as a number.
 */
object ProposalMarker {

    private val MARKER = Regex("""\[proposal:\s*(\d{1,9})\s*]""", RegexOption.IGNORE_CASE)

    /** The closing form: a colon and a word after the number, so the opening regex never matches it. */
    private val CLOSING = Regex("""\[proposal:\s*(\d{1,9})\s*:\s*([A-Za-z]{2,20})\s*]""", RegexOption.IGNORE_CASE)

    private val TYPED = Regex("""^(yes|no)\s+(\d{1,9})$""", RegexOption.IGNORE_CASE)

    /** The proposal [text] asks about, or null when it has no marker, has two different ones, or the number isn't 1 or more. */
    fun numberIn(text: String): Int? {
        val numbers = MARKER.findAll(text).map { it.groupValues[1].toInt() }.toSet()
        return numbers.singleOrNull()?.takeIf { it >= 1 }
    }

    /** As [numberIn], but only the first line counts (the first that isn't blank): where the plugin writes it. */
    fun numberOnFirstLine(text: String): Int? = numberIn(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())

    /** How a proposal ended, as a later message said: [state] lower-cased. */
    data class Closed(val number: Int, val state: String)

    /** The closing markers in [text], in order; numbers below 1 are left out. */
    fun closings(text: String): List<Closed> = CLOSING.findAll(text)
        .map { Closed(it.groupValues[1].toInt(), it.groupValues[2].lowercase()) }
        .filter { it.number >= 1 }
        .toList()

    /** [text] without either kind of marker, for showing. */
    fun without(text: String): String = text.replace(MARKER, "").replace(CLOSING, "")
        .replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** What Approve or Deny sends: the plugin's own command, the same as typing it in the chat. */
    fun command(approve: Boolean, number: Int): String {
        require(number >= 1) { "A proposal number is 1 or more." }
        return "/proposal ${if (approve) "yes" else "no"} $number"
    }

    /** The argument of a typed `/proposal yes 12` (`yes 12`) as (approve, number), or null when it is anything else. */
    fun typedAnswer(arg: String): Pair<Boolean, Int>? {
        val match = TYPED.matchEntire(arg.trim()) ?: return null
        val number = match.groupValues[2].toInt().takeIf { it >= 1 } ?: return null
        return (match.groupValues[1].equals("yes", ignoreCase = true)) to number
    }
}
