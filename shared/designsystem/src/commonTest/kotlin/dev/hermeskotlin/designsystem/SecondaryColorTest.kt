package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecondaryColorTest {
    @Test
    fun withoutASecondaryItIsTheAccentItself() {
        val v = ThemeVariant(Color(0xFF112233), Color.White, text = Color(0xFF223344), soft = Color(0x11112233))
        assertEquals(v.accent, v.secondary)
        assertEquals(v.onAccent, v.onSecondary)
        assertEquals(v.text, v.secondaryText)
        assertEquals(v.soft, v.secondarySoft)
        assertFalse(v.hasSecondary)
    }

    @Test
    fun aSecondaryReachesTheTokensInEveryScheme() {
        val cyan = Color(0xFF22E3FF)
        val v = ThemeVariant(
            Color(0xFFFF7CC0), Color.Black, text = Color(0xFFFF7CC0), soft = Color(0x24FF7CC0),
            secondary = cyan, onSecondary = Color.Black, secondaryText = cyan, secondarySoft = cyan.copy(alpha = 0.14f),
        )
        assertTrue(v.hasSecondary)
        for (scheme in listOf(lightColors(v), darkColors(v), pureBlackColors(v))) {
            assertEquals(cyan, scheme.getValue(secondary))
            assertEquals(Color.Black, scheme.getValue(onSecondary))
            assertEquals(cyan, scheme.getValue(secondaryText))
            assertEquals(cyan.copy(alpha = 0.14f), scheme.getValue(secondarySoft))
        }
    }

    @Test
    fun everyExistingThemeKeepsItsLookBecauseTheSecondaryFallsBackToTheAccent() {
        for (t in AppTheme.all + AppTheme.Blue) for (v in listOf(t.light, t.dark, t.black)) {
            if (!v.hasSecondary) {
                assertEquals(v.accent, v.secondary, t.name)
                assertEquals(v.text, v.secondaryText, t.name)
            }
        }
    }
}
