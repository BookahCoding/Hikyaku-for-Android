package dev.hermeskotlin.core.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CronReplyTest {
    private fun run(text: String, status: String = InboxRun.OK, label: String = "mail-watch") =
        InboxRun(bot = "default", job = "a5e117cbf8ac", run = "2026-11-01_17-16-21", label = label, status = status, text = text)

    @Test
    fun quotesTheFirstThreeNonBlankLines() {
        val text = CronReply.text(run("\n\nOne\nTwo\n\nThree\nFour"))
        assertEquals("Re: mail-watch · 2026-11-01 17:16\n> One\n> Two\n> Three\n> …\n\n", text)
    }

    @Test
    fun anEmptyBodyHasNoQuote() {
        assertEquals("Re: mail-watch · 2026-11-01 17:16\n\n", CronReply.text(run("")))
    }

    @Test
    fun aHugeBodyStaysShort() {
        val text = CronReply.text(run("line ".repeat(5000)))
        assertTrue(text.length < 400, "was ${text.length}")
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
