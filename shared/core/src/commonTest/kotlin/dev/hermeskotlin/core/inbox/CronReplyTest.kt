package dev.hermeskotlin.core.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CronReplyTest {
    private fun run(text: String, status: String = InboxRun.OK, label: String = "mail-watch") =
        InboxRun(bot = "default", job = "a5e117cbf8ac", run = "2026-11-01_17-16-21", label = label, status = status, text = text)

    private fun quoted(text: String) = text.lines().filter { it.startsWith("> ") }

    @Test
    fun aShortBodyIsQuotedWhole() {
        val text = CronReply.text(run("\n\nOne\nTwo\n\nThree\nFour"))
        assertEquals("Re: mail-watch · 2026-11-01 17:16\n> One\n> Two\n> Three\n> Four\n\n", text)
    }

    @Test
    fun aBodyUnderTheLimitIsQuotedWholeWithoutAnEllipsis() {
        val body = "x".repeat(1400)
        val text = CronReply.text(run(body))
        assertEquals("Re: mail-watch · 2026-11-01 17:16\n> $body\n\n", text)
    }

    @Test
    fun aBodyOverTheLimitIsCutWithAnEllipsis() {
        val text = CronReply.text(run("y".repeat(3000)))
        assertTrue(text.endsWith("\n> …\n\n"), text.takeLast(20))
        val first = quoted(text).first()
        assertTrue(first.length in 1000..1510, "was ${first.length}")
    }

    @Test
    fun aHugeBodyStaysUnderTheCap() {
        val text = CronReply.text(run("line ".repeat(4000)))
        assertTrue(text.length < 1700, "was ${text.length}")
        val lines = CronReply.text(run((1..5000).joinToString("\n") { "a" }))
        assertTrue(lines.length < 1700, "was ${lines.length}")
    }

    @Test
    fun everyQuotedLineKeepsItsPrefix() {
        val body = (1..100).joinToString("\n\n") { "row $it of the report" }
        val text = CronReply.text(run(body))
        val rows = text.lines().drop(1).filter { it.isNotEmpty() }
        assertTrue(rows.size > 10)
        assertTrue(rows.all { it.startsWith("> ") }, rows.firstOrNull { !it.startsWith("> ") }.toString())
        assertEquals("> …", rows.last())
    }

    @Test
    fun aTitleWithANewlineCannotEscapeTheQuote() {
        val text = CronReply.text(run("body", label = "evil\n\u0007\r\nsneaky  title"))
        assertEquals("Re: evil sneaky title · 2026-11-01 17:16", text.lines().first())
        assertEquals(1, text.lines().count { it.startsWith("Re:") })
    }

    @Test
    fun aLongTitleIsCapped() {
        val head = CronReply.text(run("body", label = "t".repeat(300))).lines().first()
        assertEquals("Re: ${"t".repeat(64)} · 2026-11-01 17:16", head)
    }

    @Test
    fun anEmptyBodyHasNoQuote() {
        assertEquals("Re: mail-watch · 2026-11-01 17:16\n\n", CronReply.text(run("")))
    }

    @Test
    fun aFailedRunSaysSo() {
        assertTrue(CronReply.text(run("boom", status = InboxRun.FAILED)).startsWith("Re: mail-watch (failed) · "))
    }

    @Test
    fun markupInTheNameStaysPlainText() {
        val text = CronReply.text(run("x", label = "**bold** <b>"))
        assertTrue(text.startsWith("Re: **bold** <b> · "))
    }

    @Test
    fun aRunNameThatIsNotATimestampIsShownAsIs() {
        val odd = InboxRun(job = "j", run = "weird", label = "j")
        assertTrue(CronReply.text(odd).startsWith("Re: j · weird"))
    }
}
