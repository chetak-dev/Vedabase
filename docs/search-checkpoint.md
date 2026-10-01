# Offline scoped search — v0.11.0 / code 23

Search was authorized as an autonomous feature while the user was away on
2026-09-25. This build includes the previously prepared v0.10.2 compact footer
and loading feedback. Installed on the physical RMX3286 on 2026-09-25 after the
user requested installation. Package version 0.11.0/code23 was confirmed and the
app launched; the existing private 1,101,299,712-byte library remains present.

## Search experience

- Search from the library tab, a book/chapter header, or the reader's search icon.
  A contextual open selects the narrowest independent source book containing the
  current passage. A fresh library search starts with the entire active database;
  reopening retains the user's existing search and scope.
- The searchable source picker supports one book, several books/collections, or
  **All database**. Nested independent books are separately selectable; overlapping
  selections do not duplicate results. Apply/Cancel stages changes locally.
  Apply and Close are fixed at the top so they remain reachable while filtering
  with the keyboard open; the keyboard's Done action dismisses input.
- Autocomplete begins after two characters and a 250ms typing pause. Suggestions
  come from the local word/reference index, are checked against the selected scope,
  and preserve preceding words. Selecting a suggestion fills and submits it.
- **All words** finds paragraphs containing every word, in any order.
  **Exact phrase** requires consecutive words in order, including repetitions.
  Both ignore case and Latin transliteration accents, retain Indic marks, and
  match whole words. Punctuation separates words; this is not regex or substring
  matching. Searches do not span paragraph boundaries.
- Direct references include BG 2.8, SB 1.2.3, CC Ādi 1.2 and combined verse ranges.
  Numeric dots are significant. Unknown reference formats remain ordinary queries.
- Results show original source titles and plain-text contextual snippets with
  matching terms emphasized. Tap opens the exact source paragraph in the existing
  reader. Back returns to the saved result-list position; underlying browse state
  is unchanged. Closing search without selecting a result restores the reader.
- Eight recent successful queries are stored locally per imported database and
  can be cleared. Nothing is sent online. A different imported database resets
  query/scope/results rather than accidentally reusing another database's IDs.
- Cancellation, loading, validation, timeout, retry and no-results states are
  distinct. Results are in original database order. “Passages shown” is not a
  fabricated full match count. **Show more** uses a stable scanned-record cursor.

## Source and performance constraints

The implementation reads existing Vedabase indexes; it does not scan all plain
text on each keystroke, build an alternate corpus, modify the IVD, or require a
network permission. The supplied source has 798 selectable deduplicated source
book/collection records under this catalog rule.

- Up to 8 words / 160 characters per submitted query, validated without silently
  truncating. Root and nested work IDs are validated against original metadata.
- Six-byte posting records are decoded in 98,304-byte chunks. Query candidates
  use bounded BitSets with a four-entry / 8MiB cache; scope filtering precedes
  result truncation. Source records and offsets are range checked.
- Up to 30 hits per page and 1,000 candidate paragraphs verified per page. A page
  can have no verified hits but still have a continuation cursor; the UI explains
  this rather than claiming exhaustion. Suggestions are bounded best-effort,
  not an exhaustive dictionary or fuzzy spelling correction.
- SQLite operations run off-main with cancellation signals and a 15-second read
  deadline. Search-controller generation guards reject late results after edits,
  cancellation, navigation or library changes. Cached state owns immutable data.
- Plain paragraphs are bounded at 131,072 source bytes. Malformed indexes and
  oversized paragraphs fail explicitly, not as a false empty result. No safety
  limits from the reader were removed.

## Reader coordinate fix found during integration

Deep search-result opens reproduced a real pre-existing fractional-density bug:
native scrollY 17,534 corresponded to visual pageTop 6,679.619 at native scale
2.625, while nativeWidth/visualWidth estimated 2.620148 and disagreed by ~32px.
That approximation could retry until the reader's render deadline expired.

