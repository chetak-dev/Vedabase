package org.vedareader.reader

import android.net.Uri
import org.vedareader.data.LinkQuery
import org.vedareader.data.ReaderLink
import org.vedareader.data.ReaderLinkKind

/** Maps the app-generated `https://reader.local/links/<kind>?target=` anchors back to typed links. */
object ReaderLinks {
    private val kinds = mapOf("jump" to ReaderLinkKind.JUMP, "query" to ReaderLinkKind.QUERY,
        "popup" to ReaderLinkKind.POPUP, "audio" to ReaderLinkKind.AUDIO)

    fun parse(url: Uri?): ReaderLink? {
        if (url == null || url.scheme != "https" || url.host != "reader.local") return null
        val segments = url.pathSegments
        if (segments.size != 2 || segments[0] != "links") return null
        val kind = kinds[segments[1]] ?: return null
        val target = try { url.getQueryParameter("target") } catch (_: UnsupportedOperationException) { null }
        if (target.isNullOrEmpty() || target.length > LinkQuery.MAX_TARGET_CHARACTERS ||
            target.any { it.isISOControl() }) return null
        return ReaderLink(kind, target)
    }
}
