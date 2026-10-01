# Bookmarks — v0.12.0 / code 25

This is a separate bookmark checkpoint. The requested speed/animation, brighter
theme, search-chip appearance, loading-message, book-colour and line-spacing
settings work remains pending; none is claimed as part of this change.

## Placement and behavior

- **Bookmarks** replaces the disconnected Saved tab in bottom navigation.
- A 48dp outline/filled bookmark icon sits in the reader header. It saves or
  removes the first visible **source paragraph**, not the display-only next-verse
  reference. Saving is gated while the page moves/reflows or a write is pending.
- The same paragraph is saved once per imported library, regardless of reading
  mode. Another paragraph in the same verse may have its own bookmark.
- Saving does not replace the reader document or move/reflow its text. Success
  feedback and the filled icon are published only after durable persistence.
- The bookmark list is scoped to the active imported library, newest first, with
  source book/passage titles, original plain-text excerpt and saved date. Filtering
  is case/accent-insensitive. Other libraries' bookmarks remain stored separately.
- Tapping an item reopens its source paragraph with the current reading mode and
  text settings. Back returns to the filtered/scrolled bookmark list. This is a
  paragraph anchor, not a promise to restore the same visual line or printed page.
- List removal requires confirmation; Cancel/Back leaves it untouched. The reader
  icon is a reversible save/remove toggle. No bulk-delete gesture or clear-all.
- Fullscreen keeps its existing hidden controls: leave fullscreen to use the header
  icon. An accessible bookmark action is also exposed for a settled reader.

## Local persistence and safeguards

Bookmarks are written to a dedicated versioned user-state file under no-backup
storage—not to the Vedabase database, search history, saved-notes draft or library
registry. All I/O runs off-main. Process-wide serialization, atomic writes,
explicit fsync, verified readback and a recoverable rollback image prevent an
unsuccessful save from being reported as successful.

The store validates strict JSON types and source bounds, deduplicates by
library/paragraph, and caps user metadata at 2,000 bookmarks and 4MiB. Invalid or
unreadable data is an error, not silently replaced with an empty list. Recovery is
explicit; stale/foreign UI objects cannot open or remove another library's entry.
No source permissions, network access, import or source rewriting are added.

Bookmarks persist across app restarts and app updates. They are local to this
installation; uninstall/clear-app-data removes them. Export/sync and notes remain
outside this checkpoint.

## Review

1. Open a passage and press the reader's bookmark icon.
2. Return to Library → Bookmarks; filter by book or reference.
3. Open the saved position, then Back to the same list.
4. Verify Cancel and confirm on removal; save again if desired.
5. Switch reading mode and reopen a bookmark to check source-position behavior.

## Validation and installation

- Installed **v0.12.0 / code 25** on the authorized RMX3286 over wireless debugging
  at 192.168.1.10:41379. The original private library remains 1,101,299,712 bytes.
  The final app launch succeeded. No uninstall, clear-data or duplicate import.
- Final build and lint passed. **297 JVM tests** passed with zero failures/errors.
- **92 distinct selected phone checks passed across focused runs**: bookmark
  persistence 28, bookmark presentation 25, search/bookmark/reader navigation 20,
  library 10, fullscreen 9. The final nested-search round-trip checks passed after
  preserving bookmark-list ancestry separately from the immediate search origin.
- Verified durable add/remove, duplicate prevention, failure rollback, strict
  schema/bounds, corrupt data preservation, restarts through a fresh store,
  concurrent store instances, exact original paragraph reopening, unchanged
  WebView/document on save, library isolation, and filtered/scrolled list return.
- Large accessibility text uses full-width navigation rows rather than clipping
  the Bookmarks tab. Filter editing preserves the caret; Android normally collapses
  a selection to its end when the field loses focus.
- Two earlier UI runs were interrupted by destroyed test activities; those runs
  were not counted as successes. The complete final presentation/fullscreen/library
  batch passed 44 checks; storage/navigation passed 47 before the additional
  nested-search regression was added and passed.
- All bookmark write/delete tests use UUID-owned synthetic storage. No real bookmark
  was added or removed by the agent. A live reader demonstration was stopped when
  the current UI changed before the guarded tap; no live-save claim is made.

The installed bookmark checkpoint is ready for user review. Speed, partial chapter
loading, animation, search chips, book colours, line spacing/shared settings,
brighter app colours and the “Hare Krishna! / Your eBook is loading” message remain
pending as separate requested checkpoints.