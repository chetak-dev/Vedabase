package org.vedareader.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.vedareader.R

/** UI fonts only; unsupported glyphs (including Devanagari) use platform fallback. */
val AppFontFamily = FontFamily(
    Font(R.font.fira_sans_condensed_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.fira_sans_condensed_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.fira_sans_condensed_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.fira_sans_condensed_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.fira_sans_condensed_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.fira_sans_condensed_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

/** Serif family matching the reader's Book typeface (Literata variable fonts cover all weights). */
val BookFontFamily = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_regular, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.literata_italic, FontWeight.Bold, FontStyle.Italic),
)

/** Keep every Material 3 default metric and weight; change only the font family. */
val AppTypography = Typography().let { defaults ->
    defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = AppFontFamily),
        displayMedium = defaults.displayMedium.copy(fontFamily = AppFontFamily),
        displaySmall = defaults.displaySmall.copy(fontFamily = AppFontFamily),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = AppFontFamily),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = AppFontFamily),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = AppFontFamily),
        titleLarge = defaults.titleLarge.copy(fontFamily = AppFontFamily),
        titleMedium = defaults.titleMedium.copy(fontFamily = AppFontFamily),
        titleSmall = defaults.titleSmall.copy(fontFamily = AppFontFamily),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = AppFontFamily),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = AppFontFamily),
        bodySmall = defaults.bodySmall.copy(fontFamily = AppFontFamily),
        labelLarge = defaults.labelLarge.copy(fontFamily = AppFontFamily),
        labelMedium = defaults.labelMedium.copy(fontFamily = AppFontFamily),
        labelSmall = defaults.labelSmall.copy(fontFamily = AppFontFamily),
    )
}