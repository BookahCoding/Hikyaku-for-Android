package dev.hermeskotlin.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecondaryUseTest {
    @Test
    fun aThemeWithoutASecondaryGivesLinkAndUnreadColoursOfItsAccent() {
        for (theme in AppTheme.all.filter { !it.dark.hasSecondary && !it.light.hasSecondary }) {
            for (scheme in listOf(lightColors(theme.light), darkColors(theme.dark), pureBlackColors(theme.dark))) {
                assertEquals(scheme.getValue(accentText), scheme.getValue(secondaryText), theme.name)
                assertEquals(scheme.getValue(accent), scheme.getValue(secondary), theme.name)
            }
        }
        assertFalse(AppTheme.Ukiyoe.dark.hasSecondary)
    }

    @Test
    fun aThemeWithASecondaryGivesItToTheLinkAndUnreadTokens() {
        val shibuya = AppTheme.named("Shibuya Night")
        val scheme = darkColors(shibuya.dark)
        assertEquals(shibuya.dark.secondary, scheme.getValue(secondary))
        assertEquals(shibuya.dark.secondaryText, scheme.getValue(secondaryText))
        assertTrue(scheme.getValue(secondary) != scheme.getValue(accent))
    }
}
