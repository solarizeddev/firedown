# Sketches

UX exploration artifacts produced during design conversations. Each HTML
is a self-contained static mockup — open it in a browser to see the
phone-frame, card, and popup variants we walked through before
landing on the shipped implementation.

Newer iterations supersede older ones (the file list below is in
chronological order). Older sketches are kept for context — the deltas
between them show *why* we ended up where we did.

## Files

- `bookmarks-card-sketches.html` — Four bookmark-card variants for
  the home stack (counter subtitle, last-added subtitle, favicon
  stack, count pill).
- `home-ux-alternatives.html` — Four whole-home directions
  considering the cards and bottom bar together.
- `home-ux-v2.html` — Three directions with explicit per-context
  "more" popup contents.
- `popup-redo.html` — First sectioned-popup pass using a top
  quick-row plus a flat list.
- `popup-fennec-inspired.html` — Sectioned popup with a 2×2 library
  tile grid borrowed from Fennec. Rejected as derivative — Firedown
  doesn't have the same library cardinality (Downloads is primary
  chrome, not a peer of History/Bookmarks).
- `popup-firedown-native.html` — Final direction. MaterialCard
  groups (which match the home-card vocabulary), neutral chips
  throughout except the destructive-tinted Quit, ★ Bookmark
  initially in the quick-row (later moved to a page-state row
  during implementation).
