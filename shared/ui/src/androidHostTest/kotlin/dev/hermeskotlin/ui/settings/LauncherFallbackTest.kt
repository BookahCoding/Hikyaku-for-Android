package dev.hermeskotlin.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** After an update drops the alias a user had switched to, Herald must not be left without a launcher entry. */
class LauncherFallbackTest {
    private val default = 0 // COMPONENT_ENABLED_STATE_DEFAULT: the manifest's android:enabled decides
    private val enabled = 1
    private val disabled = 2

    private fun states(vararg set: Pair<AppIcon, Int>) = AppIcon.entries.associateWith { default } + set

    @Test
    fun freshInstallNeedsNothing() = assertNull(launcherIconToEnable(states()))

    @Test
    fun ukiyoeSwitchedOffByTheAppAndNothingElseOnNeedsIt() {
        // The user picked Violet, which this update removed: MainActivity stays DISABLED, no known alias is on.
        assertEquals(AppIcon.Ukiyoe, launcherIconToEnable(states(AppIcon.Ukiyoe to disabled)))
    }

    @Test
    fun anotherEnabledAliasNeedsNothing() {
        assertNull(launcherIconToEnable(states(AppIcon.Ukiyoe to disabled, AppIcon.Hearts to enabled)))
    }

    @Test
    fun explicitlyEnabledDefaultNeedsNothing() {
        assertNull(launcherIconToEnable(states(AppIcon.Ukiyoe to enabled)))
    }

    @Test
    fun otherAliasesAtTheirManifestDefaultAreOff() {
        // Only Ukiyoe is enabled in the manifest, so DEFAULT elsewhere means off.
        assertEquals(AppIcon.Ukiyoe, launcherIconToEnable(states(AppIcon.Ukiyoe to disabled, AppIcon.Marks to default)))
    }

    @Test
    fun userDisabledStatesCountAsOff() {
        assertEquals(AppIcon.Ukiyoe, launcherIconToEnable(states(AppIcon.Ukiyoe to 3, AppIcon.Bubbles to 4)))
    }
}
