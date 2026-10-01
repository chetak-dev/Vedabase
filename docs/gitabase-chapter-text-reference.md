# Gitabase chapter-to-text reference

Inspected on the user's authorized phone on 2026-09-23. This is a design observation,
not a claim that these changes have been implemented in VedaReader.

## Implemented checkpoint — v0.6.0

After user approval, the translation-first chapter list and direct verse opening
were implemented and installed on 2026-09-23. Source `LE_Chapter` entries with direct
`LE_Verse_Number` children show original translation paragraphs, in source order.
Missing translations keep a tappable row with an explicit unavailable-preview hint.
Mixed and non-verse contents retain generic navigation. The reader opens without
pushing a verse onto the chapter's contents path; Back restores the chapter position.

Verified: build/lint, 77 JVM tests, 16 new chapter tests plus 39 phone regression
tests. A real Chapter 1 overview and direct Bg 1.1 opening were inspected on the
phone. Reference images and gallery/book-cover/reading-estimate redesign remain
outside this checkpoint.

- [Installed chapter overview](../.reference/vedareader-chapter-overview.png)
- [Direct verse reader](../.reference/vedareader-chapter-direct-text.png)

## Observed flow

1. **Book:** Bhagavad Gita As It Is opens an overview with a cover, book title,
   author, reading estimates, and a two-column chapter list. Chapter numbers are
   secondary labels above prominent chapter titles.
2. **Chapter:** tapping Chapter 1 opens a chapter overview, not the full mixed
   scripture text and not a bare list of section names. It has a persistent
   book/chapter header with previous/next chapter controls, an illustration strip,
   a chapter-summary heading, and a vertical sequence of verse references with
   their translations. Each translation entry is a large tappable row.
3. **Text:** tapping 1.1 opens the full verse directly. Back returns to the
   Chapter 1 overview. Tapping 1.2 then opens verse 1.2 directly, confirming the
   selected item is respected. There is no intervening TEXT / SYNONYMS /
   TRANSLATION / PURPORT navigation page or separate reading-confirmation button.
4. **Full verse:** compact book/verse/chapter context above a single vertically
   scrolling document. Sanskrit is centered and prominent; transliteration is
   visually distinct; synonyms, translation, and purport follow in the same
   document. Horizontal verse swipes were verified in the preceding reference
   investigation.

The reader toolbar was also tapped: the resulting capture showed the same verse
at its translation, not a chapter-picker overlay. Do not infer a chooser feature
from the downward triangle without a separate, controlled verification.

## Evidence (local, ignored reference captures)

- Book: [overview](../.reference/gitabase-flow-book.png)
- Chapter: [Chapter 1 overview](../.reference/gitabase-flow-chapter-one.png)
- Text: [verse 1.1](../.reference/gitabase-flow-text-one.png)
- Return: [chapter after Back](../.reference/gitabase-flow-return-chapter.png)
- Selection: [verse 1.2](../.reference/gitabase-flow-text-two.png)
- Reading detail: [verse translation and purport](../.reference/gitabase-flow-reader-title-menu.png)

Matching XML captures in the same ignored folder contain actual touch targets.
No Gitabase program, fonts, illustrations, or content are copied into VedaReader.

## Difference from VedaReader before this checkpoint

Previously, `BrowseContent` treated every level as the same list of titled cards.
`BrowseNavigator.open()` descended one source-parent level. Opening a verse
therefore exposed its subsections and required another **Read this section** tap.
The checkpoint reuses the full-verse reader and swipe navigation, adding the
chapter overview and its direct reading action.

## Approved scope

- Chapter heading and a vertical list of verse-reference + original translation
  previews, queried from the user's read-only Vedabase database.
- Tap the verse row directly to open the existing full-verse reader.
- Back restores the same chapter overview and list position.
- Retain VedaReader's approved light palette and typography; adapt the interaction
  pattern rather than copying Gitabase's theme.
- Combined verses remain a single source entry; do not guess missing translations
  or invent summaries. Preserve Unicode and language-specific source text.
- Keep generic browsing for collections/cantos and non-verse content; don't assume
  every chapter in the corpus is a Bhagavad-gita-style verse list.

Defer book-cover redesign, gallery imagery, reading-time/page estimates, bookmarks,
dictionary actions, and other unrelated features to later checkpoints. Images must
come from authorized assets if added later, not extracted Gitabase branding/artwork.