# Reader refinements — v0.8.0

Installed on the authorized RMX3286 via USB on 2026-09-23 after the user asked to
proceed. This checkpoint completes the six requested refinements, not an exact
reproduction of Transcend's unidentified EPUB font.

- Removed the reading-confirmation button from every browsing screen. Verified
  chapter verse rows open directly; actual leaf/prose entries now open directly
  after checking their children, keeping the parent as the Back destination.
- Home shows titles/monograms and essential navigation, without featured copy,
  descriptions, counts, language badges, or explanatory footer. Library actions
  and attribution are available from the Info dialog. Accessibility labels remain.
- Current app UI and imports use English; non-English imported files are retained
  but not exposed by the selector. Sanskrit and transliteration in original books
  are preserved; this does not translate or alter the database.
- Neutral warm paper: reader `#F5F1E8`, chapter `#F1EDE4`. Brown headings, amber
  transliteration and source emphasis remain. Native lists use Android sans-serif
  styling; the exact Transcend reading font remains unconfirmed.
- Native two-page reader host retains the outgoing document until the incoming
  document reports visual readiness, then slides over 230ms. Navigation is gated
  during loading/animation, old scroll events are ignored, and at most two WebViews
  are held. System animation-scale-zero is respected.
- Synonyms use initially collapsed HTML `details`/`summary`. Original source text
  and anchors remain intact; JavaScript and arbitrary navigation remain disabled.

## Verification

- Build and lint passed; 92 JVM tests passed.
- 54 phone tests passed after the user unlocked the phone. An earlier screen-off
  run was stopped and is not counted as successful validation.
- Native disclosure toggle, transition retention/locking, error/retry, vertical
  scrolling, chapter Back restoration, English selection and clean-home checks
  were included.
- Real Setting the Scene opened without a read-confirmation button. Real Bg 5.5
  opened and displayed the synonyms disclosure.
- [Installed reader screenshot](../.reference/vedareader-refined-reader.png)
- Data-preserving install: private IVD remains 1,101,299,712 bytes, same UUID file.

The user is reviewing this checkpoint before further design or feature work.