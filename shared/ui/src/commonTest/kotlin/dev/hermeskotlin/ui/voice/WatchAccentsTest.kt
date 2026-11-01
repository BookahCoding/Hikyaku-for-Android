package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.designsystem.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchAccentsTest {

    @Test
    fun aThemeSendsItsDarkColors() {
        val ukiyoe = watchAccent("Ukiyo-e")
        assertEquals(0xFFE5927E.toInt(), ukiyoe.fill)
        assertEquals(0xFFE5927E.toInt(), ukiyoe.text)
        assertEquals(0xFF0A0A0C.toInt(), ukiyoe.onFill)
        // The soft tint, opaque: a see-through dim would wash out on the watch.
        assertEquals(0xFFE5927E.toInt(), ukiyoe.dim)
    }

    @Test
    fun everyThemeSendsItsOwnDarkAccentToTheWatch() {
        val expected = listOf(
            "Ukiyo-e" to 0xFFE5927E,
            "Great Wave" to 0xFF7FB0E8,
            "Red Fuji" to 0xFFE8826A,
            "Thirty-six Views" to 0xFF6FBF8F,
            "Shibuya Night" to 0xFFFF7CC0,
            "Akihabara" to 0xFF2BE37A,
        )
        assertEquals(AppTheme.all.map { it.name }, expected.map { it.first })
        for ((name, argb) in expected) {
            val watch = watchAccent(name)
            assertEquals(argb.toInt(), watch.fill, "$name fill")
            assertEquals(argb.toInt(), watch.text, "$name text")
            assertEquals(argb.toInt(), watch.dim, "$name dim")
            assertEquals(0xFF0A0A0C.toInt(), watch.onFill, "$name onFill")
        }
    }

    @Test
    fun anOldOrUnknownNameIsUkiyoeAndEachThemeHasItsOwnRevision() {
        for (old in listOf("Blue", "Violet", "Green", "Orange", "Pink", "Teal", "Mauve", "")) assertEquals(watchAccent("Ukiyo-e"), watchAccent(old), old)
        assertEquals(watchAccent("Ukiyo-e"), watchAccent(null))
        val revisions = AppTheme.all.map { watchAccent(it.name).revision }
        assertEquals(6, revisions.size)
        assertEquals(revisions.size, revisions.toSet().size)
    }
}
