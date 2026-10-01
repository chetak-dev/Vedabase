# Reader header and purport checkpoint — v0.8.1

Installed on the authorized RMX3286 via USB on 2026-09-23. Version code 9.

## User-visible scope

- Reader header has no arrow icons: previous/next buttons removed, Back is a text button.
- Horizontal swipes still navigate complete verses; vertical scrolling stays within them.
  Equivalent previous/next custom accessibility actions remain available in semantics.
- All purport body text, including quoted verses, embedded paragraph styles and links,
  uses the theme's body ink. Italics, centring and link underlines are preserved.
- Main-verse transliteration outside purports retains its existing accent colour.
  Section labels and the Synonyms disclosure keep their existing styling.
- Source metadata scopes purport colour across paragraphs and resets it at the next
  verse, chapter or other section. No source text, records or Unicode are changed.

## Validation

- Build and Android lint succeeded.
- 96 JVM tests passed (35 renderer tests, including four new colour/scope regressions).
- 55 selected phone tests passed in 94.284 seconds:
  ReaderScreen 17, OfflineReaderView 12, LibraryPreview 10, BrowseNavigation 8,
  ChapterOverviewScreen 8. Includes native swipes, pending-load locking, Back return,
  collapsible synonyms and nonvisual navigation actions. Not every instrumentation
  class was rerun in this checkpoint.
- Actual Bg 1.28 was opened from the imported chapter overview. A screenshot confirms
  the quoted verse and preceding prose share the same ink, with no header arrows:
  [visual check](../.reference/vedareader-purport-quote.png).
- Device package reports v0.8.1/code 9. Existing private UUID database remains
  1,101,299,712 bytes. No data clearing, uninstall or duplicate import.
- UI inspection helper now pulls XML and decodes UTF-8 explicitly; ADB shell output
  through PowerShell's console encoding had corrupted Unicode titles during inspection.

## Reference investigation / next checkpoint

Rechecked Transcend's book-reading session, internal contents and next-page behaviour.
See [reference findings and proposal](transcend-design-reference.md).
The larger whole-book reading interaction is proposed for user review, not shipped
in this update. Exact reading-body font remains unconfirmed; no reference assets copied.