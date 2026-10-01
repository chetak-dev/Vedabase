package org.vedareader.reader

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A painted CSS-column page, not a source verse or a printed-book page number.
 * [page] is zero based; [count] is at least one. [viewportWidth] is the rounded native pixel
 * page step (not CSS pixels or necessarily View.width). [nativeOffset] is the exact scrollX,
 * rounded AFTER multiplying the unrounded step by [page]; do not multiply [viewportWidth].
 * [anchorRecord] is the first visible source record, or
 * zero for an empty document. The host should display page + 1, and persist the record anchor.
 * [referenceRecord] is display-only: the first fully visible verse heading, else [anchorRecord].
 */
data class BookPageInfo(
    val page: Int, val count: Int, val anchorRecord: Int, val viewportWidth: Int,
    val referenceRecord: Int = anchorRecord,
    val nativeOffset: Int = BookPagination.offset(page, viewportWidth),
)

/** Retain an approximate position *within* a multi-column paragraph when the layout reflows. */
internal data class BookAnchor(val record: Int, val fragment: Int = 0, val fragments: Int = 1)

/**
 * Pure geometry and closed, app-authored DOM queries. No source text/URLs enter these scripts.
 * Folio owns the CSS: main.book-flow has a fixed viewport height and a 100vw column pitch,
 * with gutters inside that pitch. The document (not a nested scroller) owns horizontal overflow.
 * These queries never install CSS, scripts, handlers, timers, bridges, or network requests.
 */
internal object BookPagination {
    val CSP = "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; " +
        "img-src https://reader.local/objects/; " +
        "font-src " + ReferenceTypography.CSP_FONTS + "; " +
        "connect-src 'none'; frame-src 'none'; child-src 'none'; object-src 'none'; " +
        "media-src 'none'; worker-src 'none'; base-uri https://reader.local; form-action 'none'"

    const val HIT_SELECTION = 1
    const val HIT_INTERACTIVE = 2
    const val HIT_SUMMARY = 4
    const val HIT_OUTSIDE = 8
    const val HIT_HIGHLIGHT = 16

    fun pageCount(scrollWidth: Double, viewportWidth: Double): Int {
        require(scrollWidth.isFinite() && scrollWidth >= 0 && viewportWidth.isFinite() && viewportWidth > 0)
        // scrollWidth is integer CSS px, whereas 100vw can be fractional. Do not invent an
        // empty final column for the browser's <= 1 CSS-pixel rounding of the overflow extent.
        return ceil((scrollWidth - 1.0).coerceAtLeast(viewportWidth) / viewportWidth)
            .coerceIn(1.0, Int.MAX_VALUE.toDouble()).toInt()
    }

    fun offset(page: Int, viewportWidth: Int): Int {
        require(page >= 0 && viewportWidth > 0)
        return Math.multiplyExact(page, viewportWidth)
    }

    /** CSS coordinates use WebView's content scale, never nativeWidth / visualViewport.width. */
    fun nativeOffset(cssOffset: Double, contentScale: Double): Int {
        require(cssOffset.isFinite() && cssOffset >= 0 && contentScale.isFinite() && contentScale > 0)
        val pixels = cssOffset * contentScale
        require(pixels.isFinite() && pixels <= Int.MAX_VALUE.toDouble())
        return pixels.roundToInt()
    }

    /** Round the absolute offset once: rounding each column first accumulates error at depth. */
    fun offset(page: Int, columnPitch: Double, contentScale: Double): Int {
        require(page >= 0 && columnPitch.isFinite() && columnPitch > 0)
        return nativeOffset(page.toDouble() * columnPitch, contentScale)
    }

    /** The only interpolated arguments are bounded native integers, never paragraph strings. */
    fun measure(anchor: BookAnchor?): String = "($MEASURE)(${anchor?.record?.coerceAtLeast(0) ?: 0}," +
        "${anchor?.fragment?.coerceAtLeast(0) ?: 0},${anchor?.fragments?.coerceAtLeast(1) ?: 1})"

    fun hitTest(x: Int, y: Int, width: Int, height: Int): String =
        "($HIT_TEST)(${x.coerceIn(0, width.coerceAtLeast(0))}," +
            "${y.coerceIn(0, height.coerceAtLeast(0))},${width.coerceAtLeast(1)},${height.coerceAtLeast(1)})"

