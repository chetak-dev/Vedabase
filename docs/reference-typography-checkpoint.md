# Reference typography and book format — v0.9.5 / code 15

Installed on the authorized RMX3286 on 2026-09-24. The supplied print samples and
phone photos are visual references, not a source of copied fonts or scripture.

## Review scope

- Fira Sans Condensed is the chosen **approximation**, not a claimed identification
  of the reference's exact font. Six bundled faces supply regular, italic, medium,
  semibold, bold and bold italic. All 15 Material typography styles, explicit screen
  text overrides and the native Android theme use this family.
- Both readers use the same local font resources. Bundled Noto Sans Devanagari
  provides Sanskrit glyphs in the reader; other unsupported glyphs retain platform
  fallback. Reader default remains 20px with 16px side margins.
- Book mode displays recognized source references as **TEXT 4 ( BG 1.4 )**, with
  analogous combined ranges. The original heading HTML and record anchor are retained;
  unknown, malformed or non-BG labels are not guessed or rewritten.
- Sanskrit and text references stay centered. Transliteration and source-styled
  synonym terms stay italic; Book verses use the same ink as prose. Synonyms remain
  visible without a heading in Book mode.
- Translations are bold; Book TRANSLATION labels are centered/bold, while PURPORT
  labels are centered/regular, matching the supplied phone photos more closely.
- Justification, source paragraph/line boundaries, page splitting, controls and
  background remain unchanged. Different font metrics naturally reflow pages.
- Audio/reference links remain original source content; this checkpoint neither
  removes them nor activates their deferred features. No artwork/title-page logo copied.

## Font provenance

Downloaded unmodified from Google Fonts' official repository:

- https://github.com/google/fonts/tree/main/ofl/firasanscondensed
  Copyright The Mozilla Foundation and Telefonica S.A.; SIL Open Font License 1.1.
- https://github.com/google/fonts/tree/main/ofl/notosansdevanagari
  Copyright The Noto Project Authors; SIL Open Font License 1.1.

Font files are shared through Android font resources. Both copyright/license texts
are bundled under assets/fonts. Reader requests allow only seven exact local font
URLs; no generic asset server or network permissions were added. Previous Literata
assets remain unused; their URLs are no longer allowed by the reader font handler.

## Validation

- Build/lint passed; 163 JVM tests passed.
- All 45 focused phone tests passed in 97.173 seconds: native typography styles,
  bundled font bytes/licenses and loaded faces, source formatting, secure resource
  allowlist, page turns, Verse navigation, library and chapter screens.
- BG 1.4 visually checked against the supplied reference:
  [text heading and Sanskrit](../.reference/vedareader-v095-reference-font.png),
  [synonyms, translation and purport](../.reference/vedareader-v095-translation.png).
- Device reports v0.9.5/code15; existing private UUID corpus remains 1,101,299,712
  bytes. No data clearing, uninstall, duplicate import or source database writes.

Pause for user feedback before the next checkpoint.