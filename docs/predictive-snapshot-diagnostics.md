# Predictive page snapshot diagnostics

`back_trace_schema=2` separates capture-local route aliases from the lifetime of
snapshot metadata. The bounded process journal stores only internal route UUID
strings, code-owned event labels and monotonic timestamps. It contains no page
content, URLs, pixels, Activity, Fragment or View references. Its 512 entries are
independent of the unchanged image-memory and UI/player diagnostic-buffer budgets.
Old entries are evicted individually; the report includes a history eviction count.

Capture start and stop reset only report-local aliases. Snapshot registration,
capture success/failure/skips and removal reasons are recorded without requiring
an active diagnostic session. Reports retain the last real removal separately
from later skipped work. Negative event/capture offsets indicate events before
recording; explicit before-capture flags and event ages disambiguate those offsets
from missing capture timestamps.

Fragment and stack saved state carry an internal process marker, never exported
in diagnostic text. Restoring a route with a different marker explicitly reports
`process_restart_snapshot_not_retained`: navigation state survived, while the
process-only pixel cache did not. An Activity restoration in the same process
keeps existing metadata. If its metadata entry was not retained, that condition
is reported separately. Routes saved by older builds lack the marker and are
classified as legacy saved routes; their earlier image history cannot be recovered.
No bitmap or full diagnostic journal is written to saved state or to disk.

An individual report's numeric route aliases cannot be compared with aliases from
another report. `snapshot_state` describes the last observed ledger state;
`cache_entry` and `viewport_matches` at gesture start describe the actual cache and
its compatibility. The journal does not pin images or recreate missing previews.

## Device verification

1. Open a board and thread with recording off. Start extended diagnostics only
   afterward and cancel a Back gesture. The report should show the earlier store
   with `snapshot_stored_before_capture=true` and a compatible cache entry.
2. Stop recording, continue navigating, then start another recording. The known
   state/removal reason must survive both recording windows.
3. Leave the thread in the background or lock the screen, return and inspect the
   same route. Ordinary backgrounding should still retain the image according to
   the existing memory policy.
4. Recreate the Activity without killing the process: the metadata should survive.
   Restart the process and restore the saved page stack: old routes should report
   `restored_new_process`; legacy state from earlier APKs is explicitly labelled.

This change improves observability only. It does not alter gestures, animation,
navigation destinations, pixel retention policy, or the missing-preview fallback.
