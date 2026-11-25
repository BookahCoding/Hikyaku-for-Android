package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BotsApiTest {

    /** Every `profiles.configure` the gateway was sent. */
    private val configured = mutableListOf<JsonObject>()

    /** Answers every call's params with [answer]. */
    private suspend fun api(scope: CoroutineScope, answer: (JsonObject) -> String): BotsApi {
        val gateway = FakeGateway(scope, reconnects = false)
        gateway.answer = { call ->
            if (call.method == "profiles.configure") configured += call.params
            answer(call.params)
        }
        return BotsApi(gateway.start())
    }

    @Test
    fun aModelIsSetOnTheBotsProfile() = runTest {
        val api = api(backgroundScope) { """{"ok":true,"applied":{"model":true}}""" }

        assertNull(api.setModel("scribe", "claude-sonnet-5-5", "anthropic"))

        val sent = configured.single()
        assertEquals("scribe", sent["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("claude-sonnet-5-5", sent["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("anthropic", sent["provider"]?.jsonPrimitive?.contentOrNull)
        assertNull(sent["confirm_expensive_model"])
    }

    @Test
    fun aGuardedModelIsAskedAboutAndSentAgainOnceConfirmed() = runTest {
        val api = api(backgroundScope) { params ->
            if (params["confirm_expensive_model"] == null) {
                """{"ok":true,"applied":{},"confirm_required":true,"confirm_message":"This model costs $15 per million tokens."}"""
            } else {
                """{"ok":true,"applied":{"model":true}}"""
            }
        }

        assertEquals("This model costs $15 per million tokens.", api.setModel("scribe", "big", "openrouter"))
        assertNull(api.setModel("scribe", "big", "openrouter", confirmed = true))

        assertEquals("true", configured.last()["confirm_expensive_model"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aModelTheGatewayDidntSaveIsAnError() = runTest {
        val api = api(backgroundScope) { """{"ok":false,"applied":{"model":false}}""" }

        assertFailsWith<RpcException> { api.setModel("scribe", "x", "y") }
    }

    /** Every method the gateway was sent, with its params, for the command tests. */
    private val calls = mutableListOf<Pair<String, JsonObject>>()

    /** The connection's own handshake call isn't part of what a command does. */
    private fun record(call: FakeGateway.Call) {
        if (call.method != "client.capabilities") calls += call.method to call.params
    }

    private suspend fun commandApi(scope: CoroutineScope, reply: (String, JsonObject) -> String): BotsApi {
        val gateway = FakeGateway(scope, reconnects = false)
        gateway.answer = { call ->
            record(call)
            when (call.method) {
                "session.resume" -> """{"session_id":"runtime-1"}"""
                else -> reply(call.method, call.params)
            }
        }
        return BotsApi(gateway.start())
    }

    @Test
    fun aBotCommandRunsAsASlashCommandInTheResumedChat() = runTest {
        val api = commandApi(backgroundScope) { _, _ -> """{"type":"exec","output":"Proposal 12 approved.
"}""" }

        assertEquals("Proposal 12 approved.", api.runBotCommand("default", "stored-9", "/proposal yes 12"))

        assertEquals(listOf("session.resume", "slash.exec"), calls.map { it.first })
        assertEquals("stored-9", calls[0].second["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("default", calls[0].second["profile"]?.jsonPrimitive?.contentOrNull)
        assertEquals("runtime-1", calls[1].second["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/proposal yes 12", calls[1].second["command"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aBotCommandIsNeverSubmittedAsAPrompt() = runTest {
        val api = commandApi(backgroundScope) { _, _ -> """{"type":"plugin","output":"No such proposal."}""" }

        assertEquals("No such proposal.", api.runBotCommand("default", "stored-9", "/proposal no 999999"))

        assertEquals(false, calls.any { it.first == "prompt.submit" })
    }

    @Test
    fun whatTheSlashWorkerWontTakeGoesToCommandDispatch() = runTest {
        val api = commandApi(backgroundScope) { method, _ ->
            if (method == "slash.exec") "error:4018" else """{"type":"plugin","output":"ok"}"""
        }

        assertEquals("ok", api.runBotCommand("default", "stored-9", "/proposal yes 12"))

        assertEquals(listOf("session.resume", "slash.exec", "command.dispatch"), calls.map { it.first })
        assertEquals("proposal", calls[2].second["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("yes 12", calls[2].second["arg"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aCommandThatBothPathsRefuseFails() = runTest {
        val api = commandApi(backgroundScope) { _, _ -> "error:4018" }

        assertFailsWith<RpcException> { api.runBotCommand("default", "stored-9", "/proposal yes 12") }
        assertEquals(false, calls.any { it.first == "prompt.submit" })
    }

    @Test
    fun anAnswerThatWouldBecomeAPromptIsRefused() = runTest {
        // A skill or an alias comes back as text to send on to the model: not something to approve with.
        val skill = commandApi(backgroundScope) { _, _ -> """{"type":"skill","message":"Do the thing","display":"/proposal"}""" }
        assertFailsWith<RpcException> { skill.runBotCommand("default", "stored-9", "/proposal yes 12") }
        val send = commandApi(backgroundScope) { _, _ -> """{"type":"send","message":"yes 12"}""" }
        assertFailsWith<RpcException> { send.runBotCommand("default", "stored-9", "/proposal yes 12") }
        assertEquals(false, calls.any { it.first == "prompt.submit" })
    }

    @Test
    fun aChatThatCannotBeResumedRunsNothing() = runTest {
        val gateway = FakeGateway(backgroundScope, reconnects = false)
        gateway.answer = { call ->
            record(call)
            if (call.method == "session.resume") "{}" else """{"type":"exec","output":"ran"}"""
        }
        val api = BotsApi(gateway.start())

        assertFailsWith<RpcException> { api.runBotCommand("default", "stored-9", "/proposal yes 12") }
        assertEquals(listOf("session.resume"), calls.map { it.first })
    }

    @Test
    fun onlyASlashCommandIsRun() = runTest {
        val api = commandApi(backgroundScope) { _, _ -> """{"type":"exec","output":"x"}""" }

        assertFailsWith<IllegalArgumentException> { api.runBotCommand("default", "stored-9", "proposal yes 12") }
        assertEquals(emptyList(), calls)
    }
}
