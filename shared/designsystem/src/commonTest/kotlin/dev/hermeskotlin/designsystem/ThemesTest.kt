package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every theme with a look of its own can be read, in light, dark and pure black. */
class ThemesTest {
    private val themes = listOf(Themes.GreatWave, Themes.RedFuji, Themes.ThirtySixViews, Themes.Shibuya, Themes.Akihabara)

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun schemes(p: AppTheme) = mapOf(
        "light" to lightColors(p.light), "dark" to darkColors(p.dark), "black" to pureBlackColors(p.black),
    )

    private val grounds = listOf(background, surface, surface2, surface3, surfaceElevated)

    private fun lab(c: Color): Triple<Double, Double, Double> {
        fun lin(v: Float) = v.toDouble().let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
    private fun difference(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a); val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2) * (l1 - l2) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2))
    }
    private fun hue(c: Color) = Math.toDegrees(Math.atan2(lab(c).third, lab(c).second)).let { if (it < 0) it + 360 else it }

    @Test
    fun everyThemeBringsItsOwnNeutralsAndIsListed() {
        for (p in themes) {
            assertNotNull(p.light.neutrals, p.name); assertNotNull(p.dark.neutrals, p.name); assertNotNull(p.black.neutrals, p.name)
            assertTrue(p in AppTheme.all, p.name)
            assertEquals(p, AppTheme.named(p.name))
        }
    }

    @Test
    fun pureBlackGroundIsBlack() {
        for (p in themes) assertEquals(Color.Black, schemes(p).getValue("black").getValue(background), p.name)
    }

    @Test
    fun everyTextStepReadsOnEveryGround() {
        for (p in themes) for ((scheme, c) in schemes(p)) {
            val tinted = c.getValue(accentSoft).compositeOver(c.getValue(surface))
            for (g in grounds.map { c.getValue(it) } + tinted + c.getValue(well)) {
                assertTrue(contrast(c.getValue(text), g) >= 7f, "${p.name} $scheme text on $g")
                assertTrue(contrast(c.getValue(textSecondary), g) >= 7f, "${p.name} $scheme secondary on $g")
                assertTrue(contrast(c.getValue(textTertiary), g) >= 4.5f, "${p.name} $scheme tertiary on $g")
            }
        }
    }

    @Test
    fun accentTextAndSignalsReadOnTheGrounds() {
        for (p in themes) for ((scheme, c) in schemes(p)) {
            val tinted = c.getValue(accentSoft).compositeOver(c.getValue(surface))
            for (g in grounds.map { c.getValue(it) } + tinted) {
                assertTrue(contrast(c.getValue(accentText), g) >= 4.5f, "${p.name} $scheme accent text on $g")
            }
            for (s in listOf(danger, success, warning)) for (g in grounds.map { c.getValue(it) }) {
                assertTrue(contrast(c.getValue(s), g) >= 4.5f, "${p.name} $scheme signal on $g")
            }
        }
    }

    @Test
    fun onAccentInverseAndBubbleRead() {
        for (p in themes) {
            for ((scheme, colors) in listOf("light" to p.light, "dark" to p.dark, "black" to p.black)) {
                assertTrue(contrast(colors.onAccent, colors.accent) >= 4.5f, "${p.name} $scheme on-accent")
            }
            for ((scheme, c) in schemes(p)) {
                assertTrue(contrast(c.getValue(onInverse), c.getValue(inverse)) >= 7f, "${p.name} $scheme inverse")
                assertTrue(contrast(c.getValue(onUserBubble), c.getValue(userBubble)) >= 4.5f, "${p.name} $scheme bubble")
            }
        }
    }

    @Test
    fun aSecondaryColourReadsWhereAThemeHasOne() {
        for (p in themes) for ((scheme, colors) in listOf("light" to p.light, "dark" to p.dark, "black" to p.black)) {
            val expected = p in listOf(Themes.ThirtySixViews, Themes.Shibuya, Themes.Akihabara)
            assertEquals(expected, colors.hasSecondary, "${p.name} $scheme hasSecondary")
            if (!colors.hasSecondary) continue
            val c = when (scheme) { "light" -> lightColors(colors); "dark" -> darkColors(colors); else -> pureBlackColors(colors) }
            val tinted = c.getValue(secondarySoft).compositeOver(c.getValue(surface))
            for (g in grounds.map { c.getValue(it) } + tinted) {
                assertTrue(contrast(c.getValue(secondaryText), g) >= 4.5f, "${p.name} $scheme secondary text on $g")
            }
            assertTrue(contrast(colors.onSecondary, colors.secondary) >= 4.5f, "${p.name} $scheme on-secondary")
            assertTrue(difference(colors.secondary, colors.accent) >= 20.0, "${p.name} $scheme secondary looks like the accent")
        }
    }

    @Test
    fun aDestructiveActionIsNeverMistakenForTheAccent() {
        for (p in themes) for ((scheme, c) in schemes(p)) {
            val a = c.getValue(accent); val d = c.getValue(danger)
            val gap = abs(hue(a) - hue(d)).let { min(it, 360 - it) }
            assertTrue(difference(a, d) >= 25.0, "${p.name} $scheme danger vs accent ΔE ${difference(a, d)}")
            assertTrue(gap >= 15.0, "${p.name} $scheme danger vs accent hue $gap")
        }
    }

    @Test
    fun theNeonThemesAreDarkFirstWithSaturatedAccents() {
        for (p in listOf(Themes.Shibuya, Themes.Akihabara)) {
            val night = darkColors(p.dark).getValue(background)
            assertTrue(night.luminance() < 0.02f, "${p.name} night ground must be near black")
            val a = p.dark.accent
            val spread = max(a.red, max(a.green, a.blue)) - min(a.red, min(a.green, a.blue))
            assertTrue(spread > 0.5f, "${p.name} accent should be saturated")
        }
        assertTrue(Themes.Shibuya.dark.hasSecondary && Themes.Akihabara.dark.hasSecondary, "the neon themes carry a second colour")
        assertTrue(Themes.ThirtySixViews.dark.hasSecondary, "Thirty-six Views carries its ochre")
    }

    @Test
    fun mutedTextStaysLegibleOnTheMainGrounds() {
        for (p in themes) for ((scheme, c) in schemes(p)) {
            for (g in listOf(background, surface, surface2).map { c.getValue(it) }) {
                assertTrue(contrast(c.getValue(textMuted), g) >= 3f, "${p.name} $scheme muted on $g")
            }
        }
    }

    @Test
    fun noTwoThemesShareAnAccent() {
        val all = AppTheme.all
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]; val b = all[j]
            for ((scheme, pair) in listOf("light" to (a.light to b.light), "dark" to (a.dark to b.dark), "black" to (a.black to b.black))) {
                val d = difference(pair.first.accent, pair.second.accent)
                assertTrue(d >= 15.0, "${a.name} vs ${b.name} $scheme accent ΔE $d")
            }
        }
    }
}
