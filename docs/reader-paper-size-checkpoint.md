# Reader paper, text size and legacy formatting — v0.10.0 / code 20

Installed on the authorized RMX3286 on 2026-09-24. Changes are scoped to the reader
and the requested formatting cleanup, not the library theme.

## Text size

- Bottom **Aa** control opens a rounded text-size dialog with preview, smaller/larger
  buttons, an 80–150% slider, Default (100%) and Done.
- Base size is 20px; selectable size is 16–30, combined with Android's font scaling.
  Toolbar/dialog text does not scale with the reader preference. Slider changes apply
  on release rather than per pixel; button changes apply one step at a time.
- Stored separately in user preferences, shared by paged and scrolling modes. No
  source writes or database reload on resize. Existing native views reflow around the
  painted paragraph (approximate within-paragraph position, not exact line guarantees).

## Reader-only appearance

- Warm paper `#F3EBDD`, dark ink `#342E27`, subdued tan header `#E7DCC9`, brown actions.
- Applies to reader content, controls, dialogs and reader system-bar appearance.
  Library/chapter colours stay unchanged; prior bar appearance restores on leaving.
- Flat paper rather than distracting textures. Body/label contrast tests meet 4.5:1.
  This is a visual preference, not a medical guarantee against eye strain; brightness
  and ambient lighting still matter.

## Formatting audit

- Read-only streaming audit visited **1,474,456 source rows**.
- `UN+`/`UN`/`UN-` now produce underline; `SO+`/`SO`/`SO-` produce strikeout, with
  balanced formatting and independent per-record state.
- Handles audited hard spaces, soft hyphens, validated spacing/shading metadata,
  presentation/note/hidden markers, parameterized tables, header rows, and quoted
  link kinds. Metadata is not displayed as raw angle-bracket codes.
- External/program/command controls remain inert; their visible labels are preserved.
  Hidden/note bodies are retained rather than silently discarded. Unknown/malformed
  tags and literal angle-bracket text stay safely escaped, not blanket-deleted.
- Source database and Unicode content remain untouched. This is coverage of the
  identified directives, not a claim that every unknown legacy construct is supported.

## Validation

- Build/lint and **196 JVM tests** passed.
- Final **38 selected phone checks passed** in 104.308 seconds: panel controls and
  persistence, reader-theme isolation/bar restoration, native legacy markup, zoom and
  position preservation, fullscreen, and existing reader regressions.
- Theme fixture corrected to use native MATCH_PARENT layout params, like production:
  direct Compose AndroidView with WRAP_CONTENT can collapse WebView viewport-height
  CSS despite a nonzero measured size. Dialog density assertions compare its own
  Android window before/after rather than assuming a parent's synthetic density.
- Package v0.10.0/code20 and private UUID library size **1,101,299,712 bytes** confirmed.
  No clearing, uninstall or duplicate import. Live manual taps were stopped when the
  user changed foreground screens; no final real-text screenshot was claimed.

Pause for review before separate page-animation tuning or another feature.