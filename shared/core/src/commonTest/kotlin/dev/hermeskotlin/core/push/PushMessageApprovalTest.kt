package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.HermesJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PushMessageApprovalTest {

    private fun parse(json: String) = HermesJson.decodeFromString(PushMessage.serializer(), json)

    @Test
    fun anApprovalFromPlugin080ParsesWithItsFields() {
        val message = parse(
            """{"id":"m1","ts":100,"type":"approval","bot":"default","label":"Shisha","session":"20261027_121247_35c8cf",
               "command":"rm -rf build","description":"recursive delete","request_id":"abcdef012345"}""",
        )
        assertEquals(PushMessage.APPROVAL, message.type)
        assertEquals("default", message.bot)
        assertEquals("Shisha", message.label)
        assertEquals("20261027_121247_35c8cf", message.session)
        assertEquals("rm -rf build", message.command)
        assertEquals("recursive delete", message.description)
        assertEquals(emptyList(), message.choices)
        assertNull(message.expires)
    }

    @Test
    fun anApprovalWithOnlyTheBotsNameStillParses() {
        // The plugin leaves the command and description out when Hermes can't redact them.
        val message = parse("""{"id":"m2","ts":100,"type":"approval","bot":"side"}""")
        assertEquals(PushMessage.APPROVAL, message.type)
        assertNull(message.command)
        assertNull(message.description)
    }
}
