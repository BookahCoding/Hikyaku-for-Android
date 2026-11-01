package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.designsystem.AppTheme
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

    private class Store(var name: String? = null) : PendingIconStore {
        override fun load() = name
        override fun save(name: String?) {
            this.name = name
        }
    }

    @Test
    fun aPickSurvivesTheProcessBeingKilled() {
        val store = Store()
        PendingIcon(store).set(AppIcon.Akihabara)
        assertEquals(AppIcon.Akihabara, PendingIcon(store).take(current = AppIcon.Ukiyoe))
        assertNull(store.name)
    }

    @Test
    fun clearingAndTakingRemoveTheStoredName() {
        val store = Store()
        val p = PendingIcon(store)
        p.set(AppIcon.RedFuji)
        p.clear()
        assertNull(store.name)
        p.set(AppIcon.RedFuji)
        p.take(current = AppIcon.RedFuji)
        assertNull(store.name)
    }

    @Test
    fun anUnknownStoredNameIsIgnored() {
        val store = Store("Retired")
        assertNull(PendingIcon(store).take(current = AppIcon.Ukiyoe))
        assertNull(store.name)
    }

    @Test
    fun reTappingTheSelectedThemeQueuesNothing() {
        val a = AppTheme.all[0]
        val b = AppTheme.all[1]
        assertEquals(false, shouldQueueThemeIcon(picked = a, selected = a))
        assertEquals(true, shouldQueueThemeIcon(picked = b, selected = a))
    }

    @Test
    fun theIconIsFlushedOnlyWhenTheAppReallyLeft() {
        val own = "dev.hermeskotlin.android"
        assertEquals(true, shouldFlushPendingIcon(false, own, own, interactive = true)) // Home or Recents
        assertEquals(false, shouldFlushPendingIcon(false, "com.google.android.GoogleCamera", own, interactive = true)) // a camera in front
        assertEquals(false, shouldFlushPendingIcon(false, own, own, interactive = false)) // screen off
        assertEquals(false, shouldFlushPendingIcon(true, own, own, interactive = true)) // rotation
        assertEquals(false, shouldFlushPendingIcon(false, null, own, interactive = true)) // unknown
    }
}
