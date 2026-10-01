# Search feedback and large contents — v0.11.1 / code 24

## Requested changes

- Three explicit targets: **All text**, **Verse words**, and **Verse reference**.
  Verse words searches the main Sanskrit/Bengali/transliterated verse lines, not
  translations, synonyms or purport quotations. Word/phrase matching remains
  available for word searches; reference searches use source-heading metadata.
- References accept the source's `Madhya 1.1` spelling as well as `CC Madhya 1.1`.
  Complete reference lookups no longer decode numeric/common-word postings.
- The search field owns its selection and IME composition. Typing no longer
  replaces the lazy list state; asynchronous suggestions do not reset the caret.
- The source picker shows numbered catalog levels, indentation, expandable
  branches, separate selection controls, and partial-selection state. Selecting
  a parent includes its descendants even while filtered/collapsed. Selecting only
  children does not implicitly include parent-only prose. Excluding a child from
  a selected parent replaces that parent scope with the unaffected branches;
  a note explains that unrepresentable parent-only prose is excluded as well.
- Clear history requires confirmation. Cancel, Back, and changing databases do
  not clear history. The “Find a passage in your library” card is removed.

## Slow search changes

Database reads already ran off-main; the fixes do not merely move the same work
to another thread or lengthen a timeout. Reference lookups bypass the word index;
prefix grouping avoids a temporary DISTINCT B-tree; suggestions have a bounded
cache and a 650ms deadline. Query cancellation still interrupts SQLite reads.

The phone exposed a costly new verse-only path: classifying every global match
before returning any results exceeded 15 seconds. Verse classification now works
in 128-candidate batches and stops after 30 hits, 1,000 body verifications or 4,096
raw candidates. The cursor is the last inspected record, not the end of a
prefetched batch. Unprocessed records remain available through Show more.

Actual phone query measurements after that fix (single diagnostic run, caches not
controlled; not tap-to-paint guarantees):

- Global verse-word `krsna`: **373ms**, 30 hits; previously timed out at 15 seconds.
- Gita verse-word `krsna`: **408ms**, 9 hits.
- Full-text `devotional service`: **528ms**, first 30 hits.
- Gita `the`: **976ms**, first 30 hits.
- CC reference: **610ms** warm; first cold read **2,486ms**, including catalog.
- Word and reference suggestions: **183ms / 70ms**.

Building parent relationships for a normal nested source catalog now uses a
linear interval-stack pass after sorting, instead of a complete scan per book.
Malformed overlapping intervals retain a conservative fallback.

## Madhya 1.1 diagnosis and fix

The supplied source has **11 rows / 854 characters** for Madhya 1.1 (227161–227171).
Its chapter is 2,757 rows / 284,727 characters. Neither exceeds the text limits.
The failure was the metadata scan rejecting Madhya-līlā's **38,739 headings** at
the old 20,000-heading cutoff before it attempted to load the selected verse.

Metadata now loads in at most 512-row keyset queries, retaining complete ancestry,
overlap, duplicate, nested-work and escaped-child validation. The limits are
separate: 100,000 total headings, 20,000 direct children, 8MB encoded heading
strings, bounded fields and the existing 40,000 outline entries. Actual text stays
bounded at 12,000 rows / 4 million characters / 16MB encoded bytes per resource.
The source is not split destructively, skipped or rewritten.

The opt-in phone check loaded the exact original verse and its complete chapter
read-only and compared every returned paragraph to the source. Other genuinely
oversized or malformed sources can still fail safely; this is not unlimited loading.

## Validation

- Installed **v0.11.1 / code 24** on the authorized RMX3286 over wireless debugging.
  Confirmed the original private library remains **1,101,299,712 bytes**. No uninstall,
  data clear, duplicate import or source writes.
- Build and lint passed; **277 JVM tests**, zero failures/errors.
- **173 distinct selected phone checks passed across focused runs**: search UI 46,
  search engine 35, search navigation 11, large metadata 16 (including the actual
  Madhya check), existing book/resource safety 64, real search timing/target check 1.
  The final full UI class passed all 46; the final reader-safety rerun passed 64.
- The native Back test initially injected the key before the reopened confirmation
  owned native window focus. Waiting for actual dialog focus fixed the test without
  replacing standard dialog Back handling. Keyboard/lazy-list transitions also
  required settled/reacquired test targets. Confirm and Cancel remain explicit.
- Four older cold-cache tests reused a terminally closed repository. They now
  construct a fresh owner for a cold reopen, retaining all source/cache checks.
- Live navigation through CC → Madhya-līlā → chapter 1 → Madhya 1.1 rendered the
  original verse in Book mode without the previous error. Screenshot retained at
  [.reference/vedareader-madhya-review.png](../.reference/vedareader-madhya-review.png).
- The phone was left on the updated contextual search screen with all three target
  options visible. The welcome card is absent and the user's actual history was
  not cleared. All destructive-history tests use isolated synthetic preferences.

Pause for the user's review. Other genuinely oversized sources remain protected
by finite limits; no claim is made that every possible loading error is solved.