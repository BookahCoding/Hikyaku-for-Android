package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.speechChunks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Where the time goes between a call ringing and its first words being heard, in milliseconds on a monotonic clock.
 * Only stage names and durations are kept: never the words, ids or anything the gateway sent.
 */
class CallClock {
    private val start = TimeSource.Monotonic.markNow()
    private val marks = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    private val finished = MutableStateFlow(false)

    /** Notes that [stage] was reached now. */
    fun mark(stage: String) {
        val at = start.elapsedNow().inWholeMilliseconds
        marks.update { it + (stage to at) }
    }

    /** The first words are about to play: the call's timings are final. Once; later calls do nothing. */
    fun played() {
        if (!finished.compareAndSet(expect = false, update = true)) return
        mark(PLAY)
        CallTiming.report(summary())
    }

    /**
     * Where "the answer" starts: the tap on the lock screen's Answer when there was one, else the call screen
     * opening (an Answer from the notification shade launches it directly, so that is the earliest a tap is seen).
     */
    private fun answerAt(): Long? = marks.value.firstOrNull { it.first == TAP }?.second ?: marks.value.firstOrNull { it.first == ANSWERED }?.second

    /** Milliseconds from the answer to the first words, once known. */
    fun answerToAudioMs(): Long? {
        val from = answerAt() ?: return null
        val played = marks.value.firstOrNull { it.first == PLAY }?.second ?: return null
        return played - from
    }

    /** The headline, then each stage after the answer with the time it took since the one before. */
    fun summary(): String {
        val from = answerAt()
        val head = answerToAudioMs()?.let { "answer to first words $it ms" } ?: "no first words"
        if (from == null) return head
        var previous: Long = from
        val stages = marks.value.filter { it.second >= from }.mapNotNull { (stage, at) ->
            val took = at - previous
            previous = at
            if (stage == TAP) null else "$stage +$took ms"
        }
        return (listOf(head) + stages).joinToString(" | ")
    }

    companion object {
        const val RING = "ring"
        const val TAP = "tap"
        const val ANSWERED = "answered"
        const val UNLOCKED = "unlocked"
        const val CHAT_READY = "chatReady"
        const val OPENER_START = "openerStart"
        const val OPENER_CLIP = "openerClip"
        const val OPENER_CLIP_PREPARED = "openerClipPrepared"
        const val PLAY = "play"
    }
}

/** Where a call's timings go: logged by the app, and the last one kept to show in Settings. */
object CallTiming {
    /** Set by the app to log a finished call's summary (stage names and milliseconds only). */
    var sink: (String) -> Unit = {}

    private val _last = MutableStateFlow<String?>(null)

    /** The last call's summary, until the app is closed. */
    val last: StateFlow<String?> = _last.asStateFlow()

    internal fun report(summary: String) {
        _last.value = summary
        sink(summary)
    }
}

/**
 * The first piece of a call's opening words, synthesized while the phone rings so that answering has it ready.
 * It's bound to one call, one gateway, one profile and one text: [matches] says whether a call about to speak is
 * that one, and nothing else ever uses it. It's used once, holds one clip at most, and lets go of it on [cancel] and
 * after [CallOpenerPrefetch.TTL_MS].
 *
 * It only ever makes audio; playing it is left to the call once it's answered.
 */
class PreparedOpener internal constructor(
    val gateway: GatewayUrl,
    val profile: String?,
    val text: String,
    private val firstChunk: String,
    private val job: Job,
    private var first: Deferred<SpokenAudio?>?,
) {
    private val claimed = MutableStateFlow(false)

    /** Whether a call speaking [text] to [gateway] as [profile] is the one this was made for. */
    fun matches(gateway: GatewayUrl, profile: String?, text: String): Boolean =
        gateway == this.gateway && profile == this.profile && text == this.text

    /**
     * The first clip, when [chunk] is the piece it was made for: waits for it when still on its way (the same request,
     * not a second one), and null when it failed, was cancelled or was taken already; the caller then asks the gateway
     * itself. A clip is handed out once.
     */
    suspend fun takeFirst(chunk: String): SpokenAudio? {
        if (chunk != firstChunk) return null
        if (!claimed.compareAndSet(expect = false, update = true)) return null
        val pending = first ?: return null
        first = null
        return awaitOrNull(pending)
    }

    /** Stops the request, if it's still going, and drops the clip. */
    fun cancel() {
        job.cancel()
        first = null
    }

    private suspend fun <T> awaitOrNull(deferred: Deferred<T>): T? = try {
        deferred.await()
    } catch (e: CancellationException) {
        // The preparation was cancelled: that's "not ready". The caller being cancelled is another matter.
        if (!currentCoroutineContext().isActive) throw e
        null
    }
}

/**
 * Makes a [PreparedOpener] for a call the agent places, once its phone rings. Everything it asks the gateway is what
 * answering would ask first anyway: the first piece of the opening line.
 */
class CallOpenerPrefetch(
    private val audio: AudioApi,
    private val gateways: GatewayRepository,
    private val profiles: ProfileStore,
    private val scope: CoroutineScope,
    private val ttlMs: Long = TTL_MS,
) {
    private val active = MutableStateFlow(0)

    /** Preparations going at once; a call ringing over another one's isn't prepared, and just answers the usual way. */
    val running: StateFlow<Int> = active.asStateFlow()

    /**
     * Starts preparing the opening of a call with [reason], for the gateway and profile the app is on (the ones the
     * call will speak with). Null when there's nothing to prepare for: no gateway, or too many going already.
     */
    suspend fun prepare(reason: CallReason): PreparedOpener? {
        val text = reason.spokenOpener()
        val chunk = speechChunks(text).firstOrNull() ?: return null
        val gateway = gateways.current()?.gatewayUrl ?: return null
        val profile = profiles.get(gateway)
        if (!reserve()) return null
        val job = Job(scope.coroutineContext[Job])
        val work = CoroutineScope(scope.coroutineContext + job)
        val first = work.async { (audio.speak(gateway, chunk, profile) as? ApiResult.Success)?.value }
        job.complete()
        job.invokeOnCompletion { active.update { it - 1 } }
        val prepared = PreparedOpener(gateway, profile, text, chunk, job, first)
        scope.launch {
            delay(ttlMs)
            prepared.cancel()
        }
        return prepared
    }

    private fun reserve(): Boolean {
        while (true) {
            val now = active.value
            if (now >= MAX_RUNNING) return false
            if (active.compareAndSet(now, now + 1)) return true
        }
    }

    companion object {
        /** A call rings for about a minute; whatever isn't taken by then is dropped. */
        const val TTL_MS = 120_000L
        const val MAX_RUNNING = 2
    }
}
