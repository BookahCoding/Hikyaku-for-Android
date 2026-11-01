package dev.hermeskotlin.ui.assistant

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.hermeskotlin.designsystem.AppTheme
import dev.hermeskotlin.designsystem.hasSecondary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeSecondaryTest {
    // The edge light as it was before themes had a secondary.
    private fun edgeBeforeSecondary(theme: AppTheme) =
        EdgeColors(theme.light.accent, theme.dark.text, lerp(Color.White, theme.dark.text, 0.22f))

    @Test
    fun theEdgeLightsBrightestToneIsTheSecondaryWhereAThemeHasOne() {
        val shibuya = AppTheme.named("Shibuya Night")
        assertTrue(shibuya.dark.hasSecondary && shibuya.light.hasSecondary)
        assertEquals(shibuya.dark.secondary, edgeColors(shibuya, dark = true).head)
        assertEquals(shibuya.light.secondary, edgeColors(shibuya, dark = false).head)
    }

    @Test
    fun aThemeWithoutOneKeepsItsEdgeLight() {
        for (theme in listOf(AppTheme.Ukiyoe, AppTheme.named("Great Wave"))) {
            for (dark in listOf(true, false)) {
                val before = edgeBeforeSecondary(theme)
                val now = edgeColors(theme, dark)
                assertEquals(before.deep, now.deep)
                assertEquals(before.bright, now.bright)
                assertEquals(before.head, now.head)
            }
        }
    }

    @Test
    fun bright_and_deep_stay_accent_derived_even_with_a_secondary() {
        val shibuya = AppTheme.named("Shibuya Night")
        val before = edgeBeforeSecondary(shibuya)
        assertEquals(before.deep, shibuya.edge.deep)
        assertEquals(before.bright, shibuya.edge.bright)
    }
}
