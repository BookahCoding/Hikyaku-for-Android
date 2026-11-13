package dev.hermeskotlin.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.DEFAULT_THEME
import dev.hermeskotlin.designsystem.AppTheme
import dev.hermeskotlin.designsystem.LocalAppTheme
import dev.hermeskotlin.designsystem.components.LocalGlow
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.ui.LocalAppSettings

/** Synthetic callers supply state; rendering and no-op actions are the screenshot preview's real ChatView. */
@Composable
fun PerformanceChatPreview(state: ChatState, dark: Boolean = true) {
    val palette = AppTheme.named(DEFAULT_THEME)
    hermesTheme(palette)(if (dark) ColorScheme.Dark else ColorScheme.Light) {
        CompositionLocalProvider(
            LocalAppTheme provides palette,
            LocalAppSettings provides AppSettings(showPet = false),
            LocalGlow provides true,
        ) {
            SampleChat(state)
        }
    }
}
