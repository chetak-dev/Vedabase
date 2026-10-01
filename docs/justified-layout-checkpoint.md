# Justified reader layout — v0.9.4 / code 14

Installed on the authorized phone on 2026-09-23 as a layout-only review checkpoint.

- Restored 16px left/right reading gutters in both modes. Book columns retain their
  exact full-viewport pitch; padding is on top-level source records/verse groups,
  never doubled on nested records or lost on continuation fragments.
- Ordinary prose, translations, synonyms and purports use inter-word justification.
  Final paragraph lines are not stretched. Centered verse lines/headings and explicit
  source alignment remain preserved.
- Book verse groups and paragraphs may split across columns; widows/orphans are one
  line so available space is used before continuing on the next page. Headings still
  stay with following text. This does not force-fill final chapter pages or stretch
  text vertically, and ordinary paragraph spacing remains.
- 20px default, Literata, synonyms rules, page-turn mechanism and controls unchanged.

## Validation

- Build/lint and 158 JVM tests passed.
- All 22 selected phone checks passed in 59.298 seconds: margin/default checks,
  measured word-edge justification, actual verse splitting, long-paragraph turns,
  visible Book synonyms and 17 Verse reader regressions.
- Actual Gita purport visually checked with gutters and aligned body edges:
  [installed layout](../.reference/vedareader-v094-justified-book.png).
- Device version 0.9.4/code14 confirmed. Private UUID library remains 1,101,299,712
  bytes; no clearing, uninstall, reimport or source modification.

Wait for feedback before further changes.