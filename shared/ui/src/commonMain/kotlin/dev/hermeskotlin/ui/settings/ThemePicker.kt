package dev.hermeskotlin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.AppTheme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.neutralsFor
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.typography

internal class ThemeSwatch(val background: Color, val surface: Color, val accent: Color, val text: Color)

/** The colours of [palette]'s preview card in the scheme currently in force. */
internal fun themeSwatch(palette: AppTheme, dark: Boolean): ThemeSwatch {
    val colors = if (dark) palette.dark else palette.light
    val n = colors.neutralsFor(dark)
    return ThemeSwatch(background = n.bg, surface = n.surface2, accent = colors.accent, text = n.text)
}

internal fun themeLabel(palette: AppTheme, selected: Boolean): String =
    if (selected) "${palette.name}, selected" else palette.name

/**
 * One card per theme: a small page in the theme's own ground with a surface bar, an accent chip and a text line, the name
 * under it, a ring and a check mark on the picked one (so the pick shows without relying on colour). Cards wrap on a
 * narrow phone, each a 48dp-minimum touch target and a radio for screen readers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemePicker(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    val dark = Theme[colors][background].luminance() < 0.5f
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppTheme.all.forEach { palette ->
            val isSelected = palette == selected
            val swatch = themeSwatch(palette, dark)
            Column(
                Modifier
                    .width(104.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(palette) })
                    .semantics { contentDescription = themeLabel(palette, isSelected) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .clearAndSetSemantics { }
                        .fillMaxWidth().height(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(swatch.background)
                        .border(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) swatch.accent else swatch.text.copy(alpha = 0.18f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(8.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(swatch.surface))
                        Box(Modifier.width(34.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(swatch.accent))
                        Box(Modifier.fillMaxWidth(0.7f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(swatch.text.copy(alpha = 0.7f)))
                    }
                    if (isSelected) Text("✓", color = swatch.accent, modifier = Modifier.align(Alignment.TopEnd))
                }
                Text(
                    palette.name,
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][text],
                    modifier = Modifier.clearAndSetSemantics { },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