    const val SELECTION = "(function(){var s=window.getSelection();return !!(s&&!s.isCollapsed);})()"

    /** Reject coercible strings, fractions and native-int overflow rather than silently wrapping. */
    fun parseReferenceRecord(value: Any?): Int? {
        val number = (value as? Number)?.toDouble() ?: return null
        return if (number.isFinite() && number in 0.0..Int.MAX_VALUE.toDouble() &&
            number == number.toInt().toDouble()) number.toInt() else null
    }

    /** Shared by both axes. Source-kind classes, never title-text guesses, identify headings. */
    val VISIBLE_REFERENCE = """
        function(flow, width, height, offsetX, offsetY, fallback) {
            function fullyVisible(r) {
                return r.width > 0 && r.height > 0 && r.left - offsetX >= 0 &&
                    r.right - offsetX <= width && r.top - offsetY >= 0 && r.bottom - offsetY <= height;
            }
            function visibleHeading(e) {
                var boxes = e.getClientRects();
                if (!boxes.length || e.closest('details:not([open])')) return false;
                // A tiny bottom sliver or a later fragment is not a readable heading.
                for (var b = 0; b < boxes.length; b++) {
                    if (!fullyVisible(boxes[b])) return false;
                }
                var walker = document.createTreeWalker(e, NodeFilter.SHOW_TEXT), node, hasText = false;
                while ((node = walker.nextNode())) {
                    if (!node.textContent.trim()) continue;
                    var parent = node.parentElement;
                    if (!parent || parent.closest('.source-book-heading')) continue;
                    var style = getComputedStyle(parent), hidden = style.visibility !== 'visible';
                    for (var ancestor = parent; ancestor && !hidden; ancestor = ancestor.parentElement) {
                        var s = getComputedStyle(ancestor);
                        hidden = s.display === 'none' || Number(s.opacity) === 0;
                    }
                    if (hidden) continue;
                    var range = document.createRange();
                    range.selectNodeContents(node);
                    var lines = range.getClientRects();
                    for (var l = 0; l < lines.length; l++) {
                        if (!(lines[l].width > 0 && lines[l].height > 0)) continue;
                        if (!fullyVisible(lines[l])) return false;
                        hasText = true;
                    }
                }
                return hasText;
            }
            // Deliberately independent of the anchor loop's early exit. DOM/source order wins,
            // including when more than one verse starts below a preceding purport's tail.
            var headings = flow.querySelectorAll('.record.verse-number[data-record],.record.verse-number-heading[data-record]');
            for (var h = 0; h < headings.length; h++) {
                var raw = headings[h].getAttribute('data-record'), record = Number(raw);
                if (Number.isSafeInteger(record) && record > 0 && record <= 2147483647 &&
                    String(record) === raw && visibleHeading(headings[h])) return record;
            }
            return fallback;
        }
    """.trimIndent()

