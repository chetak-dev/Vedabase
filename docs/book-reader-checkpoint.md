# Two reading styles — v0.9.0 / code 10

Installed and reopened on the authorized phone, 2026-09-23.

## Review scope

- Header toggle displays the current **Verse** or **Book** mode. Tap to switch.
  The preference survives reopening the reader/app. Back is an arrow again.
- Verse mode retains complete-verse horizontal swipes and vertical purport scrolling.
- Book mode first advances a viewport through the current text (with 8% overlap),
  then continues into the next source section. Previous returns to the end of the
  preceding section. Vertical scrolling remains available; this is not an EPUB
  screen-column paginator or one enormous continuously mounted book document.
- Switching at the same complete verse preserves/clamps the native scroll offset.
  For differently sized prose chunks, the original source section is reopened; pixel
  offsets are not misapplied to different text ranges. In-memory position is not a
  new guarantee of process-death reading-location restoration.
- Book contents opens a source-ordered jump list, including chapters, verses and
  front matter. Progress is approximate source-record progress, not printed pages.
  Safe-area footer stays above the system navigation bar, with wrapping labels.
- Combined Gita references display as **BG 1.16 - 18**, including in chapter rows
  and reader titles. Unknown/discontinuous/other-book forms remain unchanged;
  original database titles are never rewritten.
- Generic book contents groups verified front matter under one expandable
  **Introduction** row. Original entries/IDs/navigation and saved expansion remain.
- Home's “Your library” heading is removed. Existing VedaReader brand header remains.

## Source and boundaries

`IvdBookReader` constructs a bounded metadata outline, then reads only the current
entry. At most two native WebViews exist during a transition. Prose is chunked at
record boundaries; complete verses keep their ranges and hard size guards.
Generation/cancellation checks protect against stale loads and mode/library switches.
JavaScript, arbitrary network/file access and untrusted document navigation stay off.

Real metadata mattered: Gita front matter is `LE_Chapter`, not `LE_Section`, and its
appendices are `LE_Canto`. Recognized front matter before the first ordinary chapter
can be grouped only when it has no direct semantic verse children. Cantos are normally
subdivisions: Gita's chapter/canto mixture stays one book, while a canto-only collection
can be scoped to its selected canto. Independent nested Book/Collection boundaries
remain protected; unsafe outer anthology reads fail explicitly.

## Validation

- Build and Android lint succeeded; **133 JVM tests** passed.
- Final combined selected phone suite: **144 tests passed**, 166.312 seconds.
  Covers native page stepping, verse mode, toggles, contents jumps, Back/list state,
  enlarged-text edge-to-edge footer, preferences, source boundaries, grouping and
  prior reader/home regressions. Not every historical instrumentation class was run.
- Explicit opt-in real-library diagnostic confirmed **11,636 unique Gita source
  records**, covered exactly once across **706 entries**, including **657 verse entries**,
  all four front-matter sections, the postscript and both appendix cantos.
- A measured run took ~304 ms to build the uncached outline and ~1.8–2.1 ms for repeat
  verse reads. These are repository-stage timings with OS caches not reset, not
  tap-to-paint measurements or a benchmark against Transcend.
- Live checks: title-free home; collapsed/expanded Introduction; BG 1.1 toggle in
  both directions; BG 1.16 - 18 title; visible Book contents/progress footer.
  [Book view](../.reference/vedareader-book-mode.png),
  [combined verse](../.reference/vedareader-combined-book-mode.png),
  [Introduction](../.reference/vedareader-introduction-group.png).
- Existing private UUID library remains **1,101,299,712 bytes**. No uninstall, data
  clearing, duplicate import or source modification.

## Validation lessons / limits

- Initial Introduction tests used the test APK's unwritable cache. Fixtures now use
  uniquely owned directories in targetContext.cacheDir; all passed after correction.
- TextLayoutResult's overflow flag reported a subpixel intrinsic-width discrepancy
  for a fully fitting footer label. The regression instead checks measured line bounds
  with one physical pixel rounding tolerance, no ellipsis and no vertical overflow.
- UIAutomator still reports some bottom controls with zero bounds on the live app,
  despite visible screenshots and passing native touch/Compose tests. Do not infer
  invisible pixels or full TalkBack traversal from that dump alone. Locale override
  now sets only locale rather than copying complete pre-window display configuration.
- Exact Transcend body-font matching and its other reader features are not claimed.
  No reference fonts, artwork, code or EPUB assets were copied.
- This remains a review checkpoint: wait for user feedback before unrelated features.