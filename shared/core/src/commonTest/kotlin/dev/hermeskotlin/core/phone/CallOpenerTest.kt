package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.speechChunks
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class CallOpenerTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val reason = CallReason("The deploy failed.")

    /** What the fake gateway was asked; its engine adds to it from another thread. */
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

    private class Fixture(
        val requests: Requests,
        val prefetch: CallOpenerPrefetch,
        val profiles: ProfileStore,
    )

    /**
     * [speakGate]: the synthesis doesn't answer until it's completed. [speakStatus]: what it answers.
     */
    private suspend fun TestScope.fixture(
        speakGate: CompletableDeferred<Unit>? = null,
        speakStatus: HttpStatusCode = HttpStatusCode.OK,
        signedIn: Boolean = true,
        ttlMs: Long = CallOpenerPrefetch.TTL_MS,
    ): Fixture {
        val requests = Requests()
        val audio = AudioApi(
            createHttpClient(
                MockEngine { request ->
                    requests.add(request)
                    when (request.url.encodedPath) {
                        "/api/audio/speak" -> {
                            speakGate?.await()
                            if (speakStatus == HttpStatusCode.OK) {
                                respond("""{"data_url":"data:audio/mpeg;base64,AQID","mime_type":"audio/mpeg"}""", HttpStatusCode.OK, json)
                            } else {
                                respond("""{"ok":false,"error":"TTS is down."}""", speakStatus, json)
                            }
                        }
                        else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                    }
                },
            ),
        )
        val gateways = GatewayRepository(InMemoryKeyValueStore())
        if (signedIn) gateways.save(SavedGateway("https://hermes.example.ts.net"))
        val profiles = ProfileStore(InMemoryKeyValueStore())
        // On real threads and the real clock, like the gateway it talks to: on the test's own clock, waiting for the
        // fake gateway would let the scheduler skip ahead to the time limit.
        val scope = CoroutineScope(SupervisorJob(backgroundScope.coroutineContext[Job]) + Dispatchers.Default)
        return Fixture(requests, CallOpenerPrefetch(audio, gateways, profiles, scope, ttlMs), profiles)
    }

    /** Lets the engine's thread answer: real time, since the fake gateway isn't on the test's clock. */
    private suspend fun settle(until: () -> Boolean) {
        withContext(Dispatchers.Default) {
            repeat(2_000) { if (!until()) delay(5) }
        }
        assertTrue(until(), "the fake gateway never got there")
    }

    private val chunk get() = speechChunks(reason.spokenOpener()).first()

    @Test
    fun theFirstPieceIsMadeWhileItRingsAndIsReadyOnTheAnswer() = runTest {
        val f = fixture()
        val prepared = assertNotNull(f.prefetch.prepare(reason))
        settle { f.requests.to("/api/audio/speak").size == 1 }

        val clip = assertNotNull(prepared.takeFirst(chunk))
        assertContentEquals(byteArrayOf(1, 2, 3), clip.bytes)
        // One synthesis, for the piece the call will start with.
        assertEquals(1, f.requests.to("/api/audio/speak").size)
        assertTrue(prepared.matches(gateway, null, reason.spokenOpener()))
    }

    @Test
    fun answeringBeforeItIsReadyWaitsForTheSameRequestInsteadOfAskingAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = fixture(speakGate = gate)
        val prepared = assertNotNull(f.prefetch.prepare(reason))
        settle { f.requests.to("/api/audio/speak").size == 1 }

        val taken = async { prepared.takeFirst(chunk) }
        runCurrent()
        assertFalse(taken.isCompleted, "it was handed out before it was made")
        gate.complete(Unit)

        assertNotNull(taken.await())
        assertEquals(1, f.requests.to("/api/audio/speak").size, "a second synthesis was asked for")
    }

    @Test
    fun aClipIsHandedOutOnce() = runTest {
        val f = fixture()
        val prepared = assertNotNull(f.prefetch.prepare(reason))
        settle { f.requests.to("/api/audio/speak").size == 1 }

        assertNotNull(prepared.takeFirst(chunk))
        assertNull(prepared.takeFirst(chunk), "it would play twice")
    }

    @Test
    fun onlyThePieceItWasMadeForIsGiven() = runTest {
        val f = fixture()
        val prepared = assertNotNull(f.prefetch.prepare(reason))
        settle { f.requests.to("/api/audio/speak").size == 1 }

        assertNull(prepared.takeFirst("Something else entirely."))
        // That wasn't a take: the piece it's for is still there.
        assertNotNull(prepared.takeFirst(chunk))
    }

    @Test
    fun aFailedPreparationHandsOutNothingSoTheCallAsksForItself() = runTest {
        val f = fixture(speakStatus = HttpStatusCode.InternalServerError)
        val prepared = assertNotNull(f.prefetch.prepare(reason))
        settle { f.requests.to("/api/audio/speak").size == 1 }

        assertNull(prepared.takeFirst(chunk))
        // Nothing retried by itself.
        assertEquals(1, f.requests.to("/api/audio/speak").size)
    }

    @Test
    fun aDeclinedOrReplacedCallDropsItsPreparationAndStopsTheRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = fixture(speakGate = gate)
        val prepared = assertNotNull(f.prefetch.prepare(reason))
        settle { f.requests.to("/api/audio/speak").size == 1 }
        assertEquals(1, f.prefetch.running.value)

        prepared.cancel()

        assertNull(prepared.takeFirst(chunk), "a call that ended played its opening")
        settle { f.prefetch.running.value == 0 }
    }

    @Test
    fun aCallThatIsNotAnsweredInTimeLetsGoOfItsClip() = runTest {
        val f = fixture(ttlMs = 300)
        val prepared = assertNotNull(f.prefetch.prepare(reason))
        settle { f.requests.to("/api/audio/speak").size == 1 }
        withContext(Dispatchers.Default) { delay(700) }

        assertNull(prepared.takeFirst(chunk), "a clip outlived its call")
    }

    @Test
    fun anotherGatewayProfileOrTextIsNotThisCall() = runTest {
        val f = fixture()
        f.profiles.set(gateway, "work")
        val prepared = assertNotNull(f.prefetch.prepare(reason))
        val text = reason.spokenOpener()

        assertTrue(prepared.matches(gateway, "work", text))
        assertFalse(prepared.matches(gateway, null, text), "another profile's voice")
        assertFalse(prepared.matches(gateway, "other", text))
        assertFalse(prepared.matches(GatewayUrl.parse("https://other.example.ts.net"), "work", text))
        assertFalse(prepared.matches(gateway, "work", "Hi, it's Shisha. Another reason."), "another call's reason")
    }

    @Test
    fun theProfileTheAppIsOnIsTheOneItSpeaksWith() = runTest {
        val f = fixture()
        f.profiles.set(gateway, "work")
        f.prefetch.prepare(reason)
        settle { f.requests.to("/api/audio/speak").size == 1 }

        assertEquals("work", f.requests.to("/api/audio/speak").single().url.parameters["profile"])
    }

    @Test
    fun noMoreThanTwoAreMadeAtOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = fixture(speakGate = gate)
        val first = assertNotNull(f.prefetch.prepare(reason))
        assertNotNull(f.prefetch.prepare(CallReason("Another call.")))
        assertNull(f.prefetch.prepare(CallReason("A third.")), "an unbounded number of syntheses")

        first.cancel()
        settle { f.prefetch.running.value == 1 }
        assertNotNull(f.prefetch.prepare(CallReason("A fourth.")))
    }

    @Test
    fun withoutAGatewayThereIsNothingToPrepare() = runTest {
        val f = fixture(signedIn = false)

        assertNull(f.prefetch.prepare(reason))
        assertEquals(0, f.requests.to("/api/audio/speak").size)
    }

    @Test
    fun aClockTimesFromTheAnswerToTheFirstSoundOnceAndKeepsNoWords() {
        val reported = mutableListOf<String>()
        val before = CallTiming.sink
        CallTiming.sink = { reported += it }
        try {
            val clock = CallClock()
            clock.mark(CallClock.RING)
            clock.mark(CallClock.ANSWERED)
            clock.mark(CallClock.OPENER_CLIP_PREPARED)
            clock.played()
            clock.played()

            assertEquals(1, reported.size, "reported twice")
            assertNotNull(clock.answerToAudioMs())
            assertTrue(reported.single().startsWith("answer to first words "))
            assertTrue("The deploy failed" !in reported.single())
            assertEquals(reported.single(), CallTiming.last.value)
            assertTrue(reported.single().contains("answered +"), "each stage after the answer shows what it took")
        } finally {
            CallTiming.sink = before
        }
    }
}
