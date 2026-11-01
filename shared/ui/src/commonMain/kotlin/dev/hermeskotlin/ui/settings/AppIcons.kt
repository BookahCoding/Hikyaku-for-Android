package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.designsystem.AppTheme

/** The rows of the app icon picker. */
enum class AppIconGroup(val title: String) {
    Themes("Themes"),
    Patterns("Patterns"),
}

/**
 * The launcher icons Herald comes with: one per theme ([Ukiyoe] is the one every install starts with), and the
 * patterns, which put the outlined mark over a print.
 */
enum class AppIcon(val label: String, val group: AppIconGroup) {
    Ukiyoe("Ukiyo-e", AppIconGroup.Themes),
    GreatWave("Great Wave", AppIconGroup.Themes),
    RedFuji("Red Fuji", AppIconGroup.Themes),
    ThirtySixViews("Thirty-six Views", AppIconGroup.Themes),
    Shibuya("Shibuya Night", AppIconGroup.Themes),
    Akihabara("Akihabara", AppIconGroup.Themes),
    Hearts("Hearts", AppIconGroup.Patterns),
    Marks("Marks", AppIconGroup.Patterns),
    Bubbles("Bubbles", AppIconGroup.Patterns),
}

/** The icon that goes with [this] theme. */
fun AppTheme.icon(): AppIcon = AppIcon.entries.firstOrNull { it.group == AppIconGroup.Themes && it.label == name } ?: AppIcon.Ukiyoe

/** Which launcher icon Herald shows, and switching it. */
interface AppIcons {
    val current: AppIcon

    /** [icon] as the launcher draws it, [sizePx] square, in the phone's own icon shape; null if it can't be drawn. */
    suspend fun picture(icon: AppIcon, sizePx: Int): ImageBitmap?

    /** Shows [icon] in the launcher instead of [current], and opens Herald again from it. */
    fun apply(icon: AppIcon)
}

/** Reads the icon again after [AppIcons.apply], so [AppIcons.current] follows. */
@Composable
expect fun rememberAppIcons(): AppIcons
