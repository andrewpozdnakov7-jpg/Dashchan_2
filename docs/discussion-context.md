# Experimental discussion context

The feature is disabled by default. Enable **Discussion context** in Experimental
settings, then long-press a post inside a thread and choose **Discussion context**.
Disabling the setting removes the entry. The settings search points to that same
Experimental setting; there is no second switch.

## Current implementation

- Uses actual quoted link destinations, normalized by the forum locator. Only
  links in the same forum, board and thread are edges; plain `>>number` text is not.
- Shows preceding quotes, the selected post and direct replies. The post menu can
  expand that post's continuation; siblings through a shared parent are excluded.
- Starts with at most 20 cards. Explicit expansion adds up to 20, capped at 200.
  Predecessors start at depth 3; maximum traversal depth is 12.
- Uses a bounded snapshot from the open thread and exact local-cache lookups.
  **Find replies in saved thread** explicitly scans saved posts. Local coverage is
  always labelled partial, not the complete conversation.
- Background work is cancellable and generation-checked. Closing the panel stops
  work. Large blobs, post text, scans and link counts have independent limits.
- Uses native post cards, with cached-only automatic thumbnails and icons. Does
  not automatically fetch posts or start translations/model downloads. Explicit
  user actions on links or attachments keep their normal behavior.
- The read-only state provider does not mark posts/replies read or acknowledge push.
- Hidden posts use neutral placeholders, or are omitted when removal of hidden
  posts is enabled. Temporary reveal applies only inside this panel. Cache erasure
  invalidates the snapshot; old open-thread seeds are not reused after that.
- Expansion preserves the visible anchor where available. Back can return through
  five selected context targets; it then closes the panel. The existing dialog
  stack retains compact state across view recreation. A killed app does not persist
  this temporary panel to disk.

## Manual acceptance checks

1. Setting off: ordinary quotes, Replies, thread menus and scrolling are unchanged.
   Turn it on through Experimental settings (also check settings search).
2. Open a quoted chain with two sibling branches. Select the middle post: check the
   selected post, predecessors and direct replies; expand a reply and ensure its
   continuation appears without adding the sibling branch of an ancestor.
3. Try offline with cached and uncached posts, then scan the saved thread. Missing
   posts must be labelled unavailable, not deleted; unread reply counts must stay.
4. Test hidden posts with both visibility settings. Temporary reveal must not change
   the global hide rule. Clear the thread cache while context is open, then refresh.
5. Expand past 20 cards, scroll, expand again, choose a different context, press Back,
   rotate, open several nested quotes and return, close during a scan, reopen.
6. Check copy, quote/reply, attachment gallery and ordinary thread navigation.

## Verification status

Source-level review and resource checks only. Unit cases are provided for graph
scope, cycles, ordering, expansion limits, edited quotes, cancellation and HTML
link extraction. They have **not been executed** in SOURCE PATCH ONLY mode.
No Gradle/APK build or device verification is claimed. Layout, rotation, database
integration and the acceptance checks above still require a test build.
