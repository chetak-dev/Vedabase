# Reader speed and appearance — v0.13.0 / code 26

Prepared in response to the remaining reader requests, then **installed on the
phone on 2026-09-26** after the user explicitly requested “install it now”.

## Phone installation

- Verified RMX3286 / CUNF7LFETKMJQOCY at 192.168.1.10:41691.
- In-place app-only update succeeded; installed version confirmed as 0.13.0 / 26.
- Original imported library retained at 1,101,299,712 bytes; bookmark file SHA-256
  unchanged across installation. No uninstall, data clearing or new import.
- MainActivity launched successfully for review. No phone instrumentation tests
  or automated navigation were performed; validation below used the API35 emulator.

## Reading windows and motion

- Production now uses `readerWindow` / `readWindow` rather than loading the entire
  chapter's text into one document. Windows target 12 complete verses, 48,000
  encoded bytes or 384 source rows; an indivisible entry can stand alone under the
  unchanged hard limits. Boundaries are deterministic, not dependent on the tap.
- Complete source order, neighbouring resources, combined verses, search/bookmark
  paragraph anchors and Back navigation are preserved. Legacy full-resource APIs
  remain available and have separate cache entries.
- **Screen-page breaks are not guaranteed identical**: pagination restarts at
  window boundaries, so a window's final page can be shorter. No verse text is
  omitted or rewritten. Scrolling-mode boundary navigation follows the existing
  previous/next-resource behavior, now with smaller resources.
- Native intra-document motion is 150ms (previously 210ms); resource transitions
  are 170ms (previously 230ms). Existing visual fences, input gates and reduced-
  motion behavior remain. This is not a claim of zero dropped frames on every
  device or an exact reproduction/benchmark of Gitabase.
- Repeated appearance changes retain a stable logical paragraph/fragment instead
  of cumulatively rounding the first-visible fragment and drifting backwards.
  An interrupted real turn retains its accepted anchor separately from reflow.

### Actual Madhya 1 comparison

Read-only emulator diagnostic, same source and separate cold reader instances:

| Metric | Full chapter | First reading window |
|---|---:|---:|
| Complete verses | 284 | 12 |
| Source rows | 2,757 | 123 |
| Encoded source bytes | 305,601 | 11,653 |
| Generated HTML bytes | 704,165 | 38,181 |
| Cold data preparation | 828ms | 789ms |

Warm window data preparation was 1.8ms in this run. These timings do not isolate
OS caches and are not phone tap-to-paint measurements. Cold full-book metadata
validation still happens; the main benefit is ~95% less HTML to lay out initially.

## Unified Reading settings

The footer's Aa label is replaced by a settings icon. One scrollable panel has:

- Text size 80–150% (16–30px baseline choices), +/- controls, slider and Default.
- Light, Sepia and Dark book themes, with explicit selected indicators.
- Line spacing 85–150% of the original typography, +/- in 5% steps and Default.
- Fixed Back/Done controls and the existing bilingual Hare Krishna preview.

Settings persist independently and apply to both reader modes. Resizing and
spacing/paper changes use the existing WebView; no database query, source rewrite,
new reading document, or URL reload is required. Only a closed app-owned head style
is updated; source scripts and network access remain blocked. Native backgrounds,
reader controls, contents/settings dialogs and system-bar icon contrast follow the
chosen paper. Leaving restores the app's bar appearance.

## App and search appearance

- The app/library uses brighter warm-white paper, pale containers and a clearer
  brown header with explicit Material foreground/background roles.
- Search filters are unfilled/paper-coloured when unselected; selected filters
  use brown fill, white text and a checkmark. Behavior and search data are unchanged.
- Initial and retained-page loading feedback reads **Hare Krishna!** followed by
  **Your eBook is loading**, with a spinner. Fast turns do not immediately flash
  the retained-page loading indicator.

## Validation

- Final build/lint passed and **301 JVM tests passed**, zero failures/errors.
- **179 distinct selected Android checks passed locally across focused runs**:
  native style/reflow 6, reading windows 17 (including actual Madhya source),
  preferences 7, search appearance 4, settings panel 9, settings integration 6,
  search/bookmark navigation 20, reader cleanup 4, zoom 3, fullscreen 9, local
  visual capture 1, library 10, bookmarks 25, offline WebView 12, search UI 46.
  This is not a claim that the entire historical instrumentation suite was run.
- Native tests verify unchanged document URL/source markup during theme/spacing
  changes, retained paragraph/fraction, no duplicate render callbacks, color and
  line-height results, cancellation/coalescing, and preserved script/network guards.
- The slider now exposes a 56dp accessible wrapper with the real bounded progress
  action; Material's inner track was smaller than 48dp despite outer height. Drag
  and accessibility adjustment tests both pass.
- Android 15 enforces transparent system-bar colors for target 35. Tests check that
  platform behavior plus actual icon contrast, reader palettes and exit restoration,
  rather than incorrectly requiring a nontransparent bar color on that platform.
- Earlier fullscreen checks required a previous paragraph tail to stay visible
  after repagination. They now check the stable logical reading intent, retaining
  independent first-visible DOM/native/controller agreement and unchanged document
  identity. This avoids the cumulative backward drift reproduced during settings.
- An earlier long emulator batch was aborted by an Android system-service crash.
  It was rerun in shorter successful batches; the aborted run is not counted as a
  successful test run.
- Actual Light/Dark Madhya windows and the unified settings panel were captured
  and reviewed locally. The phone remained on v0.12.0 throughout local validation;
  the subsequently authorized installation is recorded above. The staged corpus
  was read-only and is not bundled.
- Final source and staged-copy SHA-256 hashes matched the original supplied corpus.
  The temporary emulator corpus and screenshot copies were removed, and the local
  emulator was stopped. Local review images are retained outside the application.

Local review images: [Light reader](../.reference/reader-window-light.png),
[Dark reader](../.reference/reader-window-dark.png),
[Font settings](../.reference/reader-settings-font.png),
[Theme and spacing](../.reference/reader-settings-dark-spacing.png).

## Checklist against the latest reader requests

1. Smaller initial verse windows: implemented; the page-break tradeoff above is explicit.
2. Faster horizontal turns: 150ms within a resource, 170ms between resources.
3. Clear selected/unselected search filters: implemented, with selected checkmarks.
4. Smoother scrolling: less reader layout work and stable reflows; universal frame-rate
   guarantees and direct Gitabase speed equivalence are not claimed.
5. “Hare Krishna!” / “Your eBook is loading” with spinner: implemented.
6. User-selectable book themes: Light, Sepia, Dark; persist across sessions.
7. Line spacing and combined settings: implemented alongside font size, each with defaults.
8. Brighter application palette: implemented independently of the chosen book theme.

## Scope limits

These changes address the latest reader-speed/settings/colour requests. They do
not add hyperlink navigation, audio, export/sync, or remove safety limits for truly
oversized/malformed sections. Device-wide smoothness requires physical-device
review; native gestures outside reader page turns have not been globally replaced.