package dev.hermeskotlin.designsystem.components

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Keep an append-only reply visible while its next preparation finishes, including after completion. */
internal data class PreparedMarkdown(val source: String, val content: String) {
    fun visibleFor(text: String): String? = content.takeIf { text.startsWith(source) }
}

/** A replacement must not inherit the Markdown library's retained parse of a different source. */
internal class MarkdownRenderContinuity {
    private var previous = ""
    private var generation = 0

    fun keyFor(text: String): Int {
        if (!text.startsWith(previous)) generation++
        previous = text
        return generation
    }
}

/** Composition-thread LRU. Both retained source and expanded math links count toward its budget. */
internal class MarkdownPreparationCache(private val maxEntries: Int = 32, private val maxCharacters: Int = 512_000) {
    private val entries = LinkedHashMap<String, PreparedMarkdown>()
    private var characters = 0L

    init {
        require(maxEntries > 0 && maxCharacters > 0)
    }

    fun cached(text: String): PreparedMarkdown? {
        val hit = entries.remove(text) ?: return null
        entries[text] = hit
        return hit
    }

    fun put(prepared: PreparedMarkdown) {
        val weight = prepared.weight
        // One very large reply must not evict all the useful small ones, or be retained indefinitely.
        if (weight > maxCharacters) return
        entries.remove(prepared.source)?.let { characters -= it.weight }
        entries[prepared.source] = prepared
        characters += weight
        while (entries.size > maxEntries || characters > maxCharacters) {
            characters -= entries.remove(entries.keys.first())!!.weight
        }
    }

    private val PreparedMarkdown.weight: Long get() = source.length.toLong() + content.length
}

/** Returning to the caller is cancellable even if the CPU-bound preparer finished after replacement. */
internal suspend fun prepareMarkdown(
    text: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    prepare: (String) -> String = MathMarkup::prepare,
): PreparedMarkdown = withContext(dispatcher) {
    val content = prepare(text)
    ensureActive()
    PreparedMarkdown(text, content)
}
