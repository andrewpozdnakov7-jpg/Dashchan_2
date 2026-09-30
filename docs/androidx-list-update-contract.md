# AndroidX list updates and background-work review

This is the second source-only modernization pass. It builds on
`androidx-lifecycle-contract.md`; no dependency versions are changed.

## List update boundaries

- Replies/My Posts capture detached display values, including read/tracking/deleted
  flags, comment, date and reply target. Reply identity excludes the target because
  storage deduplicates replies referencing multiple own posts. Forum, board,
  thread, post and item kind are included in identity.
- Tabs, the empty placeholder and the more footer participate in the same diff.
  The existing paced append path remains, including its page-generation and
  revision checks. Leaving the page still stops prefetch.
- Catalog updates use forum/board/thread identity. Dataset replacement rebinds
  retained rows conservatively: a state provider or mutable vote/hide data may
  have changed even when the PostItem reference did not. Search/sort/append can
  reuse unchanged rows. This is not a claim that every row bind has been eliminated.
- A new search query still discards old-query results. A dataset/sort change for
  the same query retains only identical PostItem revisions present in the current
  source, in source order, while the cancellable search runs. Hidden/removed
  objects cannot reappear from an older async result. Existing generation checks
  and cancellation on leaving the screen remain unchanged.
- My Boards retains its existing source-qualified scroll anchor and restore path.
  Opening a hidden row now changes that row rather than invalidating all rows.
- Selection payloads update controls/background without reconfiguring attachments
  or replacing comment text. Full binds and partial binds share the same control
  update method. Translation and search highlighting retain full text-row binds
  because line count, spans and measured height can change; they no longer signal
  structural invalidation of the entire adapter.
- Complete window replacement in large threads, hide-state rebuilds and card/grid
  mode changes retain full refreshes. DiffUtil is bounded to 1000 rows per side;
  larger lists or duplicate keys fall back to the previous full refresh. Move
  detection is disabled; a reorder is represented by remove/insert notifications.
  The cap bounds UI-thread comparison work, not stored post/thread counts.

`AdapterListUpdateTest` covers unchanged rows, read flags, placeholder replacement,
footer insertion, compound identity, reply targets, conservative rebind, mapping,
sorting, duplicate keys and the size fallback. These are source-added unit tests,
not a substitute for device acceptance or measured performance results.

## WorkManager review: observe before changing delivery policy

Local checks use one unique periodic work name with KEEP/UPDATE, foreground
cancellation and run/result guards. Push synchronization uses APPEND_OR_REPLACE
and a process lock; retry/delete/reset work has separate unique names. Delivery
uses KEEP per event and durable storage deduplication. These policies, constraints,
backoffs, network requests, notification settings and intervals are unchanged.

WorkerDiagnostics is enabled only by BuildConfig.DEBUG. It records start, lock
wait, completion, attempt count and stop state/reason. Active queue states are
sampled asynchronously, at most once per fixed queue name per 30 seconds. Finished
history is excluded. There are no long-lived observers, input/output Data dumps,
installation/watch/event IDs, post text or exception messages in these new logs.
Stop reason is guarded for Android 12+; Android 11 records unavailable.

Unproven concerns remain observations, not claimed fixes: repeated sync requests
can form a chain while offline; a stopped worker may still be inside synchronous
network code or waiting for the process lock. Changing coalescing/cancellation
requires a separate delivery-order review and device evidence. The new diagnostics
make these paths distinguishable without rewriting notification policy here.

## Verification status

Static diff/API review and source archive checks are separate from compilation.
This pass has not run Gradle tests, lint, APK builds, emulators or device benchmarks.
Verify replies/read/clear, My Posts prefetch and cancellation, catalogs/My Boards
search/hide/sort/scroll restoration, selection exit, translation and notification
smoke scenarios on the resulting APK. No quantitative speedup is claimed.

API references:
- https://developer.android.com/reference/androidx/recyclerview/widget/DiffUtil
- https://developer.android.com/reference/androidx/work/WorkQuery.Builder
- https://developer.android.com/reference/androidx/work/ListenableWorker
