package dev.hermeskotlin.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingIconTest {
    @Test
    fun theLastPickWinsAndIsTakenOnce() {
        val p = PendingIcon()
        p.set(AppIcon.GreatWave)
        p.set(AppIcon.Shibuya)
        assertEquals(AppIcon.Shibuya, p.take(current = AppIcon.Ukiyoe))
        assertNull(p.take(current = AppIcon.Ukiyoe))
    }

    @Test
    fun pickingTheIconAlreadyShownChangesNothing() {
        val p = PendingIcon()
        p.set(AppIcon.GreatWave)
        p.set(AppIcon.Ukiyoe)
        assertNull(p.take(current = AppIcon.Ukiyoe))
    }

    @Test
    fun nothingPendingGivesNothing() {
        assertNull(PendingIcon().take(current = AppIcon.Hearts))
    }

    @Test
    fun anIconChosenByHandIsNotOverwrittenByAnEarlierThemePick() {
        val p = PendingIcon()
        p.set(AppIcon.Shibuya)
        p.clear()
        assertNull(p.take(current = AppIcon.Hearts))
    }

    @Test
    fun aThemePickReplacesAPatternIconShown() {
        val p = PendingIcon()
        p.set(AppIcon.RedFuji)
        assertEquals(AppIcon.RedFuji, p.take(current = AppIcon.Bubbles))
    }
}
