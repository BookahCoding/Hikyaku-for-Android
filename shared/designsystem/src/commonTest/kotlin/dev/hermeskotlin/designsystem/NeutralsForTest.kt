package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertNotEquals

class NeutralsForTest {
    @Test
    fun aVariantWithoutNeutralsUsesTheSharedSetOfItsScheme() {
        val v = ThemeVariant(Color.Red, Color.White, Color.Red, Color.Red)
        assertNotEquals(v.neutralsFor(dark = false).bg, v.neutralsFor(dark = true).bg)
        assertEquals(Palette.LightBg, v.neutralsFor(dark = false).bg)
        assertEquals(Palette.Bg, v.neutralsFor(dark = true).bg)
    }

    @Test
    fun aVariantWithItsOwnNeutralsKeepsThem() {
        val own = AppTheme.named("Great Wave").light
        assertSame(own.neutrals, own.neutralsFor(dark = false))
    }
}
