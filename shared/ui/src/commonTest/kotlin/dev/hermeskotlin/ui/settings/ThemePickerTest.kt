package dev.hermeskotlin.ui.settings

import dev.hermeskotlin.designsystem.AppTheme
import dev.hermeskotlin.designsystem.ThemeVariant
import dev.hermeskotlin.designsystem.neutralsFor
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ThemePickerTest {
    @Test
    fun everyThemeHasADistinctPreviewInBothSchemes() {
        for (dark in listOf(false, true)) {
            val swatches = AppTheme.all.map { themeSwatch(it, dark) }
            assertEquals(AppTheme.all.size, swatches.map { it.background to it.accent }.toSet().size, "dark=$dark")
        }
    }

    @Test
    fun theSwatchUsesTheThemesOwnGroundsAndAccent() {
        val p = AppTheme.named("Great Wave")
        val light = themeSwatch(p, dark = false)
        val dark = themeSwatch(p, dark = true)
        assertEquals(p.light.accent, light.accent)
        assertEquals(p.dark.accent, dark.accent)
        assertEquals(p.light.neutrals!!.bg, light.background)
        assertNotEquals(light.background, dark.background)
    }

    @Test
    fun theSelectedLabelNamesTheTheme() {
        assertEquals("Great Wave, selected", themeLabel(AppTheme.named("Great Wave"), selected = true))
        assertEquals("Red Fuji", themeLabel(AppTheme.named("Red Fuji"), selected = false))
    }

    @Test
    fun aVariantWithoutNeutralsFallsBackToTheSharedOnes() {
        val v = ThemeVariant(Color.Red, Color.White, Color.Red, Color.Red)
        assertNotEquals(v.neutralsFor(dark = false).bg, v.neutralsFor(dark = true).bg)
    }
}
