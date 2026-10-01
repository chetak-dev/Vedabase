# Reader styling review — v0.9.3 / code 13

Installed on the authorized RMX3286 on 2026-09-23. This checkpoint changes styling
only; existing page turns, controls, fonts and source data remain.

- Verse mode: Synonyms summary and its disclosure marker are centered; it remains
  initially collapsed and expandable.
- Book mode: synonyms always flow as visible text, with no disclosure or visible
  heading. Original heading text and record IDs remain in the HTML, hidden only
  when source metadata identifies a real synonyms heading. Ordinary body words
  are not hidden.
- Default reading font size is 20px, down from 24px, in both modes. Android text
  scaling remains respected; saved custom preferences are not overwritten.
- Removed horizontal reader padding and the maximum-width restriction. Book
  columns still use an exact full-viewport pitch with zero column gap.

## Verified

- Build/lint passed; 157 JVM tests passed.
- All 23 focused phone checks passed in 55.448 seconds: four Book styling/anchor
  checks, a resize/zoom regression, native Verse disclosure, and 17 Verse reader
  regressions. The entire historical instrumentation suite was not rerun.
- Real BG 1.1 visually checked in both modes:
  [Verse](../.reference/vedareader-v093-verse.png) and
  [Book](../.reference/vedareader-v093-book.png).
- Device reports version 0.9.3/code13. Existing private UUID library remains
  1,101,299,712 bytes; no clearing, uninstall, duplicate import or source edits.

Wait for user review before the next feature.