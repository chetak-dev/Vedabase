# Outlined text cards — v0.9.7 / code 17

Installed on the authorized RMX3286 on 2026-09-24. This small checkpoint addresses
the requested chapter-style boundaries for text/verse entries, without reader changes.

- Verse previews now use the chapter cards' 16dp rounded corners, 1dp theme outline,
  surface fill, 16dp interior padding and 12dp spacing.
- Full original translations, missing-translation hints, combined references and
  source-record keys remain unchanged. The whole card—including padding—is tappable.
- Existing one-tap reader opening and Back/list-position restoration are preserved.

Validation: build, JVM suite and lint passed; all 22 focused chapter/browse phone
tests passed in 49.008 seconds. The new test checks padding, spacing and an actual
touch in the card's empty inset. Real Gita Chapter 1 was inspected visually:
[installed cards](../.reference/vedareader-v097-text-cards.png).

Device version 0.9.7/code17 confirmed; private UUID library remains 1,101,299,712
bytes. No data clearing, uninstall, import or source edits. Phone left on the chapter
overview for user review.

Still pending: animation tuning, fullscreen including system bars, expandable chapter
contents, and a closer/exact font identification. Wait for feedback before the next chunk.