    private val MEASURE = """
        function(anchor, fragment, fragments) {
            var flow = document.querySelector('main.book-flow');
            var root = document.scrollingElement;
            if (!flow || !root) return {error:true};
            var viewport = window.visualViewport;
            var width = viewport ? viewport.width : window.innerWidth;
            var height = viewport ? viewport.height : window.innerHeight;
            // Force style/layout before testing font status; a newly used face may only now load.
            var flowBox = flow.getBoundingClientRect();
            // Folio authors exactly one column, with zero gap/border/padding on the flow.
            // Its used 100vw width is the column pitch; the visual viewport is only the
            // visibility clip and can differ fractionally after Android density rounding.
            var pitch = flowBox.width;
            if (!(width > 0 && height > 0 && pitch > 0 && flowBox.height > 0)) return {ready:false};
            if (document.fonts && document.fonts.status !== 'loaded') return {ready:false};
            // Android WebView can pan its visual viewport while the wider layout viewport
            // remains at scrollX=0. Native scrollX corresponds to pageLeft, not layout scrollX.
            var x = viewport ? viewport.pageLeft : window.scrollX;
            var y = viewport ? viewport.pageTop : window.scrollY;
            var offsetX = viewport ? viewport.offsetLeft : 0;
            var offsetY = viewport ? viewport.offsetTop : 0;
            var extent = Math.max(root.scrollWidth, document.documentElement.scrollWidth,
                document.body ? document.body.scrollWidth : 0, width);
            function recordId(element) {
                var raw = element.getAttribute('data-record');
                var id = Number(raw);
                return Number.isSafeInteger(id) && id > 0 && id <= 2147483647 && String(id) === raw ? id : 0;
            }
            function closedSummary(element) {
                var closed = element.closest('details:not([open])');
                if (!closed) return null;
                var summary = closed.querySelector('summary');
                return summary && element !== summary && !summary.contains(element) ? summary : null;
            }
            function rectangles(element) {
                var result = [], boxes = element.getClientRects();
                for (var i = 0; i < boxes.length; i++) {
                    if (boxes[i].width > 0 && boxes[i].height > 0) result.push(boxes[i]);
                }
                return result;
            }
            var targetPage = -1;
            var target = anchor > 0 ? document.getElementById('record-' + anchor) : null;
            if (target && target.matches('.record[data-record]') && recordId(target) === anchor) {
                target = closedSummary(target) || target;
                var targetRects = rectangles(target);
                if (targetRects.length) {
                    var index = Math.min(targetRects.length - 1,
                        Math.floor(fragment * targetRects.length / Math.max(1, fragments)));
                    targetPage = Math.max(0, Math.floor((targetRects[index].left + window.scrollX + 0.5) / pitch));
                }
            }
            var visible = 0, visibleFragment = 0, visibleFragments = 1;
            var records = flow.querySelectorAll('.record[data-record]');
            for (var r = 0; r < records.length && !visible; r++) {
                var id = recordId(records[r]);
                if (!id) continue;
                var summary = closedSummary(records[r]);
                // A real source summary represents the collapsed group. If Folio supplied an
                // implicit summary for a body-only group, represent it by its first body record
                // instead of inventing a record number or reporting an empty document.
                if (summary && (recordId(summary) || summary.querySelector('.record[data-record]'))) continue;
                var boxes = rectangles(summary || records[r]);
                for (var b = 0; b < boxes.length; b++) {
                    var box = boxes[b];
                    if (box.right - offsetX > 0.5 && box.left - offsetX < width - 0.5 &&
                        box.bottom - offsetY > 0.5 && box.top - offsetY < height - 0.5) {
                        visible = id; visibleFragment = b; visibleFragments = boxes.length;
                        break;
                    }
                }
            }
            var reference = ($VISIBLE_REFERENCE)(flow, width, height, offsetX, offsetY, visible);
            if (document.fonts && document.fonts.status !== 'loaded') return {ready:false};
            return {ready:true, width:width, height:height, pitch:pitch, extent:extent, x:x, y:y,
                targetPage:targetPage, record:visible, fragment:visibleFragment, fragments:visibleFragments,
                referenceRecord:reference};
        }
    """.trimIndent()

    private val HIT_TEST = """
        function(x, y, nativeWidth, nativeHeight) {
            var selection = window.getSelection();
            var result = selection && !selection.isCollapsed ? 1 : 0;
            var viewport = window.visualViewport;
            var width = viewport ? viewport.width : window.innerWidth;
            var height = viewport ? viewport.height : window.innerHeight;
            // Keep one CSS pixel inside the visible edge: elementFromPoint treats the
            // fractional right/bottom boundary as outside, which would drop edge taps.
            var vx = Math.min(x * width / nativeWidth, width - 1);
            var vy = Math.min(y * height / nativeHeight, height - 1);
            var element = document.elementFromPoint(vx + (viewport ? viewport.offsetLeft : 0),
                vy + (viewport ? viewport.offsetTop : 0));
            if (!element) return result | 8;
            // Viewport gutters/background are also chrome taps, not navigation edge zones.
            if (element === document.body || element === document.documentElement) return result;
            if (!element.closest('main.book-flow')) return result | 8;
            if (element.closest('summary')) result |= 4;
            if (element.closest('a,img,summary,button,input,textarea,select,label,audio,video,[contenteditable]')) result |= 2;
            if (element.closest('mark.${ReaderHighlights.MARK_CLASS}')) result |= 16;
            return result;
        }
    """.trimIndent()
}