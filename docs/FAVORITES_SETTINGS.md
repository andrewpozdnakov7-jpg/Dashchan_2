# Favorites settings and board groups

Open Settings → Contents → Favorites (after Offline translation, when that
entry is available). Existing favorite-thread order and add-on-reply settings
have moved here without changing their saved keys or defaults.

Two independent presentation options:

- Group by board (off by default): collapsible groups identified by both forum and board.
  Boards are alphabetized inside the existing forum order. Each header shows
  the forum, board and total thread count. Collapsed groups are remembered.
- Unread above read (on by default): a stable partition using the existing favorite-thread
  monitor's unread counters. Unknown counters are not assumed unread. No
  additional polling or unread-state database is introduced.

These are presentation options: turning them off restores the underlying
favorite order. Existing date/title/manual order is retained inside each
displayed group and unread partition. During manual dragging, movement across
automatic board/unread boundaries is disallowed; favorite-board rows are not
subject to these thread-only restrictions. Watcher updates are deferred while
dragging or selecting multiple favorites.

## Manual verification

1. Open the new screen and locate all four options. Find them through settings
   search too. With no saved choice: grouping should be off and unread first on.
   Existing explicitly saved values and old order/add-on-reply choices stay unchanged.
2. Add favorite threads from two boards, and (with merged forums enabled) two
   forums with the same board name. Enable grouping: forum/board identities
   must remain separate. Collapse a board, reopen the drawer and restart the
   application: its collapsed state should persist. Global collapse must still
   work and counts must not shrink when individual groups close.
3. Use threads with confirmed unread counters and read threads. Enable unread
   first alone, then together with grouping. Read one of them: after the
   monitor updates it should move into the read partition of its own group.
   Disable both options: the underlying order must return.
4. Try the existing manual reorder gesture inside one group/partition, title
   ordering, deleting the last favorite of a board, hidden deleted threads,
   bulk selection and custom drawer-section ordering. Update the monitor while
   dragging/selecting: it must not reshuffle active rows mid-operation.
5. Check both distributions, light/dark themes, long board titles, large font
   scale and rotating/recreating the settings screen.

Prepared tests: `FavoriteThreadsLayoutTest` (8 JVM cases) and
`FavoriteThreadsGroupingTest` (4 instrumentation model cases). They have not
been executed for this source-only handoff; no compilation, APK build or
device verification is claimed.

## Default-on monitoring and rename follow-up

Favorite-thread monitoring now defaults on. A one-time preferences migration
also enables "Watch initially" for existing installations, including an old
explicit false. A separate version marker inside favorites.json enables
monitoring on existing favorite threads once. Boards are not changed. Marker
and migrated thread flags are written together through the existing atomic
storage writer; malformed input does not schedule an empty migration write.
Later user opt-outs are preserved, both globally and for individual threads.
No monitoring item has been added to the long-press menu.

The rename editor wraps long titles, grows from one to five lines, then scrolls
internally. Saving, cancelling and the existing field ID are unchanged.

Additional manual checks:

- Upgrade a disposable profile with "Watch initially" off and watched/unwatched
  favorites: the setting and existing threads become enabled, but boards,
  titles, favorite ordering and unread state remain unchanged.
- Disable one thread and "Watch initially", then restart twice: both choices
  must stay disabled. Adding a new thread should respect the new manual choice.
- Try long rename text with keyboard open and on rotation, cancel once, then
  save; verify the title and watcher state are independent.

Additional prepared tests: three JVM migration-policy cases and two disposable
preferences instrumentation cases. These also have not been executed.
