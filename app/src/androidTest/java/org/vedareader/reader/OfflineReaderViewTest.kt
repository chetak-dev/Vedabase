package org.vedareader.reader

import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.ContentObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/** Resource policy checks use in-memory bytes only; none of these URIs are ever navigated to. */
@RunWith(AndroidJUnit4::class)
class OfflineReaderViewTest {
    // Complete, CRC-checked 1x1 RGBA PNG, including IHDR, compressed IDAT, and IEND.
    private val png = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )
    private val objectUri = Uri.parse("https://reader.local/objects/fixture.png")

    @Test
    fun canonicalEncodedObjectRouteDecodesUnicodeNameAndReturnsTheOriginalPng() {
        val name = "नमूना বাংলা ā a\u0304 +#?%/图.png"
        val names = mutableListOf<String>()

        val response = ReaderResources.response(
            Uri.parse("https://reader.local/objects/${Uri.encode(name)}"), "GET", false,
        ) { decoded ->
            names += decoded
            ContentObject("image/png", png)
        }

        assertEquals(listOf(name), names)
        assertPngResponse(response, png)
    }

    @Test
    fun foreignSchemesUserInfoPortsAndLookalikeHostsNeverCallTheImageLoader() {
        val urls = listOf(
            "https://example.invalid/objects/fixture.png",
            "http://example.invalid/objects/fixture.png",
            "http://reader.local/objects/fixture.png",
            "file:///objects/fixture.png",
            "file:///android_asset/fixture.png",
            "content://reader.local/objects/fixture.png",
            "javascript:void(0)",
            "data:image/png;base64,ignored",
            "ftp://reader.local/objects/fixture.png",
            "/objects/fixture.png",
            "//reader.local/objects/fixture.png",
            "https://user@reader.local/objects/fixture.png",
            "https://user:ignored@reader.local/objects/fixture.png",
            "https://reader.local@example.invalid/objects/fixture.png",
            "https://reader.local:443/objects/fixture.png",
            "https://reader.local:8443/objects/fixture.png",
            "https://reader.local:/objects/fixture.png",
            "https://reader.local.example.invalid/objects/fixture.png",
            "https://notreader.local/objects/fixture.png",
            "https://reader.local./objects/fixture.png",
            "https://reader%2Elocal/objects/fixture.png",
            "https://127.0.0.1/objects/fixture.png",
        )

        urls.forEach { assertRouteBlocked(it) }
    }

    @Test
    fun queryFragmentRootAndNonObjectPathsNeverCallTheImageLoader() {
        val urls = listOf(
            "https://reader.local",
            "https://reader.local/",
            "https://reader.local/objects",
            "https://reader.local/object/fixture.png",
            "https://reader.local/objects-other/fixture.png",
            "https://reader.local/Objects/fixture.png",
            "https://reader.local/assets/fixture.png",
            "https://reader.local/fonts/fixture.png",
            "https://reader.local/links/jump",
            "https://reader.local/other/../objects/fixture.png",
            "https://reader.local/%6fbjects/fixture.png",
            "https://reader.local/objects%2Ffixture.png",
            "https://reader.local/objects/fixture.png?query=1",
            "https://reader.local/objects/fixture.png?",
            "https://reader.local/objects/fixture.png#fragment",
            "https://reader.local/objects/fixture.png#",
            "https://reader.local/objects/fixture.png?query=1#fragment",
        )

        urls.forEach { assertRouteBlocked(it) }
    }

    @Test
    fun blankNulAndOverlongDecodedObjectNamesAreRejectedBeforeLoading() {
        val names = listOf("", " \t\n", "\u0000", "prefix\u0000.png", "x".repeat(513), "ā".repeat(513))

        names.forEach { assertRouteBlocked("https://reader.local/objects/${Uri.encode(it)}") }
    }

    @Test
    fun onlyExactGetSubresourceRequestsCanReachTheLoader() {
        for (method in listOf("POST", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS", "CONNECT", "TRACE", "get", "Get", "", " GET", "GET ")) {
            assertRouteBlocked(objectUri.toString(), method = method)
        }
        assertRouteBlocked(objectUri.toString(), mainFrame = true)
    }

    @Test
    fun nullAndThrowingLoadersReturnAnEmptyBlockedResponse() {
        for (throwFailure in listOf(false, true)) {
            val names = mutableListOf<String>()
            val response = ReaderResources.response(objectUri, "GET", false) { name ->
                names += name
                if (throwFailure) error("Synthetic image loader failure")
                null
            }

            assertEquals(listOf("fixture.png"), names)
            assertDenied(response, "Loader throws: $throwFailure")
        }
    }

    @Test
    fun pngBytesCannotBeServedAsAnotherRasterOrAnUnsupportedMimeType() {
        val mimeTypes = listOf(
            "image/jpeg", "image/gif", "image/bmp", "image/webp",
            "text/html", "image/svg+xml", "application/javascript", "application/octet-stream", "audio/mpeg",
        )

        mimeTypes.forEach { assertContentBlocked(it, ContentObject(it, png)) }
    }

    @Test
    fun emptyTruncatedCorruptAndActiveContentCannotMasqueradeAsRasterImages() {
        val html = "<html><script>throw 'synthetic rejected content'</script></html>".toByteArray(Charsets.UTF_8)
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>throw 'synthetic'</script></svg>".toByteArray(Charsets.UTF_8)
        for (mime in listOf("image/png", "image/jpeg", "image/gif", "image/bmp", "image/webp")) {
            assertContentBlocked("Empty $mime", ContentObject(mime, ByteArray(0)))
            assertContentBlocked("HTML labelled $mime", ContentObject(mime, html))
            assertContentBlocked("SVG labelled $mime", ContentObject(mime, svg))
        }
        assertContentBlocked("Active HTML", ContentObject("text/html", html))
        assertContentBlocked("Active SVG", ContentObject("image/svg+xml", svg))
        assertContentBlocked("Truncated PNG signature", ContentObject("image/png", png.copyOf(7)))
        assertContentBlocked("Corrupt PNG signature", ContentObject("image/png", png.copyOf().apply { this[0] = 0 }))
        assertContentBlocked("Truncated JPEG signature", ContentObject("image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        assertContentBlocked("Truncated GIF signature", ContentObject("image/gif", "GIF".toByteArray(Charsets.US_ASCII)))
        assertContentBlocked("Truncated BMP signature", ContentObject("image/bmp", byteArrayOf(66)))
        for (header in listOf("RIFF1234WEB", "RIFF1234NOPE", "NOPE1234WEBP")) {
            assertContentBlocked("Invalid WebP $header", ContentObject("image/webp", header.toByteArray(Charsets.US_ASCII)))
        }
    }

    @Test
    fun oneMebibyteIsAnInclusiveLimitAndAnOversizedImageLeaksNoBytes() {
        val limit = 1024 * 1024
        val atLimit = pngWithSize(limit)
        val overLimit = pngWithSize(limit + 1)
        val names = mutableListOf<String>()

        val accepted = ReaderResources.response(objectUri, "GET", false) { name ->
            names += name
            ContentObject("image/png", atLimit)
        }
        assertEquals(limit, atLimit.size)
        assertPngResponse(accepted, atLimit)

        val denied = ReaderResources.response(objectUri, "GET", false) { name ->
            names += name
            ContentObject("image/png", overLimit)
        }
        assertEquals(limit + 1, overLimit.size)
        assertDenied(denied, "One byte over the image limit")
        assertEquals(listOf("fixture.png", "fixture.png"), names)
    }

    @Test
    @Suppress("DEPRECATION")
    fun webViewSettingsDisableScriptStorageFileContentNetworkAndMixedContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = OfflineReaderView(instrumentation.targetContext)
            try {
                with(view.settings) {
                    assertFalse(javaScriptEnabled)
                    assertFalse(javaScriptCanOpenWindowsAutomatically)
                    assertFalse(domStorageEnabled)
                    assertFalse(databaseEnabled)
                    assertFalse(allowFileAccess)
                    assertFalse(allowContentAccess)
                    assertFalse(allowFileAccessFromFileURLs)
                    assertFalse(allowUniversalAccessFromFileURLs)
                    assertTrue(blockNetworkLoads)
                    assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
                    assertEquals(WebSettings.LOAD_NO_CACHE, cacheMode)
                    assertTrue(mediaPlaybackRequiresUserGesture)
                    assertFalse(supportMultipleWindows())
                    assertFalse(supportZoom())
                }
            } finally {
                // No document is loaded in this settings test; even assertion failures release the view.
                view.release()
            }
        }
    }

    @Test
    fun nativeDetailsTapExpandsWithScriptsOffAndNeverBecomesVerseNavigation() {
        val ready = CountDownLatch(1)
        val expanded = CountDownLatch(1)
        val rendered = AtomicInteger()
        val navigation = AtomicInteger()
        val links = AtomicInteger()
        val html = """
            <!doctype html><html><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Reader</title><style>body{margin:0}summary{height:80px;padding:0 16px;line-height:80px}</style>
            </head><body><details><summary>Synonyms</summary>
            <div style="height:3000px">Synthetic native synonyms नमूना ā.</div>
            </details></body></html>
        """.trimIndent()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var reader: OfflineReaderView? = null
            try {
                scenario.onActivity { activity ->
                    val view = OfflineReaderView(activity)
                    reader = view
                    view.canNextUnit = true
                    view.canPreviousUnit = true
                    view.onNextUnit = { navigation.incrementAndGet() }
                    view.onPreviousUnit = { navigation.incrementAndGet() }
                    view.onLinkUnavailable = { links.incrementAndGet() }
                    view.onRendered = { rendered.incrementAndGet(); ready.countDown() }
                    activity.setContentView(view)
                    view.render("native-details", html, 0) { null }
                }
                assertTrue("Native visual-state callback must report the ready document", ready.await(10, TimeUnit.SECONDS))
                var closedHeight = 0
                scenario.onActivity {
                    val view = requireNotNull(reader)
                    assertFalse(view.settings.javaScriptEnabled)
                    assertEquals(1, rendered.get())
                    closedHeight = view.contentHeight
                    val now = SystemClock.uptimeMillis()
                    val x = 40f * view.resources.displayMetrics.density
                    val y = 30f * view.resources.displayMetrics.density
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now, action, x, y, 0)
                        view.dispatchTouchEvent(event)
                        event.recycle()
                    }
                    // Fence the native tap's layout without JavaScript, sleeps, or replacing the client.
                    view.postVisualStateCallback(99L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { expanded.countDown() }
                    })
                    view.invalidate()
                }
                assertTrue("Native details expansion must paint", expanded.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    val view = requireNotNull(reader)
                    assertTrue("The summary tap must reveal the native details content", view.contentHeight > closedHeight)
                    assertEquals(0, navigation.get())
                    assertEquals(0, links.get())
                    assertEquals(1, rendered.get())
                    assertFalse(view.settings.javaScriptEnabled)
                }
            } finally {
                scenario.onActivity {
                    reader?.let { view ->
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.release()
                    }
                }
            }
        }
    }

    @Test
    fun onlyTheLatestDocumentReportsRenderedAndReleaseIsIdempotent() {
        val ready = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val callbacks = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var reader: OfflineReaderView? = null
            try {
                scenario.onActivity { activity ->
                    val view = OfflineReaderView(activity)
                    reader = view
                    activity.setContentView(view)
                    view.onRendered = { callbacks.incrementAndGet(); ready.countDown() }
                    view.render("superseded", "<html><head><title>Old</title></head><body>Old fixture</body></html>", 0) { null }
                    view.render("latest", "<html><head><title>Latest</title></head><body>Latest fixture</body></html>", 0) { null }
                }
                assertTrue("The latest document must become visually ready", ready.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    val view = requireNotNull(reader)
                    assertEquals("Latest", view.title)
                    assertEquals(1, callbacks.get())
                    view.render("latest", "Ignored duplicate render", 0) { null }
                    view.postVisualStateCallback(99L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { drained.countDown() }
                    })
                    view.invalidate()
                }
                assertTrue("The unchanged document must remain ready", drained.await(10, TimeUnit.SECONDS))
                assertEquals(1, callbacks.get())
            } finally {
                scenario.onActivity {
                    reader?.let { view ->
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.release()
                        view.release()
                        view.onRendered()
                        assertTrue(view.isReleased)
                    }
                }
            }
            assertEquals(1, callbacks.get())
        }
    }

    private fun assertRouteBlocked(url: String, method: String = "GET", mainFrame: Boolean = false) {
        var calls = 0
        val response = ReaderResources.response(Uri.parse(url), method, mainFrame) {
            calls++
            // A valid result prevents a mistakenly invoked loader from hiding behind content rejection.
            ContentObject("image/png", png)
        }
        val label = "$method $url (mainFrame=$mainFrame)"
        assertEquals("Blocked routes must not consult the image loader: $label", 0, calls)
        assertDenied(response, label)
    }

    private fun assertContentBlocked(label: String, content: ContentObject) {
        val names = mutableListOf<String>()
        val response = ReaderResources.response(objectUri, "GET", false) { name ->
            names += name
            content
        }
        assertEquals(label, listOf("fixture.png"), names)
        assertDenied(response, label)
    }

    private fun assertDenied(response: WebResourceResponse, label: String) {
        assertEquals(label, 403, response.statusCode)
        assertEquals(label, "Blocked", response.reasonPhrase)
        assertEquals(label, "text/plain", response.mimeType)
        assertEquals(label, "UTF-8", response.encoding)
        assertEquals(label, "no-store", response.responseHeaders?.get("Cache-Control"))
        requireNotNull(response.data).use { assertEquals("Denied response must be empty: $label", -1, it.read()) }
    }

    private fun assertPngResponse(response: WebResourceResponse, expected: ByteArray) {
        assertEquals(200, response.statusCode)
        assertEquals("OK", response.reasonPhrase)
        assertEquals("image/png", response.mimeType)
        assertNull(response.encoding)
        assertEquals("no-store", response.responseHeaders?.get("Cache-Control"))
        assertEquals("nosniff", response.responseHeaders?.get("X-Content-Type-Options"))
        requireNotNull(response.data).use { assertArrayEquals(expected, it.readBytes()) }
    }

    /** Add a valid ancillary tEXt chunk before IEND, rather than inventing a magic-header-only image. */
    private fun pngWithSize(size: Int): ByteArray {
        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val text = ByteArray(size - png.size - 12) { 'x'.code.toByte() }
        "Fixture\u0000".toByteArray(Charsets.US_ASCII).copyInto(text)
        val checksum = CRC32().apply { update(type); update(text) }
        val output = ByteArrayOutputStream(size)
        DataOutputStream(output).use {
            it.write(png, 0, png.size - 12)
            it.writeInt(text.size)
            it.write(type)
            it.write(text)
            it.writeInt(checksum.value.toInt())
            it.write(png, png.size - 12, 12)
        }
        return output.toByteArray()
    }
}