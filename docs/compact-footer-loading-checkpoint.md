# Compact footer and loading feedback — v0.10.2 / code 22

Prepared on 2026-09-25. **Not installed or phone-tested**: the phone was not
connected, and the user chose “Prepare it without installing for now.”
The last verified installed version remains v0.10.1/code21.

## First review chunk

- Replaced the footer's visible “Book contents” text with a three-line icon.
  Its accessible name stays “Book contents” and its touch target is 48dp.
- Moved the compact reading percentage onto the same action row, between the
  contents icon and Aa. Corner arrows and fullscreen remain. The thin progress
  bar stays above the row; percentage meaning remains approximate source-record
  progress, not printed pages.
- Contents preparation and selected-chapter lookup now run on Dispatchers.Default
  rather than during UI composition. The dialog can show “Preparing contents…”
  with a spinner and remain dismissible. Errors offer Retry and Close; cancelled
  or replaced dialogs do not publish late results.
- A retained page now gets a small “Preparing pages…” indicator if its loading
  or native-busy state lasts over 300ms. This covers slow mode changes as well as
  resource loads. The indicator overlays content rather than changing viewport
  size; existing input gates stay in place. Short turns avoid immediate flashing.
- No palette, source database, link routing, resource-size limits or animation
  engine changes in this checkpoint. Moving preparation off the UI thread is
  not a measured end-to-end speed improvement or a fix for every reported hang.

## Validation

- Final build including both APKs and lint passed in 30 seconds.
- 200 JVM tests passed; no failures/errors/skips in the checked reports.
- Instrumentation compiled but **was not run**. Updated footer/fullscreen geometry
  assertions; added held-load mode-feedback, background contents, cancellation and
  retry/selected-root checks. Run ReaderCleanupTest, BookContentsDialogTest and
  ReaderFullscreenTest on the unlocked authorized phone before review handoff.
- Compose lint flagged a produceState value-assignment check despite explicit
  assignments. Using remembered state with LaunchedEffect preserves cancellation
  and worker dispatch without suppressing the rule; final lint passed.

## Remaining requests — separate review checkpoints

1. Brighter colours similar to Transcend; inspect the reference before selecting
   colours. Do not assume the user wants the current dim palette retained.
2. Diagnose unstable horizontal page animations and intermittent slow loads /
   “section could not be loaded” failures with real failing sections and timings.
3. Safely support currently oversized sections without silently dropping source
   text or simply removing memory limits.
4. Implement source hyperlink navigation; current link handling is intentionally
   inert and is not fixed by loading indicators. Preserve local-resource security.

Keep these incremental. Review this footer/loading chunk before another batch.