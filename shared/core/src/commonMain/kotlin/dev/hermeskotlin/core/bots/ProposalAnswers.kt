package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** How a proposal was answered: [approve] is null when it ended some way that isn't a yes or a no here. [text] is what to show. */
data class ProposalOutcome(val approve: Boolean?, val text: String)

/** Proposals being answered right now ([sending]) and answered ones ([done]), both keyed by `bot/number`. */
data class ProposalsState(val sending: Set<String> = emptySet(), val done: Map<String, ProposalOutcome> = emptyMap())

/**
 * Which proposals this phone has answered, shared by the notification's buttons and the chat's: an answer from either
 * must not be sent twice, and must show on the other. Answered ones are kept ([store], newest [keep]) so the chat shows
 * them after it is reopened; "being sent" is never kept, since a send cut short by the app dying proves nothing.
 *
 * The plugin is what finally refuses a second answer; this only keeps the app from offering one.
 */
class ProposalAnswers(
    private val store: KeyValueStore? = null,
    private val scope: CoroutineScope? = null,
    private val keep: Int = DEFAULT_KEEP,
) {

    private val state = MutableStateFlow(ProposalsState())

    /** The current state, for the chat to follow. */
    val flow: StateFlow<ProposalsState> = state.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val writes = Mutex()

    init {
        if (store != null && scope != null) scope.launch { load(store) } else loaded.complete(Unit)
    }

    /** Waits until what was kept has been read, so a tap just after start-up can't skip an answer already given. */
    suspend fun ready() = loaded.await()

    private fun key(bot: String, number: Int) = "$bot/$number"

    /** True for the one caller that may send the answer for [number]; false when it's answered or on its way. */
    fun claim(bot: String, number: Int): Boolean {
        val k = key(bot, number)
        var won = false
        state.update { s ->
            won = k !in s.sending && k !in s.done
            if (won) s.copy(sending = s.sending + k) else s
        }
        return won
    }

    /** The answer went out and the plugin said [text]: it stays answered, with what to show in place of the buttons. */
    fun settle(bot: String, number: Int, approve: Boolean?, text: String) {
        val k = key(bot, number)
        state.update { s -> s.copy(sending = s.sending - k, done = s.done.with(k, ProposalOutcome(approve, text))) }
        persist()
    }

    /** An answer seen elsewhere (a later message, a typed command). Never replaces one already known. */
    fun record(bot: String, number: Int, approve: Boolean?, text: String) {
        val k = key(bot, number)
        var added = false
        state.update { s ->
            added = k !in s.done
            if (added) s.copy(done = s.done.with(k, ProposalOutcome(approve, text))) else s
        }
        if (added) persist()
    }

    /** The answer didn't go out: the caller may try again. */
    fun release(bot: String, number: Int) {
        val k = key(bot, number)
        state.update { s -> s.copy(sending = s.sending - k) }
    }

    /** Answered, or being answered: no buttons for it. */
    fun answered(bot: String, number: Int): Boolean {
        val k = key(bot, number)
        return state.value.let { k in it.sending || k in it.done }
    }

    /** How it was answered, when it was and this phone knows. */
    fun outcome(bot: String, number: Int): ProposalOutcome? = state.value.done[key(bot, number)]

    private fun Map<String, ProposalOutcome>.with(k: String, outcome: ProposalOutcome): Map<String, ProposalOutcome> =
        (this - k + (k to outcome)).entries.toList().takeLast(keep).associate { it.key to it.value }

    private suspend fun load(store: KeyValueStore) {
        try {
            val saved = store.get(KEY)?.let { raw ->
                runCatching { HermesJson.decodeFromString(Saved.serializer(), raw).entries }.getOrNull()
            }.orEmpty()
            val read = saved.associate { it.key to ProposalOutcome(it.approve, it.text) }
            // What happened since start-up wins over what was kept.
            state.update { s -> s.copy(done = (read + s.done).entries.toList().takeLast(keep).associate { it.key to it.value }) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unreadable: start empty. The plugin refuses a repeat anyway.
        } finally {
            loaded.complete(Unit)
        }
    }

    private fun persist() {
        val store = store ?: return
        scope?.launch {
            // Whatever is newest when the turn comes, so quick answers can't write over each other with old state.
            writes.withLock {
                val entries = state.value.done.map { (k, o) -> Entry(k, o.approve, o.text) }
                store.put(KEY, HermesJson.encodeToString(Saved.serializer(), Saved(entries)))
            }
        }
    }

    @Serializable
    private data class Entry(val key: String, val approve: Boolean? = null, val text: String)

    @Serializable
    private data class Saved(val entries: List<Entry> = emptyList())

    companion object {
        private const val KEY = "proposals.answers.v1"
        private const val DEFAULT_KEEP = 200
    }
}
