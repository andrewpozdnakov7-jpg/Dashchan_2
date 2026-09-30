# AndroidX lifecycle pass: contracts and acceptance

This pass does not update dependencies, change the page stack, replace storage,
or optimize RecyclerView datasets. Tests below are source additions; a source ZIP
does not imply that Gradle tests or device scenarios have been executed.

## M00 / M02: host and diagnostics

The main fragment host is FragmentContainerView. Drawer containers, the toolbar
and gallery remain separate. The ExpandedScreen hierarchy listener still tracks
added/removed content views; already-restored children are registered too.
There is no LayoutTransition on the fragment container.

MainActivity installs FragmentStrictMode before super.onCreate only when
BuildConfig.DEBUG is true. It detects reuse, retain/target APIs, user-visible hints,
wrong nesting and wrong containers. The penalty listener logs violation category
and class/instance identity under UiLifecycle, without fragment arguments,
addresses, post content or drafts. No penaltyDeath. Production builds do not
install the policy or lifecycle observer. A device diagnostics pass is still
required; absence of diagnostics in a non-debuggable APK is intentional.

## M04: ListPage / PageFragment

The synthetic page lifecycle is intentionally retained. It describes the lifetime
of a page view, not merely the foreground state of its Fragment. PageLifecycle
owns the transition rules; its registry owner remains the original ListPage.

| Operation | State and contract |
| --- | --- |
| Create view / init | INITIALIZED during onCreate, then STARTED; observers may receive retained task results only after initialization |
| Resume | RESUMED, then onResume and pending post data; no callback if an observer destroyed the page during transition |
| Pause/background | STARTED; task results may populate the still-living view; menu/transactions remain subject to the Fragment host's saved-state checks |
| Save to stack | Save identity, scroll position and extras; saving is not itself a lifecycle transition |
| Switch page / terminate | Pause if needed, then DESTROYED before onDestroy; detach the PageViewModel weak pointer only if it still refers to this page |
| Destroy view | Idempotent destroy; discard queued doOnResume work belonging to the old view |
| Rotation / restore | New page and registry; retained task models may deliver to the new owner, never the old destroyed owner |
| Process recreation | New models/registry; recover persisted page data, not in-memory callbacks |

PageLifecycleTest uses the real AndroidX LifecycleRegistry with main-thread
enforcement disabled only in tests. It covers observer delivery, pause/resume,
terminal destruction, duplicate calls, replacement owners and reentrant cleanup.
It is not a substitute for real FragmentManager/activity/process tests.

## M05: menus

ContentFragment's MenuProvider remains view-lifecycle scoped. Only the host creates
and prepares the menu. Invalidation is not a direct onPrepare call. Termination
removes the provider; action-view collapse during rebuilding must not erase search.

## M07: StateActivity cleanup decision

Keep InstanceFragment for this pass. onFinish currently releases MainActivity
services/subscriptions during explicit finish, recreate and old-host detach.
Replacing it with onDestroy would change ordering relative to fragment teardown
and PiP restoration without a device-proven equivalent. The once-only flag is
claimed before calling cleanup to prevent reentrant duplicate unbind/unregister.
The fragment holds no retained data. Removing it is not a completion requirement.

## M09: Activity Result

PostingFragment registers picker/editor launchers as fields in a fixed order.
The draft is saved before opening the picker or editor. The editor returns original hash
and original name, as well as destination hash/name and slot. A result may replace
that slot only if the original identity still matches. Missing/stale results do
not replace another attachment; the user is asked to reopen the editor.
AttachmentResultGuardTest covers matching, changed, removed and missing identities.
Picker URI copy/grant behavior is unchanged; it still requires on-device testing.

## M11: transient dialogs

| Dialog | Rotation | Process/request loss |
| --- | --- | --- |
| InstanceDialog | RESTORE factory from ViewModel | DISMISS empty shell; no automatic restart |
| ForegroundManager captcha / item / image / permission choices | RESTORE while PendingData exists | DISMISS if request missing; never fabricate success or restart posting |
| RecaptchaReader.V2Dialog | RESTORE retained WebViewHolder | DISMISS when holder and initial challenge are both gone, without allocating a WebView |

Allowing state loss is restricted to discarding already-orphaned dialog shells;
it is not used to open dialogs or replay operations. Captcha callbacks and queued
touch events cannot use a renderer that has already been removed.

## Required device acceptance (not claimed as completed)

1. Navigation: boards, threads, answers, settings, drawer, quick page switches,
   portrait/landscape, wide/split screen, predictive back, expanded toolbar.
2. Menu: open/search/close search; switch screens, background/foreground, rotate;
   correct actions remain visible, no duplicated menu items or lost query.
3. Posting: draft plus two attachments; edit the second, rotate, save/cancel;
   verify only the correct attachment changes. Repeat with process loss while the
   external picker/editor is in the foreground; restored draft and URI import work.
4. Captcha/choices: rotate an active dialog; then test process loss separately.
   Lost requests dismiss without a crash or automatically sending a post.
5. Gallery: selection/grid/filter survive activity recreation and process restore
   where a local snapshot exists; PiP enter/exit/rotation/back remains functional.
6. In debuggable builds collect UiLifecycle violations; investigate actual
   findings before deciding to change retained legacy behavior.

Process-loss testing: put the target app in the background, run
`adb shell am kill io.dashchan2`, verify the old PID is gone, then restore the task
or finish the foreground picker/editor. Do not substitute force-stop or clearing
app data. The system may refuse to kill a foreground/foreground-service process.

Build/test both GitHub and F-Droid variants through the existing build workflow.
This source-only pass has not run Gradle, lint, instrumentation or APK builds.

## Remaining separate pass

M06 (measured list diff/payload optimization) and M08 (WorkManager queue review)
are not changed here. Full API-30/current-Android acceptance and performance
measurements remain separate from static/source-level completion.
