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

    /** Shows [icon] once the app is left, without restarting it; the last call wins. */
    fun applyWhenLeaving(icon: AppIcon)
}

/** Where a [PendingIcon] keeps its icon's name, so a pick survives the process being killed. */
interface PendingIconStore {
    fun load(): String?
    fun save(name: String?)
}

/**
 * The icon a theme pick asked for, held until the app is left; the last pick wins. Kept in [store] so it survives the
 * process dying while something covers the app (a picker, the camera).
 */
class PendingIcon(private val store: PendingIconStore = object : PendingIconStore {
    private var name: String? = null
    override fun load() = name
    override fun save(name: String?) {
        this.name = name
    }
}) {
    fun set(icon: AppIcon?) = store.save(icon?.name)

    /** Forgets what is pending, for an icon chosen by hand that must not be overwritten. */
    fun clear() = set(null)

    /** The icon to switch to, or null when nothing is pending or it is the one already shown; forgets it either way. */
    fun take(current: AppIcon): AppIcon? {
        val icon = store.load()?.let { name -> AppIcon.entries.firstOrNull { it.name == name } } // an unknown name is ignored
        store.save(null)
        return icon?.takeIf { it != current }
    }
}

/** Whether picking [picked] queues its icon: only a change of theme, so re-tapping the chosen one keeps a hand-picked icon. */
fun shouldQueueThemeIcon(picked: AppTheme, selected: AppTheme): Boolean = picked != selected

/**
 * Whether the pending icon may be switched on now. Only when the app really went out of sight: not a rotation, Herald's
 * own activity still the top of its task (not a picker, camera or browser in front, which the switch would close), and
 * the screen on (a locked screen isn't leaving; the next real leave applies it). An unknown top never flushes.
 */
fun shouldFlushPendingIcon(isChangingConfigurations: Boolean, topPackage: String?, ownPackage: String, interactive: Boolean): Boolean =
    !isChangingConfigurations && topPackage == ownPackage && interactive

/** Reads the icon again after [AppIcons.apply], so [AppIcons.current] follows. */
@Composable
expect fun rememberAppIcons(): AppIcons
