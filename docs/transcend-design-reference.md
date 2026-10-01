# Transcend design and font inspection

Inspected on 2026-09-23 at the user's request on the authorized RMX3286 phone.
Package: `com.bbt.transcend`. This is a reference investigation, not an implemented
VedaReader redesign. The user clarified that the **text-details page is the priority**,
and the chapter font should match the reference too.

## Navigation observed

- Contents uses clean, full-width chapter/title rows with a trailing disclosure arrow,
  a persistent book name, and a Contents heading. No verse body on this level.
- Tapping Chapter 1 opens a list headed by the chapter title. Entries use labels such
  as **TEXT 1 (BG 1.1)** with a translation immediately below. Rows are separated rather
  than presented as rounded cards.
- Selecting Text 1 opens `EbookReaderHostActivity` directly. Accessible reader content
  includes the chapter heading, text reference, Sanskrit, transliteration, and body.
- Back returns to the text list, then to the book's contents.
- Chapter 2 uses the same structure, with **TEXT 1 (BG 2.1)** etc. Text selections in
  both chapters entered the reader; not every chapter or verse was exhaustively opened.
- Reader chrome includes Back, contents/menu, search, options, location indicators,
  and previous/next page controls. Those observations are not a request to implement
  all of those features in VedaReader now.

## Font evidence — distinguish native lists from the EPUB reader

Public compiled resource inspection establishes:

| Element / evidence | Font |
|---|---|
| `item_book_content` title (same resource ID as observed chapter/text rows), `fontFamily` 0x7f09000d | Roboto Regular |
| `item_content` row title, 0x7f09000d | Roboto Regular, 16sp |
| `item_chapter` label, 0x7f09000a | Roboto Medium |
| Reader settings labels, 0x7f09000d; action 0x7f09000e | Roboto Regular / Semi Bold |

The application bundles Roboto variants and also has Readium accessibility fonts.
Their presence alone is **not** evidence that the displayed book body uses them.

Readium's base CSS declares `--RS__baseFontFamily: var(--RS__oldStyleTf)`, where the
fallback stack is `"Iowan Old Style", "Sitka Text", Palatino, "Book Antiqua", serif`.
This is an engine default, **not a verified computed font for the active book**:
publication CSS and user settings can override it.

The inspected appearance sheet provides theme, font size, spacing, alignment, and
continuous scrolling controls. It does **not** expose the selected font-family name.
It was dismissed without applying changes.

## Capture limitation

The initial contents screenshot succeeded. Later screenshots failed; window inspection
confirmed `SECURE` on the Transcend reader and content windows. No attempt was made to
disable or bypass this protection. Normal accessible controls were used to inspect the
navigation. No private book files, DRM credentials, or account data were accessed.

The exact text-details font remains **unconfirmed**. A user-supplied clear photo/reference
or an identified, appropriately licensed font source is needed before promising an exact
match. List typography can use Roboto from its official licensed distribution, rather than
copying fonts out of the reference APK into the app.

## Evidence

- [Initial contents screenshot](../.reference/transcend-current.png)
- [Chapter 1 text list hierarchy](../.reference/transcend-chapter-one.xml)
- [Loaded Text 1 reader hierarchy](../.reference/transcend-text-one-loaded.xml)
- [Chapter 2 text list hierarchy](../.reference/transcend-chapter-two.xml)
- [Reader settings hierarchy](../.reference/transcend-reader-font-settings.xml)

All APK/screen inspection artifacts stay in the ignored reference directory. No Transcend
assets, code, or book contents have been bundled into VedaReader.

## Follow-up inspection: whole-book reading (2026-09-23)

The user asked to inspect the details page again, especially its whole-book design.
Normal accessible controls were inspected on the same authorized phone; no protected
screenshots or private EPUB files were accessed.

- The existing reader was at Bg 9.28. Its hierarchy contained the Chapter 9 resource
  and Text 1–34 anchors, rather than an isolated verse document.
- Tapping the reading area revealed a compact toolbar: Back, Menu, Search, Options.
  Book position indicators showed 399 and 692. These are observed position labels,
  not a verified count of screen-sized pages.
- Menu opened Table of Contents with About the book, Contents and Annotations tabs.
- Contents exposed front matter and chapter navigation for the same book.
- Selecting Chapter 3 jumped straight into the reading session at its heading/Text 1.
  The position labels then showed 245 and 692.
- The next-page control advanced into Text 1's translation/purport, rather than
  skipping to Text 2. This is materially different from VedaReader's current
  one-complete-verse-per-horizontal-swipe model.
- No reference settings were changed. The exact reading-body font is still unknown.

Evidence (ignored local hierarchy captures):
- [Reader with controls](../.reference/transcend-book-flow-chrome.xml)
- [Book-wide contents](../.reference/transcend-book-flow-toc.xml)
- [Chapter jump](../.reference/transcend-book-flow-chapter-three-loaded.xml)
- [Next reading page](../.reference/transcend-book-flow-next-page.xml)

### Original proposal (implemented in the v0.9.0 checkpoint)

Offer one book-reading session, opening at the selected verse's source anchor, with
chapter/text contents and continuation through subsequent verses/chapters. Retain
neutral paper, purport ink, collapsible synonyms and the unchanged Vedabase source.
Reading-page navigation must not silently retain the old skip-to-next-verse meaning.
Load bounded nearby sections, not the entire book/corpus into one huge WebView;
preserve book boundaries, record anchors and a stable reading position. Do not bypass
existing section-size safeguards to simulate a whole book. Confirm the proposed
interaction with the user before implementing this larger change.

## VedaReader implementation status

The earlier six refinements were completed and installed in v0.8.0; see
[the refinement checkpoint](reader-refinements-checkpoint.md).
The smaller arrow-removal and purport-ink follow-up is installed in v0.8.1; see
[the follow-up checkpoint](reader-header-purport-checkpoint.md).
Optional book navigation is now implemented alongside verse mode in
[the v0.9.0 checkpoint](book-reader-checkpoint.md). It uses native viewport stepping
through bounded source sections rather than a copied EPUB engine/design. The exact
Transcend body-font match is still unconfirmed.