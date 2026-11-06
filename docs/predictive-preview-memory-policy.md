# Predictive preview memory policy

Preview pixels are disposable, process-local cache entries. They do not own an
Activity, View, navigation stack, or network request. Navigation remains valid
when pixels are absent. There is no disk persistence and no background expiry timer.

## Retention and removal

| Event | Action |
| --- | --- |
| UI hidden, screen locked, or process backgrounded (levels 20/40) | Keep compatible snapshots unless `ActivityManager.MemoryInfo.lowMemory` is true. |
| Activity recreated within the same process | Keep the retained page store and transfer ownership; late callbacks from the old owner cannot remove the new owner's entries. |
| A capture would exceed the shared budget | Evict only as many entries as needed. Try unprotected entries across all caches before the nearest return page. Decline capture if the remaining space is insufficient. |
| Confirmed low memory, legacy running-pressure levels 5/10/15, severe background levels 60/80, or allocation failure | Release cache ownership and decline the failed capture. Active renderers keep their leases until completion/cancellation. |
| Route no longer reachable from any live/saved return stack | Remove that route's entry. |
| Presentation incompatible with new configuration/theme/window | Discard incompatible pixels. Keyboard/navigation hardware changes alone do not invalidate presentation. Per-window geometry and inset validation still apply. |
| Process death, app update, or reboot | Pixel cache is lost. Android may terminate a background process even below this cache's limit. |

The predictive budget remains `min(256 MiB, memoryClass / 2)`, shared by page,
gallery, context-dialog and WebView previews. The disabled-predictive budget and
the independent diagnostic-buffer limits are unchanged.

## Coordination and ownership

`PreviewMemoryPolicy.Dispatcher` registers one application callback per process.
It samples system low-memory state on trim callbacks and makes one decision for
all preview owners. Owners are weakly registered and explicitly unsubscribe on
close. MainApplication and MainActivity must not independently clear these caches.

The same dispatcher handles capture/allocation failure. Cache callbacks remove
pixels without changing route identities, invalidating accepted navigation, or
finishing an active gesture. Gallery grid cleanup may wait for its active gesture
because that gesture also validates the grid geometry. Stale WebView capture work
is invalidated separately from document-readiness and accepted-back callbacks.

Budget eviction is incremental; actual pressure is allowed to clear all caches.
Extended diagnostics record the decision, trim level, low-memory flag, removal
reason, affected route/image counts and bytes released. A leased image may remain
allocated after its cache entry has been removed.

## Validation

Instrumentation scenarios cover repeated background notifications without
pressure, confirmed pressure, unregistering/closing an owner, compatible and
incompatible configuration changes, nearest-route protection, and reclamation
during an active bitmap lease. Device checks must additionally cover locking and
unlocking, switching apps, Activity recreation, gesture cancellation and commit,
gallery grid return, context dialogs and WebView document readiness during trim.
