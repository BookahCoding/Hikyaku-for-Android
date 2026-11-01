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
    fun anOldOrUnknownNameIsUkiyoeAndEachThemeHasItsOwnRevision() {
        for (old in listOf("Blue", "Violet", "Green", "Orange", "Pink", "Teal", "Mauve", "")) assertEquals(watchAccent("Ukiyo-e"), watchAccent(old), old)
        assertEquals(watchAccent("Ukiyo-e"), watchAccent(null))
        val revisions = AppTheme.all.map { watchAccent(it.name).revision }
        assertEquals(6, revisions.size)
        assertEquals(revisions.size, revisions.toSet().size)
    }
}
