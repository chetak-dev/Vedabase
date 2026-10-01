# Reader design and loading checkpoint — v0.7.0

2026-09-23. User rejected the earlier ivory/green design and requested a closer
Gitabase-style reader plus investigation of slow loading. Connected via authorized
USB debugging; no phone settings, source database, or original app data were changed.

## Visual changes

- Reference pixels sampled from user-authorized screenshots: brown header `#7b5549`,
  reader paper `#faf6da`, chapter background `#f7dfb5`.
- One shared palette for native UI and HTML; dark blue-grey reader ink and amber
  italic verse/transliteration text. Default body size 24px, system font scale retained.
- Compact chapter context header and flat, full-width sans-serif translation rows.
  No rounded verse cards, oversized repeated heading, breadcrumbs, or bottom tabs
  on the chapter screen; Back and Library remain accessible in its header.
- Compact reader toolbar with existing previous/next and Back controls. Removed the
  permanent swipe-instruction strip; gestures remain available and tested.
- In whole-verse mode, only explicit, level-compatible duplicate source labels
  (verse reference / TEXT number / TEXT heading) are hidden by presentation CSS.
  Source text, record IDs, Unicode, and database bytes remain untouched. Chapter
  reading and other content do not receive this label treatment.
- No proprietary icons, fonts, book artwork or gallery images copied from Gitabase.

## Measured performance

Opt-in `ReaderPerformanceCheck` ran against the **same already imported database on
the RMX3286 phone**, using the first Bhagavad-gita verse chapter. Three sequential
measurements before and after, with no database writes or cache clearing:

| Stage | Before, milliseconds | After, milliseconds |
|---|---|---|
| Chapter overview (39 verse entries) | 6234.0 / 4112.4 / 4299.7 | 43.2 / 9.1 / 12.2 |
| First verse data | 12.4 / 4.6 / 4.3 | 14.9 / 3.9 / 5.0 |
| HTML generation | 30.6 / 96.8 / 25.0 | 72.6 / 27.0 / 32.7 |

These are **data-loading/render-document stages, not total tap-to-paint latency**.
They are not a controlled benchmark of Gitabase itself or cold OS/storage caches.
The actionable bottleneck was chapter translation extraction on Android SQLite.
Desktop Node/SQLite timings did not reproduce the multi-second delay, which is why
the on-device measurement was necessary.

The previous multi-table range join was replaced with a single indexed text scan
restricted to the chapter's record range. Sorted, validated verse ranges assign
translations in one linear pass. Non-verse/nested-work rows remain excluded; row,
individual-text, and aggregate-size limits remain enforced without silent truncation.

## Verification

- APK and Android test APK built; lint passed (existing non-blocking warnings remain).
- 81 JVM tests passed.
- 57 phone regression tests passed: extraction, chapter UI, reader, generic browsing,
  library UI, and WebView/resource restrictions.
- One opt-in real-library performance diagnostic passed after the change.
- Installed `org.vedareader` v0.7.0/code7 with data-preserving update.
- Existing private imported IVD retained: 1,101,299,712 bytes.
- Real Chapter 1 → Bg 1.1 → Back flow inspected on the phone.

Screenshots:
- [Chapter redesign](../.reference/vedareader-warm-chapter.png)
- [Verse reader redesign](../.reference/vedareader-warm-reader.png)

Next step is user review of this checkpoint, not additional unrelated features.