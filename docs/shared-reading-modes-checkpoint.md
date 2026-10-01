# Shared paged/scrolling reader — v0.9.6 / code 16

First small checkpoint from the user's seven-item refinement request. Installed
on the authorized RMX3286 on 2026-09-24; stop for review before the other items.

## Included: shared formatting and icon toggle

- Both modes load the same bounded chapter resource and share book typography,
  headings, visible synonyms, colours, margins and source content. Scrolling mode
  no longer has its former per-verse formatting/disclosure differences.
- The book icon selects horizontal fixed pages; the text-lines icon indicates
  vertical scrolling. The icon-only toggle exposes its state/action to accessibility.
- Both modes offer the existing contents/progress controls. Switching retains a
  source paragraph anchor, not pixels from the other layout. A containing page may
  start before that paragraph; exact line-offset restoration is not claimed.
- Scrolling continues normally through verses in the loaded chapter. At a resource
  boundary, a fresh outward vertical gesture continues to its neighbour. A fling
  or a drag that merely reaches the boundary does not automatically change chapters.
- Both resource readers use closed native layout queries under script-blocking CSP;
  source scripts, arbitrary network, file/content access and JS bridges stay blocked.
  Legacy non-resource fixtures remain supported but are not the production mode.

## Validation

- Build/lint and 173 JVM tests passed.
- Final selected phone run: 27 tests passed in 67.577 seconds. Covers scrolling,
  boundary gestures, live anchors, stale/released callbacks, shared formatting,
  icon-toggle state, security and existing reader/page regressions.
- Tests were corrected to assert source positions rather than zero pixel offsets,
  and to let Chromium settle a synthetic gesture before a programmatic scroll.
- Real BG 1.1 opened in both modes; vertical scroll and icon toggle checked.
  [Installed scrolling reader](../.reference/vedareader-v096-scrolling.png).
- Device reports v0.9.6/code16. Existing private UUID library remains 1,101,299,712
  bytes; no data clearing, uninstall or duplicate import.

## Still pending — not claimed as shipped

1. Exact font identification/matching: Fira Sans Condensed remains an approximation.
2. Separate animation-smoothness tuning and measurement.
3. Outlined verse/text cards matching chapter cards.
4. Fullscreen/distraction-free button, including status/navigation bar handling.
5. Collapsible chapter hierarchy inside Book contents, with verse jumps.

The inactive immersive reader draft is not enabled by this checkpoint. Keep each
remaining change separately installable/reviewable, as requested by the user.