package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushLogTest {
    private val url = "https://gw.example"
    private val now = 2_000_000_000L

    private fun entry(id: String, at: Long = now, text: String = "hi") =
        PushLogEntry(id = id, type = PushMessage.BOT_MESSAGE, at = at, bot = "default", session = "20261027_121247_35c8cf", text = text)

    private fun message(type: String = PushMessage.BOT_MESSAGE, bot: String? = "default", session: String? = "20261027_121247_35c8cf", text: String? = "hello") =
        PushMessage(id = "m1", ts = now, type = type, bot = bot, session = session, text = text)

    @Test
    fun aBotMessageBecomesAnEntry() {
        val e = assertNotNull(PushLogEntry.from(message()))
        assertEquals("m1", e.id)
        assertEquals("hello", e.text)
    }

    @Test
    fun nudgesAndPingsAreNotLogged() {
        assertNull(PushLogEntry.from(message(type = PushMessage.PING)))
        assertNull(PushLogEntry.from(message(type = PushMessage.PHONE_REQUEST)))
        assertNull(PushLogEntry.from(message(type = PushMessage.CRON_RUN)))
    }

    @Test
    fun oddBotOrSessionNamesAreNotLogged() {
        assertNull(PushLogEntry.from(message(bot = "bad name!")))
        assertNull(PushLogEntry.from(message(bot = "")))
        assertNull(PushLogEntry.from(message(session = "has space")))
        assertNull(PushLogEntry.from(message(text = "   ")))
    }

    @Test
    fun aVeryLongTextIsCut() {
        val e = assertNotNull(PushLogEntry.from(message(text = "x".repeat(10_000))))
        assertEquals(PushLogStore.MAX_TEXT, e.text.length)
    }

    @Test
    fun theSameIdReplacesItsEarlierEntry() = runTest {
        val log = PushLogStore(InMemoryKeyValueStore())
        log.bind(url)
        log.record(url, entry("a", text = "first"), now)
        log.record(url, entry("a", text = "second"), now)
        assertEquals(listOf("second"), log.state.value.entries.map { it.text })
    }

    @Test
    fun sixHundredPushesKeepTheNewestFiveHundred() = runTest {
        val log = PushLogStore(InMemoryKeyValueStore())
        log.bind(url)
        (1..600).forEach { log.record(url, entry("id$it", at = now - 600 + it), now) }
        val ids = log.state.value.entries.map { it.id }
        assertEquals(500, ids.size)
        assertEquals("id600", ids.first())
        assertEquals("id101", ids.last())
    }

    @Test
    fun entriesOlderThanThirtyDaysAreDropped() {
        val old = entry("old", at = now - PushLogStore.MAX_AGE_SECONDS - 1)
        val fresh = entry("fresh", at = now - 10)
        assertEquals(listOf("fresh"), PushLogStore.trim(listOf(old, fresh), now).map { it.id })
    }

    @Test
    fun eachGatewayKeepsItsOwnLogAndReadMarks() = runTest {
        val kv = InMemoryKeyValueStore()
        val log = PushLogStore(kv)
        log.bind(url)
        log.record(url, entry("a"), now)
        log.record("https://other", entry("b"), now)
        assertEquals(listOf("a"), log.state.value.entries.map { it.id })
        log.markRead(url, "a")
        assertEquals(0, log.state.value.unread)
        // A new store over the same storage sees what was kept.
        val again = PushLogStore(kv)
        again.bind("https://other")
        assertEquals(listOf("b"), again.state.value.entries.map { it.id })
        assertTrue(again.state.value.unread == 1)
    }
}
