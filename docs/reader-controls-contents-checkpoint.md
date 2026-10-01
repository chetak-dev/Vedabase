# Fullscreen exit, stable controls and chapter contents — v0.9.9 / code 19

Installed on the authorized phone on 2026-09-24.

## Changes

- Fullscreen entry moved from the header to the bottom bar.
- In fullscreen, tap ordinary text to show/hide a bottom **Exit fullscreen reading**
  button. The button overlays the page without resizing or repaginating it. Back and
  the accessibility exit remain available. System bars stay hidden until exit.
- Book and scrolling modes both forward safe taps. Links, images, selections,
  long presses, multitouch, drags and stale/released callbacks do not toggle the overlay.
- Controls keep their colours when temporarily disabled during page turns. The
  footer retains its last book metadata while a neighbouring resource loads, avoiding
  the colour flashing and layout removal that caused visible blinking. Input gating
  remains active; this is not a claim that all animation performance has been tuned.
- Book contents now groups original source headings into expandable chapters.
  Groups start collapsed; expanding lists their original verses and a section-start
  action. Combined/blank references and valid source jump IDs remain unchanged.
  Front matter remains available, with source-order hierarchy and chunk deduplication.

## Validation

- Build/lint and 179 JVM tests passed.
- Final combined phone run: 42 tests passed in 122.125 seconds, including fullscreen
  restoration, safe taps, stable button rendering/footer bounds, source hierarchy,
  contents navigation, mode toggles and scrolling/reader regressions.
- A historical test expecting scroll taps to be suppressed was updated for the new
  forwarding contract. Fullscreen tests wait for displayed controls after system-bar
  animation, rather than treating the earlier visibility flag as completed layout.
- Live Gita: collapsed chapters opened, Chapter 1 expanded and BG 1.2 selected.
  Fullscreen exit overlay was visually checked without moving the page.
  [Bottom controls](../.reference/vedareader-v099-controls.png),
  [fullscreen exit](../.reference/vedareader-v099-fullscreen-exit.png),
  [expanded chapter](../.reference/vedareader-v099-chapter-contents.png).
- Device reports v0.9.9/code19. Original private UUID library is still 1,101,299,712
  bytes. No uninstall, data clearing, reimport, or global phone settings changes.

Exact font identification and broader page-animation performance tuning remain
separate follow-ups. Pause for user feedback before further changes.