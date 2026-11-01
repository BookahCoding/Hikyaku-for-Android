package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The themes with a look of their own besides Ukiyo-e (which lives in [AppTheme]). Each brings its own neutrals for
 * light, dark and pure black; the colours were checked against ThemesTest's contrast rules. A theme's accent is its fill
 * and its text alike, as the plain presets do.
 */
internal object Themes {
    private val OnDark = Color(0xFF0A0A0C)

    private fun k(rgb: Long) = Color(0xFF000000L or rgb)

    private fun light(
        bg: Long, surface: Long, surface2: Long, surface3: Long, well: Long,
        text: Long, text2: Long, text4: Long, danger: Long, success: Long, warning: Long,
    ): Neutrals {
        val t = k(text); val b = k(bg); val s = k(surface)
        return Neutrals(
            bg = b, surface = s, surface2 = k(surface2), surface3 = k(surface3), well = k(well), thumb = s, elevated = s,
            line = t.copy(alpha = 0.12f), lineStrong = t.copy(alpha = 0.20f),
            text = t, text2 = k(text2), text4 = k(text4), text5 = lerp(k(text4), b, 0.35f),
            inverse = t, onInverse = s, danger = k(danger), success = k(success), warning = k(warning),
        )
    }

    private fun dark(
        bg: Long, surface: Long, surface2: Long, surface3: Long, well: Long, elevated: Long,
        text: Long, text2: Long, text4: Long, danger: Long, success: Long, warning: Long,
    ): Neutrals {
        val t = k(text); val b = k(bg)
        return Neutrals(
            bg = b, surface = k(surface), surface2 = k(surface2), surface3 = k(surface3), well = k(well),
            thumb = lerp(k(surface3), t, 0.10f), elevated = k(elevated),
            line = t.copy(alpha = 0.08f), lineStrong = t.copy(alpha = 0.14f),
            text = t, text2 = k(text2), text4 = k(text4), text5 = lerp(k(text4), b, 0.35f),
            inverse = t, onInverse = b, danger = k(danger), success = k(success), warning = k(warning),
        )
    }

    /** Pure black: the grounds pulled 45% toward black, the text and signals as in dark. */
    private fun black(d: Neutrals): Neutrals {
        fun toBlack(c: Color) = lerp(c, Color.Black, 0.45f)
        return Neutrals(
            bg = Color.Black, surface = toBlack(d.surface), surface2 = toBlack(d.surface2), surface3 = toBlack(d.surface3),
            well = toBlack(d.well), thumb = d.thumb, elevated = toBlack(d.elevated),
            line = d.line, lineStrong = d.lineStrong, text = d.text, text2 = d.text2, text4 = d.text4, text5 = d.text5,
            inverse = d.inverse, onInverse = d.onInverse, danger = d.danger, success = d.success, warning = d.warning,
        )
    }

    /** [lightSecondary] / [darkSecondary] give the theme a second colour; without them it is the accent itself. */
    private fun theme(
        name: String, light: Neutrals, dark: Neutrals, lightAccent: Long, darkAccent: Long,
        lightSecondary: Long? = null, darkSecondary: Long? = null,
    ): AppTheme {
        fun colors(fill: Color, on: Color, n: Neutrals, soft: Float, secondary: Long?): ThemeVariant {
            val s = secondary?.let(::k)
            return ThemeVariant(
                fill, on, text = fill, soft = fill.copy(alpha = soft), neutrals = n,
                secondary = s ?: fill, onSecondary = on, secondaryText = s ?: fill, secondarySoft = (s ?: fill).copy(alpha = soft),
            )
        }
        val l = k(lightAccent); val d = k(darkAccent)
        return AppTheme(
            name,
            light = colors(l, Color.White, light, 0.10f, lightSecondary),
            dark = colors(d, OnDark, dark, 0.14f, darkSecondary),
            black = colors(d, OnDark, black(dark), 0.14f, darkSecondary),
        )
    }

