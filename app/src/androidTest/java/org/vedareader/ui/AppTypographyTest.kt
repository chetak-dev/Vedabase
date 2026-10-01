package org.vedareader.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.vedareader.R
import org.xmlpull.v1.XmlPullParser

/** Typography/resource checks only; no library data or reader controls are changed. */
class AppTypographyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allFifteenStylesChangeOnlyTheFontFamily() {
        val defaults = styles(Typography())
        val actual = styles(AppTypography)
        assertEquals(15, actual.size)
        assertEquals(defaults.keys, actual.keys)
        defaults.forEach { (name, style) ->
            assertEquals(name, AppFontFamily, actual.getValue(name).fontFamily)
            assertEquals(name, style.copy(fontFamily = AppFontFamily), actual.getValue(name))
        }
    }

    @Test
    fun composeAndNativeFamiliesDeclareSixMatchingFacesAndResourcesLoad() {
        val faces = listOf(
            Triple(R.font.fira_sans_condensed_regular, FontWeight.Normal, FontStyle.Normal),
            Triple(R.font.fira_sans_condensed_italic, FontWeight.Normal, FontStyle.Italic),
            Triple(R.font.fira_sans_condensed_medium, FontWeight.Medium, FontStyle.Normal),
            Triple(R.font.fira_sans_condensed_semibold, FontWeight.SemiBold, FontStyle.Normal),
            Triple(R.font.fira_sans_condensed_bold, FontWeight.Bold, FontStyle.Normal),
            Triple(R.font.fira_sans_condensed_bold_italic, FontWeight.Bold, FontStyle.Italic),
        )
        assertEquals(FontFamily(faces.map { (resource, weight, style) -> Font(resource, weight, style) }), AppFontFamily)

        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val nativeFaces = mutableListOf<Triple<Int, Int, Int>>()
        resources.getXml(R.font.app_font_family).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "font") {
                    nativeFaces += Triple(
                        parser.getAttributeResourceValue(androidNamespace, "font", 0),
                        parser.getAttributeIntValue(androidNamespace, "fontWeight", -1),
                        parser.getAttributeIntValue(androidNamespace, "fontStyle", -1),
                    )
                }
            }
        }
        assertEquals(faces.map { (resource, weight, style) ->
            Triple(resource, weight.weight, if (style == FontStyle.Italic) 1 else 0)
        }, nativeFaces)
        (faces.map { it.first } + listOf(R.font.app_font_family, R.font.noto_sans_devanagari)).forEach { resource ->
            assertNotNull(resources.getResourceEntryName(resource), resources.getFont(resource))
        }
        // Loading Noto does not assert that the platform selects it for missing UI glyphs.
    }

    @Test
    fun themeInstallsTypographyAndPreservesDefaultLocalTextMetrics() {
        var defaultStyle: TextStyle? = null
        var appStyle: TextStyle? = null
        var themedTypography: Typography? = null
        compose.setContent {
            MaterialTheme {
                val style = LocalTextStyle.current
                SideEffect { defaultStyle = style }
            }
            VedaPreviewTheme {
                val style = LocalTextStyle.current
                val typography = MaterialTheme.typography
                SideEffect {
                    appStyle = style
                    themedTypography = typography
                }
            }
        }
        compose.runOnIdle {
            assertEquals(AppTypography, themedTypography)
            assertEquals(requireNotNull(defaultStyle).copy(fontFamily = AppFontFamily), appStyle)
        }
    }

    private fun styles(typography: Typography): Map<String, TextStyle> = with(typography) {
        mapOf(
            "displayLarge" to displayLarge,
            "displayMedium" to displayMedium,
            "displaySmall" to displaySmall,
            "headlineLarge" to headlineLarge,
            "headlineMedium" to headlineMedium,
            "headlineSmall" to headlineSmall,
            "titleLarge" to titleLarge,
            "titleMedium" to titleMedium,
            "titleSmall" to titleSmall,
            "bodyLarge" to bodyLarge,
            "bodyMedium" to bodyMedium,
            "bodySmall" to bodySmall,
            "labelLarge" to labelLarge,
            "labelMedium" to labelMedium,
            "labelSmall" to labelSmall,
        )
    }
}