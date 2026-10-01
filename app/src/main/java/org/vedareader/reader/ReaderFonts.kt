package org.vedareader.reader

import android.content.res.Resources
import android.net.Uri
import android.webkit.WebResourceResponse
import org.vedareader.R

/** Exact passive font URLs only. Native UI and reader share the same bundled font resources. */
object ReaderFonts {
    fun response(uri: Uri, method: String, mainFrame: Boolean, resources: Resources): WebResourceResponse {
        if (mainFrame || method != "GET") return ReaderResources.denied()
        val resource = when (uri.toString()) {
            "https://reader.local/fonts/FiraSansCondensed-Regular.ttf" -> R.font.fira_sans_condensed_regular
            "https://reader.local/fonts/FiraSansCondensed-Italic.ttf" -> R.font.fira_sans_condensed_italic
            "https://reader.local/fonts/FiraSansCondensed-Medium.ttf" -> R.font.fira_sans_condensed_medium
            "https://reader.local/fonts/FiraSansCondensed-SemiBold.ttf" -> R.font.fira_sans_condensed_semibold
            "https://reader.local/fonts/FiraSansCondensed-Bold.ttf" -> R.font.fira_sans_condensed_bold
            "https://reader.local/fonts/FiraSansCondensed-BoldItalic.ttf" -> R.font.fira_sans_condensed_bold_italic
            "https://reader.local/fonts/NotoSerifDevanagari.ttf" -> R.font.noto_serif_devanagari
            else -> return ReaderResources.denied()
        }
        val stream = try { resources.openRawResource(resource) }
            catch (_: Resources.NotFoundException) { return ReaderResources.denied() }
        return WebResourceResponse("font/ttf", null, 200, "OK", mapOf(
            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
        ), stream)
    }
}