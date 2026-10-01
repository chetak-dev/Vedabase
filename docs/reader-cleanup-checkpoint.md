# Reader cleanup — v0.10.1 / code 21

Installed over the existing app on the authorized RMX3286. This is a review
checkpoint, not a new feature batch.

## Changes

- Subtle procedural paper grain and shading in the reader's light/sepiatone
  content only. No texture image, network request or source-text changes.
- Removed “You are here” and the chapter overview's verse-entry count line.
  Breadcrumb navigation and generic section counts remain.
- Header reference follows the first fully visible verse heading, falling back
  to the first visible source record on continuation pages. A BG 2.7 tail with a
  fully visible BG 2.8 heading now displays BG 2.8. The separate source anchor
  still preserves that BG 2.7 tail for reflow and mode changes.
- Text-size preview reads “Hare Krishna” and “हरे कृष्ण” on separate lines.
- Added 48dp previous/next controls at the bottom corners. Paged mode steps
  screen pages, crossing resources only at boundaries; scrolling mode uses the
  existing previous/next resource navigation. Busy controls retain resting colours.
- Fullscreen now leaves 16dp of extra space above and below the reader, beyond
  display-cutout safe insets. Back and the tap-to-show Exit overlay still work.

## Validation

- Build, lint and 200 JVM tests passed (zero failures/errors/skips).
- 37 distinct selected phone checks passed: visible-reference geometry (3),
  cleanup UI (3), fullscreen (9), chapter overview (10), browse navigation (12).
  Initial run passed 36/37; the new arrow pixel comparison sampled a transient
  native Material tap ripple. Its test now waits for resting pixels while the
  real loader remains held. All 3 cleanup checks passed on rerun in 15.026s.
  No production change was needed for that test correction.
- Reference tests exercise real WebView geometry and preserve the independent
  source anchor in both modes. Fullscreen tests check native cutout-plus-gap
  bounds. Arrow tests cover real page steps, boundary loads and disabled inputs.
- Installed version 0.10.1/code21 confirmed. The original private UUID library
  remains 1,101,299,712 bytes; no uninstall, data clear or duplicate import.
- App launch requested after tests. The live hierarchy then exposed System UI
  rather than reader controls, so no guessed taps or real-corpus screenshot
  claims were made. BG 2.8 verification here uses synthetic native test content;
  the paper appearance still needs the user's visual review.

Pause for feedback. Exact font matching and broader page-animation tuning remain
separate, deferred work.