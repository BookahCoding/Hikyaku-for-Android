package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.phone.CallClock
import dev.hermeskotlin.core.phone.CallOpenerPrefetch
import dev.hermeskotlin.core.phone.CallReason
import dev.hermeskotlin.core.phone.PreparedOpener
import dev.hermeskotlin.core.phone.spokenOpener
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import dev.hermeskotlin.core.voice.speechChunks
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The opening words of a call the agent placed: made while the phone rings, played once it's answered. The fake
 * gateway here is on real threads, so these wait for it in real time.
 */
@OptIn(ExperimentalAtomicApi::class)
class CallOpeningTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val reason = CallReason("The deploy failed.")

    private class Requests {
        private val seen = AtomicReference(emptyList<HttpRequestData>())

        fun add(request: HttpRequestData) {
            while (true) {
                val now = seen.load()
                if (seen.compareAndSet(now, now + request)) return
            }
        }

        fun to(path: String) = seen.load().filter { it.url.encodedPath == path }
    }

    /** What was played, by whom and when. */
    private class Player : SpeechPlayer {
        val played = AtomicReference(emptyList<Int>())
        override suspend fun play(audio: SpokenAudio) {
            while (true) {
                val now = played.load()
                if (played.compareAndSet(now, now + audio.bytes.size)) return
            }
        }
    }

    private class Recorder : VoiceRecorder {
        var recordings = 0
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            recordings++
            onSpeech()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", true)
        }

        override fun finish() = Unit
    }

    private class Env(
        val requests: Requests,
        val prefetch: CallOpenerPrefetch,
        val voice: VoiceController,
        val player: Player,
        val recorder: Recorder,
        val session: ChatSession,
        val profiles: ProfileStore,
    )

    /**
     * [gate]: the first synthesis doesn't answer until it's completed. [failFirst]: the first synthesis fails. The
     * person says "Stop." once the opening has been spoken, which ends the call.
     */
    private suspend fun TestScope.env(gate: CompletableDeferred<Unit>? = null, failFirst: Boolean = false): Env {
        val requests = Requests()
        val speaks = AtomicInt(0)
        val audio = AudioApi(
            createHttpClient(
                MockEngine { request ->
                    requests.add(request)
                    when (request.url.encodedPath) {
                        "/api/audio/speak" -> {
                            val n = speaks.incrementAndFetch()
                            if (n == 1) gate?.await()
                            if (n == 1 && failFirst) {
                                respond("""{"ok":false,"error":"TTS is down."}""", HttpStatusCode.InternalServerError, json)
                            } else {
                                // The size says which synthesis it was.
                                respond("""{"data_url":"data:audio/mpeg;base64,${"AQID".repeat(n)}","mime_type":"audio/mpeg"}""", HttpStatusCode.OK, json)
                            }
                        }
                        "/api/audio/transcribe" -> respond("""{"ok":true,"transcript":"Stop."}""", HttpStatusCode.OK, json)
                        else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                    }
                },
                PersistentCookiesStorage(InMemoryKeyValueStore()),
            ),
        )
        val gateways = GatewayRepository(InMemoryKeyValueStore())
        gateways.save(SavedGateway("https://hermes.example.ts.net"))
        val profiles = ProfileStore(InMemoryKeyValueStore())
        val real = CoroutineScope(SupervisorJob(backgroundScope.coroutineContext[Job]) + Dispatchers.Default)
        val player = Player()
        val recorder = Recorder()
        val voice = VoiceController(audio, recorder, player, backgroundScope, backgroundScope)
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val http = createHttpClient(MockEngine { error("no REST in this test") }, cookies)
        val connection = GatewayConnection(AuthApi(http, cookies), { _, _ -> error("not connecting") }, backgroundScope)
        val session = ChatSession(gateway, null, null, connection, SessionsApi(http), backgroundScope)
        return Env(requests, CallOpenerPrefetch(audio, gateways, profiles, real), voice, player, recorder, session, profiles)
    }

    private suspend fun settle(until: () -> Boolean) {
        withContext(Dispatchers.Default) { repeat(2_000) { if (!until()) delay(5) } }
        assertTrue(until(), "the fake gateway never got there")
    }

    /** Answers: starts the chat the way the call screen does, and waits for the opening and the call's end. */
    private suspend fun answer(e: Env, prepared: PreparedOpener?, clock: CallClock? = null, profile: String? = null) {
        e.voice.startChat(e.session, gateway, profile, opener = reason, prepared = prepared, clock = clock)
        withContext(Dispatchers.Default) { e.voice.chat.first { it.phase == VoicePhase.Off } }
    }

    @Test
    fun aClipMadeWhileItRangPlaysOnTheAnswerWithoutAskingTheGatewayAgain() = runTest {
        val e = env()
        val prepared = assertNotNull(e.prefetch.prepare(reason))
        settle { e.requests.to("/api/audio/speak").size == 1 }
        // Ringing, not answered: nothing has played and nobody listened.
        assertEquals(emptyList(), e.player.played.load())
        assertEquals(0, e.recorder.recordings)
        val clock = CallClock().also { it.mark(CallClock.ANSWERED) }

        answer(e, prepared, clock)

        assertEquals(1, e.requests.to("/api/audio/speak").size, "the opening was synthesized twice")
        assertEquals(1, e.player.played.load().size, "the opening played more or less than once")
        assertNotNull(clock.answerToAudioMs())
        assertTrue(clock.summary().contains(CallClock.OPENER_CLIP_PREPARED))
    }

    @Test
    fun answeringBeforeItIsReadyPlaysTheSameClipWhenItArrives() = runTest {
        val gate = CompletableDeferred<Unit>()
        val e = env(gate = gate)
        val prepared = assertNotNull(e.prefetch.prepare(reason))
        settle { e.requests.to("/api/audio/speak").size == 1 }

        e.voice.startChat(e.session, gateway, null, opener = reason, prepared = prepared)
        // Waiting on it: still one request, nothing played.
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(1, e.requests.to("/api/audio/speak").size)
        assertEquals(emptyList(), e.player.played.load())
        gate.complete(Unit)
        withContext(Dispatchers.Default) { e.voice.chat.first { it.phase == VoicePhase.Off } }

        assertEquals(1, e.requests.to("/api/audio/speak").size, "a second synthesis was asked for")
        assertEquals(1, e.player.played.load().size)
    }

    @Test
    fun aPreparationThatFailedFallsBackToTheUsualWay() = runTest {
        val e = env(failFirst = true)
        val prepared = assertNotNull(e.prefetch.prepare(reason))
        settle { e.requests.to("/api/audio/speak").size == 1 }

        answer(e, prepared)

        // The prepared one failed; the call asked for itself, once, and that one played.
        assertEquals(2, e.requests.to("/api/audio/speak").size)
        assertEquals(1, e.player.played.load().size)
        assertEquals(listOf(6), e.player.played.load(), "it played the fallback's clip")
    }

    @Test
    fun aCancelledPreparationIsNeverPlayedAndTheCallAsksForItself() = runTest {
        val e = env()
        val prepared = assertNotNull(e.prefetch.prepare(reason))
        settle { e.requests.to("/api/audio/speak").size == 1 }
        prepared.cancel()

        answer(e, prepared)

        assertEquals(2, e.requests.to("/api/audio/speak").size)
        assertEquals(listOf(6), e.player.played.load(), "the cancelled clip played")
    }

    @Test
    fun aCallWithoutAPreparationIsAnsweredAsBefore() = runTest {
        val e = env()

        answer(e, prepared = null)

        assertEquals(1, e.requests.to("/api/audio/speak").size)
        assertEquals(1, e.player.played.load().size)
    }

    @Test
    fun aClipMadeForAnotherProfileIsNeverPlayedForThisOne() = runTest {
        val e = env()
        e.profiles.set(gateway, "other")
        val prepared = assertNotNull(e.prefetch.prepare(reason))
        settle { e.requests.to("/api/audio/speak").size == 1 }

        // The call goes on in the default profile (the app moved to it meanwhile).
        answer(e, prepared, profile = null)

        assertEquals("other", e.requests.to("/api/audio/speak").first().url.parameters["profile"])
        assertNull(e.requests.to("/api/audio/speak").last().url.parameters["profile"], "the call spoke with another profile's voice")
        assertEquals(listOf(6), e.player.played.load())
    }

    @Test
    fun aClipMadeForAnotherCallsReasonIsNeverPlayed() = runTest {
        val e = env()
        val prepared = assertNotNull(e.prefetch.prepare(CallReason("Another reason entirely.")))
        settle { e.requests.to("/api/audio/speak").size == 1 }

        // This call opens with its own reason, not the one the clip was made for.
        answer(e, prepared)

        assertEquals(2, e.requests.to("/api/audio/speak").size)
        assertEquals(listOf(6), e.player.played.load())
    }

    @Test
    fun onlyTheFirstPieceIsPreparedAndTheRestFollowsAsUsual() = runTest {
        val long = CallReason("The deploy of the website failed after the migration step stopped halfway. " +
            "The database is still on the old version, and the old pages are being served. Nothing was lost. " +
            "I rolled the website back and I'm waiting for your word before I try the migration again tonight.")
        val pieces = speechChunks(long.spokenOpener())
        assertTrue(pieces.size >= 2, "the test needs an opening of several pieces")
        val e = env()
        val prepared = assertNotNull(e.prefetch.prepare(long))
        settle { e.requests.to("/api/audio/speak").size == 1 }

        e.voice.startChat(e.session, gateway, null, opener = long, prepared = prepared)
        withContext(Dispatchers.Default) { e.voice.chat.first { it.phase == VoicePhase.Off } }

        // The first piece once (from the ring), every other piece once, each played once and in order.
        assertEquals(pieces.size, e.requests.to("/api/audio/speak").size)
        assertEquals(pieces.size, e.player.played.load().size)
    }
}