    /** "Great Wave": foam cream and Prussian blue, from the Kanagawa print. */
    val GreatWave: AppTheme = theme(
        "Great Wave",
        light(0xF3EBD8, 0xFAF4E4, 0xEEE5CE, 0xE4D9BD, 0xEFE7D2, text = 0x1B2A41, text2 = 0x2A3B55, text4 = 0x4F5F78, danger = 0xA32E2A, success = 0x2A6347, warning = 0x7A4F00),
        dark(0x0B1A33, 0x12213F, 0x182B4D, 0x20375E, 0x0D1B36, 0x14264A, text = 0xEAE3CF, text2 = 0xD2CCBA, text4 = 0xA5ABBA, danger = 0xF08A7C, success = 0x7FCBA0, warning = 0xE3B458),
        lightAccent = 0x1F4E8C, darkAccent = 0x7FB0E8,
    )

    /** "Red Fuji": dawn pink and warm charcoal, with the mountain's red, from "Fine Wind, Clear Morning". */
    val RedFuji: AppTheme = theme(
        "Red Fuji",
        light(0xF7E9E1, 0xFDF3EC, 0xF1E0D6, 0xE8D3C6, 0xF3E4DA, text = 0x2A1F1D, text2 = 0x3D2E2B, text4 = 0x6B5550, danger = 0x7A1F4A, success = 0x2F5E3C, warning = 0x7A4F00),
        dark(0x1C1614, 0x251D1A, 0x2E2420, 0x3A2D28, 0x181210, 0x271F1C, text = 0xF2E4DA, text2 = 0xDCCDC2, text4 = 0xB5A398, danger = 0xF2799B, success = 0x8CC79A, warning = 0xE6B85C),
        lightAccent = 0xA33524, darkAccent = 0xE8826A,
    )

    /** "Thirty-six Views": celadon and pine night, from the green of Hokusai's landscapes. */
    val ThirtySixViews: AppTheme = theme(
        "Thirty-six Views",
        light(0xE6EDDF, 0xF1F6EB, 0xDDE6D4, 0xD1DCC6, 0xE2EADA, text = 0x1F2A22, text2 = 0x2D3B31, text4 = 0x4A5E52, danger = 0x9C2B2B, success = 0x285F45, warning = 0x7A5200),
        dark(0x0F1A14, 0x16241C, 0x1D2F24, 0x26402F, 0x0B140F, 0x182820, text = 0xE4EDE0, text2 = 0xCBD7C6, text4 = 0x9DB0A2, danger = 0xF28B7D, success = 0x6FBF8F, warning = 0xE2B24F),
        lightAccent = 0x27614A, darkAccent = 0x6FBF8F,
        lightSecondary = 0x7A5200, darkSecondary = 0xD4A03E,
    )

    /** "Shibuya Night": the crossing after dark: near-black violet, hot magenta. Flat, no glow. */
    val Shibuya: AppTheme = theme(
        "Shibuya Night",
        light(0xF6F1FF, 0xFCF9FF, 0xEDE5FB, 0xE2D8F6, 0xF1EAFD, text = 0x1B1233, text2 = 0x2C2250, text4 = 0x5A4E7C, danger = 0xB3202B, success = 0x1F6B55, warning = 0x855608),
        dark(0x0A0716, 0x130E26, 0x1B1535, 0x261E48, 0x070510, 0x171130, text = 0xF3EEFF, text2 = 0xDAD2F0, text4 = 0xB1A6D1, danger = 0xFF6B6B, success = 0x3EE0B0, warning = 0xFFC857),
        lightAccent = 0xA9136A, darkAccent = 0xFF7CC0,
        lightSecondary = 0x00677B, darkSecondary = 0x22E3FF,
    )

    /** "Akihabara": black-blue and sign-green, the electric town. */
    val Akihabara: AppTheme = theme(
        "Akihabara",
        light(0xEEF2F5, 0xF8FAFC, 0xE3E9EE, 0xD6DFE6, 0xE8EDF1, text = 0x0E1A24, text2 = 0x1E2D3A, text4 = 0x48596A, danger = 0xB3202B, success = 0x0B6B3A, warning = 0x855608),
        dark(0x060B12, 0x0C1520, 0x12202F, 0x1A2D42, 0x04080D, 0x0E1A28, text = 0xE9F4F1, text2 = 0xCFE0DC, text4 = 0x9FB4B0, danger = 0xFF6B7A, success = 0x2BE37A, warning = 0xFFC857),
        lightAccent = 0x06703A, darkAccent = 0x2BE37A,
        lightSecondary = 0x5A2DD0, darkSecondary = 0xA080FF,
    )
}
