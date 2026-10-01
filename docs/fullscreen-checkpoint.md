# Fullscreen reading — v0.9.8 / code 18

Installed on the authorized RMX3286 on 2026-09-24. This checkpoint adds only
explicit fullscreen/distraction-free reading to the active reviewed reader.

- Header expand icon enters fullscreen in either paged or scrolling mode.
- App header/footer and Android status/navigation bars are hidden. Reading fills
  the available window, with display-cutout protection so hardware does not obscure text.
- Back exits fullscreen first, without closing the reader. A subsequent Back uses
  the existing reader navigation. An accessibility exit action is also available.
- Android edge swipes can temporarily reveal system bars; this is preserved deliberately.
- The existing native view/document survives layout changes. Paged content reflows
  around its visible source; scrolling retains/clamps its offset. Fixed page numbers
  need not remain identical when the viewport grows.
- Empty/error states restore controls. Window focus/resume reapplies fullscreen;
  leaving fullscreen or disposing the reader restores prior bar behaviour/visibility.
- No lock/wake/global phone settings changed. No new automatic tap-to-hide behaviour,
  contents redesign, font changes or general animation-tuning scope was added.

## Validation

- Build/lint and 173 JVM tests passed.
- Final selected phone suite: 26 tests passed in 75.784 seconds, including six new
  fullscreen cases, icon-mode tests and existing reader regressions. Tests inspect
  real Android bar visibility, viewport growth, source/offset preservation, Back,
  disposal, lifecycle resume, accessibility exit and error recovery.
- Initial tests found a device/compat leak of legacy IMMERSIVE flags after restoring
  default behaviour. Cleanup now restores only the saved immersive bits, without
  overwriting layout or light-bar flags. The complete selected rerun passed.
- Real passage checked in both modes. [Fullscreen capture](../.reference/vedareader-v098-fullscreen.png)
  shows no app controls, status bar or navigation bar. Back restoration verified;
  the phone is left with normal reader controls visible for review.
- Device reports v0.9.8/code18. Original private UUID library remains 1,101,299,712
  bytes, with no data clearing, uninstall or duplicate import.

Still pending: smoother page animations, collapsible chapter/verse contents and
exact font identification. Wait for user feedback before another checkpoint.