package org.vedareader.reader

import org.json.JSONArray
import org.json.JSONObject
import org.vedareader.data.HighlightColor
import org.vedareader.data.HighlightSelection
import org.vedareader.data.HighlightSpan

/**
 * Closed, app-owned DOM queries for user highlights. Offsets index a record's plain text, so a
 * span is independent of theme, size, or the search-hit marks that share the same records.
 * Nothing here installs handlers, timers, or bridges; scripts run only through evaluateJavascript.
 */
internal object ReaderHighlights {
    const val MARK_CLASS = "user-hl"
    const val ID_ATTRIBUTE = "data-hl"

    /** Unwraps any earlier user marks and paints the given spans; returns the applied count. */
    fun apply(spans: List<HighlightSpan>): String {
        val list = JSONArray()
        for (span in spans) {
            list.put(JSONObject().put("id", span.id).put("record", span.record).put("start", span.start)
                .put("endRecord", span.endRecord).put("end", span.end).put("color", span.color.name.lowercase())
                .put("noted", span.noted).put("highlighted", span.highlighted))
        }
        return "($APPLY)($list)"
    }

    /** The current selection in record/offset coordinates, or null when nothing is selected. */
    val SELECTION: String get() = "($SELECTION_RANGE)()"

    /** The highlight id under a native point, or null. Same viewport mapping as the hit test. */
    fun at(x: Int, y: Int, width: Int, height: Int): String =
        "($HIGHLIGHT_AT)(${x.coerceIn(0, width.coerceAtLeast(0))}," +
            "${y.coerceIn(0, height.coerceAtLeast(0))},${width.coerceAtLeast(1)},${height.coerceAtLeast(1)})"

    /** Parses an evaluateJavascript result of [SELECTION]; rejects malformed or empty ranges. */
    fun parseSelection(result: String?): HighlightSelection? {
        if (result.isNullOrBlank() || result == "null") return null
        val value = runCatching { JSONObject(result) }.getOrNull() ?: return null
        val record = value.optInt("record", -1)
        val endRecord = value.optInt("endRecord", -1)
        val start = value.optInt("start", -1)
        val end = value.optInt("end", -1)
        val text = value.optString("text").trim()
        if (record <= 0 || endRecord < record || start < 0 || end < 0 || text.isEmpty()) return null
        val selection = HighlightSelection(record, start, endRecord, end, text)
        return selection.takeUnless { it.isEmpty }
    }

    /** Parses an evaluateJavascript result of [at]. */
    fun parseId(result: String?): String? {
        if (result.isNullOrBlank() || result == "null") return null
        val id = runCatching { JSONArray("[$result]").getString(0) }.getOrNull() ?: return null
        return id.takeIf { it.isNotBlank() && it.length <= 128 }
    }

    fun colorClass(color: HighlightColor) = "hl-" + color.name.lowercase()

    private val APPLY = """
        function(list) {
            var flow = document.querySelector('main.book-flow') || document.body;
            if (!flow) return 0;
            var marks = flow.querySelectorAll('mark.$MARK_CLASS');
            // Unwrap descendants first: normalizing an outer mark while a nested mark is still
            // in the static NodeList can leave stale nodes behind on overlapping repaints.
            for (var m = marks.length - 1; m >= 0; m--) {
                var mark = marks[m], parent = mark.parentNode;
                if (!parent) continue;
                while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
                parent.removeChild(mark);
            }
            flow.normalize();
            var records = flow.querySelectorAll('.record[data-record]');
            var byId = {}, order = [];
            for (var r = 0; r < records.length; r++) {
                var raw = records[r].getAttribute('data-record'), id = Number(raw);
                if (!Number.isSafeInteger(id) || id <= 0 || String(id) !== raw || byId[id]) continue;
                byId[id] = records[r];
                order.push(id);
            }
            function wrap(element, from, to, cls, hid) {
                var walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT), node, nodes = [];
                while ((node = walker.nextNode())) nodes.push(node);
                var pos = 0;
                for (var n = 0; n < nodes.length; n++) {
                    var text = nodes[n], len = text.data.length, a = pos, b = pos + len;
                    pos = b;
                    if (b <= from || a >= to || len === 0) continue;
                    var s = Math.max(from, a) - a, e = Math.min(to, b) - a;
                    if (e <= s) continue;
                    var target = text;
                    if (e < len) target.splitText(e);
                    if (s > 0) target = target.splitText(s);
                    var wrapper = document.createElement('mark');
                    wrapper.className = '$MARK_CLASS ' + cls;
                    wrapper.setAttribute('$ID_ATTRIBUTE', hid);
                    target.parentNode.insertBefore(wrapper, target);
                    wrapper.appendChild(target);
                }
            }
            var applied = 0;
            for (var h = 0; h < list.length; h++) {
                var x = list[h];
                if (x.highlighted === false && !x.noted) continue;
                var si = order.indexOf(x.record), ei = order.indexOf(x.endRecord);
                if (si < 0 || ei < 0 || ei < si) continue;
                for (var k = si; k <= ei; k++) {
                    var element = byId[order[k]];
                    var from = k === si ? x.start : 0;
                    var to = k === ei ? x.end : element.textContent.length;
                    wrap(element, from, to, 'hl-' + x.color + (x.noted ? ' has-note' : '') +
                        (x.highlighted === false ? ' note-only' : ''), String(x.id));
                }
                applied++;
            }
            return applied;
        }
    """.trimIndent()

    private val SELECTION_RANGE = """
        function() {
            var selection = window.getSelection();
            if (!selection || selection.isCollapsed || selection.rangeCount < 1) return null;
            var range = selection.getRangeAt(0);
            var flow = document.querySelector('main.book-flow');
            if (!flow) return null;
            var records = flow.querySelectorAll('.record[data-record]'), hit = [];
            for (var i = 0; i < records.length; i++) {
                if (range.intersectsNode(records[i])) hit.push(records[i]);
            }
            if (!hit.length) return null;
            function recordOf(node) {
                var element = node.nodeType === 1 ? node : node.parentElement;
                return element ? element.closest('.record[data-record]') : null;
            }
            function offsetIn(record, container, offset) {
                var probe = document.createRange();
                probe.selectNodeContents(record);
                probe.setEnd(container, offset);
                return probe.toString().length;
            }
            var first = hit[0], last = hit[hit.length - 1];
            var start = recordOf(range.startContainer) === first ? offsetIn(first, range.startContainer, range.startOffset) : 0;
            var end = recordOf(range.endContainer) === last ? offsetIn(last, range.endContainer, range.endOffset) : last.textContent.length;
            var text = range.toString().replace(/\s+/g, ' ').trim();
            if (text.length > 1000) text = text.slice(0, 997) + '...';
            return {record: Number(first.getAttribute('data-record')), start: start,
                endRecord: Number(last.getAttribute('data-record')), end: end, text: text};
        }
    """.trimIndent()

    private val HIGHLIGHT_AT = """
        function(x, y, nativeWidth, nativeHeight) {
            var viewport = window.visualViewport;
            var width = viewport ? viewport.width : window.innerWidth;
            var height = viewport ? viewport.height : window.innerHeight;
            var vx = Math.min(x * width / nativeWidth, width - 1);
            var vy = Math.min(y * height / nativeHeight, height - 1);
            var element = document.elementFromPoint(vx + (viewport ? viewport.offsetLeft : 0),
                vy + (viewport ? viewport.offsetTop : 0));
            var mark = element ? element.closest('mark.$MARK_CLASS') : null;
            return mark ? mark.getAttribute('$ID_ATTRIBUTE') : null;
        }
    """.trimIndent()
}
