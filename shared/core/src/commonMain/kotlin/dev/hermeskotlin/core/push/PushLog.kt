package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

/** One push Herald accepted, kept so it can be found again: a bot's reply or a call (cron runs live in the Inbox). */
@Serializable
data class PushLogEntry(
    val id: String,
    val type: String,
    /** When it was received, unix seconds. */
    val at: Long,
    val bot: String? = null,
    val label: String? = null,
    /** The stored session the message is in. */
    val session: String? = null,
    val text: String = "",
) {
    companion object {
        private val BOT_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val SESSION_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")

        /** The entry for [message], or null for a type that isn't logged or one missing what names it. */
        fun from(message: PushMessage): PushLogEntry? {
            if (message.type != PushMessage.BOT_MESSAGE && message.type != PushMessage.PHONE_CALL) return null
            val bot = message.bot?.takeIf { BOT_NAME.matches(it) }
            val session = message.session?.takeIf { SESSION_ID.matches(it) }
            if (message.type == PushMessage.BOT_MESSAGE && (bot == null || session == null)) return null
            val text = (message.text ?: message.reason).orEmpty().trim().take(PushLogStore.MAX_TEXT)
            if (text.isEmpty()) return null
            return PushLogEntry(
                id = message.id,
                type = message.type,
                at = message.ts,
                bot = bot,
                label = message.label?.filter { !it.isISOControl() }?.trim()?.take(64)?.takeIf { it.isNotEmpty() },
                session = session,
                text = text,
            )
        }
    }
}

/**
 * The pushes Herald accepted, newest first, kept per gateway so a notification can always be found again.
 * Same shape as `InboxStore`: [bind] picks the gateway shown, [record] works for any gateway without moving it.
 */
class PushLogStore(
    private val store: KeyValueStore,
    private val work: CoroutineContext = Dispatchers.Default,
) {
    @Serializable
    private data class Saved(val entries: List<PushLogEntry> = emptyList(), val read: Set<String> = emptySet())

    data class Snapshot(val entries: List<PushLogEntry> = emptyList(), val read: Set<String> = emptySet()) {
        val unread: Int get() = entries.count { it.id !in read }
    }

    private val lock = Mutex()
    private var gateway: String? = null
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    suspend fun bind(gatewayUrl: String) = lock.withLock {
        if (gateway == gatewayUrl) return@withLock
        val saved = load(gatewayUrl)
        gateway = gatewayUrl
        _state.value = Snapshot(saved.entries, saved.read)
    }

    suspend fun record(gatewayUrl: String, entry: PushLogEntry, now: Long = getTimeMillis() / 1000) = lock.withLock {
        val saved = if (gateway == gatewayUrl) Saved(_state.value.entries, _state.value.read) else load(gatewayUrl)
        val next = saved.copy(entries = trim(listOf(entry) + saved.entries.filter { it.id != entry.id }, now))
        save(gatewayUrl, next)
        if (gateway == gatewayUrl) _state.value = Snapshot(next.entries, next.read)
    }

    suspend fun markRead(gatewayUrl: String, id: String) = edit(gatewayUrl) { s -> s.copy(read = trimRead(s.read + id)) }

    suspend fun markAllRead(gatewayUrl: String) = edit(gatewayUrl) { s -> s.copy(read = trimRead(s.read + s.entries.map { it.id })) }

    /** Forgets [gatewayUrl]'s log, when the gateway itself is forgotten. */
    suspend fun forget(gatewayUrl: String) = lock.withLock {
        store.remove(key(gatewayUrl))
        if (gateway == gatewayUrl) {
            gateway = null
            _state.value = Snapshot()
        }
    }

    private suspend fun edit(url: String, change: (Saved) -> Saved) = lock.withLock {
        if (gateway != url) return@withLock
        val next = change(Saved(_state.value.entries, _state.value.read))
        save(url, next)
        _state.value = Snapshot(next.entries, next.read)
    }

    private suspend fun load(url: String): Saved {
        val raw = store.get(key(url)) ?: return Saved()
        return withContext(work) { runCatching { HermesJson.decodeFromString(Saved.serializer(), raw) }.getOrDefault(Saved()) }
    }

    private suspend fun save(url: String, saved: Saved) {
        val encoded = withContext(work) { HermesJson.encodeToString(Saved.serializer(), saved) }
        store.put(key(url), encoded)
    }

    private fun key(url: String) = "pushlog.v1.$url"

    companion object {
        const val MAX_KEPT = 500
        const val MAX_AGE_SECONDS = 30L * 24 * 3600
        const val MAX_TEXT = 2_000
        private const val MAX_READ = 1_000

        /** [entries] without repeats or anything older than [MAX_AGE_SECONDS], newest first, at most [MAX_KEPT]. */
        fun trim(entries: List<PushLogEntry>, now: Long): List<PushLogEntry> =
            entries.filter { now - it.at <= MAX_AGE_SECONDS }.distinctBy { it.id }.sortedByDescending { it.at }.take(MAX_KEPT)

        private fun trimRead(read: Set<String>): Set<String> = if (read.size <= MAX_READ) read else read.toList().takeLast(MAX_READ).toSet()
    }
}
