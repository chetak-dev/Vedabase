# Page-turn-only review — v0.9.2 / code 12

Installed on the authorized RMX3286 on 2026-09-23, after approval of the font preview.

## Included

- Book mode uses fixed-height horizontal CSS columns, not viewport-sized vertical scrolling.
- Bounded chapter resources contain consecutive source verses: a page may contain the end
  of one verse and the beginning of another. Source records are not truncated or rewritten.
- Selected text opens at its containing page. Backward chapter navigation lands on the last
  page. Painted source anchors drive the existing title, contents selection and mode toggle.
- Verse mode, Literata, brown header, Back arrow, mode toggle and existing percentage/footer
  controls remain. `ReviewedReaderScreen` stays the active route. The immersive controls,
  seek slider and automatic chrome hiding in `ReaderScreen` remain inactive drafts.
- Progress is approximate source progress, not printed pages; it can stay unchanged across
  several pages of the same paragraph.

## Validation

- Build/lint and 154 JVM tests passed.
- Two native smoke checks passed after correcting Android viewport coordinates.
- Focused 50-test run: 49 passed; selected-anchor test initially used layout-viewport
  bounds rather than visual-viewport bounds. Corrected that assertion; its isolated rerun
  passed. This covers 52 distinct selected checks, not the entire draft instrumentation suite.
- Coverage includes contiguous verses, selected/final-page anchors, source-script blocking,
  local font/resource restrictions, native synonyms, Verse regressions, 27 resource tests
  and the explicit read-only real-library resource check.
- Live Gita page before/after one horizontal swipe visually checked:
  [first page](../.reference/vedareader-fixed-page-one.png),
  [next page](../.reference/vedareader-fixed-page-two.png).
- Live Book → Verse → Book toggle checked. Installed version 0.9.2/code12 confirmed;
  original private UUID database remains 1,101,299,712 bytes.

## Important implementation details

- Fixed columns need document-owned horizontal overflow. Applying overflow-y:hidden to both
  root/body made a nested scroller and broke native/DOM agreement. Full viewport-width
  columns, zero column gap and internal 20px gutters give an exact native page pitch.
- Android WebView can pan `visualViewport.pageLeft` while `window.scrollX` stays zero.
  Measurement uses visual page offsets; visibility/hit tests subtract visual viewport offsets
  from layout-relative rectangles. Source anchor absolute positions still use layout scrollX.
- JavaScript remains off in Verse mode. Book mode enables only closed app-authored layout
  queries via native evaluation; response CSP denies source scripts, frames and connections.
  No JavaScript interface/bridge or arbitrary local/network access is added.

Stop for feedback before implementing another control or layout change.