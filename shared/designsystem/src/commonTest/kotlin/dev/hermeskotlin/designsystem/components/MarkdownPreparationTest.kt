package dev.hermeskotlin.designsystem.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownPreparationTest {
    @Test
    fun changedTextCannotHitThePreviousPreparation() {
        val cache = MarkdownPreparationCache(maxEntries = 2, maxCharacters = 100)
        cache.put(PreparedMarkdown("\$x\$", "![math](herald-math:i:eA)"))
        assertEquals("![math](herald-math:i:eA)", cache.cached("\$x\$")?.content)
        assertNull(cache.cached("\$y\$"))
    }

    @Test
    fun entryLimitEvictsTheLeastRecentlyRead() {
        val cache = MarkdownPreparationCache(maxEntries = 2, maxCharacters = 100)
        cache.put(PreparedMarkdown("a", "A"))
        cache.put(PreparedMarkdown("b", "B"))
        cache.cached("a")
        cache.put(PreparedMarkdown("c", "C"))
        assertNull(cache.cached("b"))
        assertEquals("A", cache.cached("a")?.content)
        assertEquals("C", cache.cached("c")?.content)
    }

    @Test
    fun textBudgetCountsSourceAndResultAndDoesNotRetainHugeMessages() {
        val cache = MarkdownPreparationCache(maxEntries = 10, maxCharacters = 8)
        cache.put(PreparedMarkdown("aa", "AA"))
        cache.put(PreparedMarkdown("bb", "BB"))
        cache.put(PreparedMarkdown("cc", "CC"))
        assertNull(cache.cached("aa"))
        assertEquals("BB", cache.cached("bb")?.content)
        cache.put(PreparedMarkdown("large", "LARGE"))
        assertNull(cache.cached("large"))
        assertEquals("BB", cache.cached("bb")?.content)
    }

    @Test
    fun previousRenderIsOnlyRetainedWhileTheSameStreamingTextGrows() {
        val ready = PreparedMarkdown("one", "ONE")
        assertEquals("ONE", ready.visibleFor("one more", streaming = true))
        assertNull(ready.visibleFor("another message", streaming = true))
        assertNull(ready.visibleFor("one more", streaming = false))
        assertNull(ready.visibleFor("on", streaming = true))
        assertEquals("ONE", ready.visibleFor("one", streaming = false))
    }

    @Test
    fun rendererStateSurvivesDeltasButResetsForReplacementOrShortening() {
        val continuity = MarkdownRenderContinuity()
        val first = continuity.keyFor("first")
        assertEquals(first, continuity.keyFor("first more"))
        assertEquals(first, continuity.keyFor("first more"))
        val replacement = continuity.keyFor("other")
        assertTrue(first != replacement)
        assertTrue(replacement != continuity.keyFor("oth"))
    }

    @Test
    fun cancellationDiscardsEvenANonCooperativePreparationAndReplacementFinishesExactly() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        var obsoletePublished = false
        lateinit var obsolete: Job
        obsolete = launch {
            prepareMarkdown("obsolete", worker) {
                obsolete.cancel()
                "must not publish"
            }
            obsoletePublished = true
        }
        runCurrent()
        assertTrue(obsolete.isCancelled)
        assertFalse(obsoletePublished)
        val replacement = prepareMarkdown("final \$x\$", worker)
        assertEquals("final \$x\$", replacement.source)
        assertEquals("final ![math](herald-math:i:eA)", replacement.content)
    }
}