CSS/native conversion now uses actual WebView content scale. Horizontal pages use
the measured authored column pitch and round the complete absolute offset once,
not a rounded step multiplied repeatedly. Geometry remains fenced and checked;
timeouts, CSS, font, source anchors and animation timings were not relaxed.
`BookPageInfo.nativeOffset` is the exact horizontal position; `viewportWidth` is
only the rounded single-page step. This fixes the reproduced drift, not every
reported animation/performance or oversized-section issue.

## Book-reader reference research

Accessible official Apple documentation confirms:

- [Read books](https://support.apple.com/guide/books/read-books-ibks5f526382/mac):
  in-book word/phrase/page-number searching and returning after a multipage jump.
- [Find books and audiobooks](https://support.apple.com/guide/books/find-and-buy-books-and-audiobooks-ibks1d8b1c37/mac):
  suggestions while typing and submitting a suggestion or Return.

Discovery/store search is different from searching every book's full text. These
sources do not prove highlighted result snippets or strict phrase semantics;
those are original design choices here. Kobo, Google Play Books, calibre and
KOReader documentation requests were blocked by the corporate gateway, which was
not bypassed. No proprietary interface assets, code or book content were copied.

Potential later additions: transient reader-side match highlights, next/previous
search result, saved searches, relevance ranking and typo-tolerant suggestions.
Currently highlighting is in result snippets, not inside the opened reader.

## Validation

- Final APK/test APK build and lint succeeded; **247 JVM tests passed**, zero
  failures/errors. The final test-only build completed in 24 seconds.
- **106 distinct selected Android checks passed across focused batches** on the
  isolated API35 emulator: search engine 20, search UI 30, real route/view-model
  navigation 11, history 6, native scale 3, fullscreen 9, zoom 3, visible reference
  3, footer cleanup 4, contents dialog 5, library 10, real-corpus diagnostic 1,
  local screenshot capture 1. The final UI-only rerun passed all 30 after fixing
  keyboard reachability and a subpixel touch-target assertion.
- A large earlier batch exposed a preview-emulator/Espresso incompatibility and
  later Android system-service crashes. Stable API35, host GPU and smaller batches
  were used; aborted runs were not counted as successful. Reader test-side
  width-ratio assumptions were corrected alongside the production geometry fix.
- Last real-corpus run: catalog 798 sources in 350ms; six representative first-page
  searches 30–502ms; three suggestion queries 12–52ms. These are emulator query
  timings with uncontrolled cache state, **not phone tap-to-paint promises**.
- The source and staged emulator copy both retained SHA-256
  `b36498fd8afdf52b1a7e136523afa4442cfaa2c3b3bd5f4ed88b7830c4a1e2ae`.
  The staged copy was never registered/imported into the app or packaged in an APK.
  Synthetic tests also verify unchanged source hashes/schema and isolated prefs.
- Final home, results, suggestions and keyboard-open picker screenshots were
  inspected locally. Captures wait for native window quiet/fresh frames, since
  Compose idleness alone can capture a stale page or an animating dialog.
- Physical RMX3286 installation is complete; physical-device review remains pending.
  Only the production APK was installed over the existing app. No uninstall,
  data clear or duplicate library import; the test counts above are emulator checks.

Local preview artifacts (ignored; original corpus excerpts, not distributable
app assets): [results](../.reference/search-results.png),
[suggestions](../.reference/search-suggestions.png),
[book picker](../.reference/search-books.png).

## Suggested review

1. Library → Search → choose one book, then several, then All database.
2. Type `devot` or `BG 2.` and select a completion.
3. Compare All words and Exact phrase for `devotional service`.
4. Open a result, switch reader modes if desired, then Back to the same results.
5. Try Show more, a no-match query, Cancel, and clearing recent queries.
6. Open search from a book/chapter or reader to check its automatic scope.

Remaining unrelated review items: Transcend-style brighter colours, general
animation tuning, other loading failures, oversized sections and hyperlink routing.