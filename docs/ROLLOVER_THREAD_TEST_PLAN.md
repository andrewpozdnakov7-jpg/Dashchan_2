# Thread rollover (source-only implementation)

Long-press a live OP on a board supporting new-thread posting and choose
"Roll over thread". The form targets a NEW thread on the SAME board, carrying
the OP subject, the existing markup conversion and uploaded original files in
their original order. It never submits a post automatically or guesses a new
thread number. Embedded players/link previews are not treated as attachments.

Originals are copied from full media cache or fetched using the forum's normal
Chan/HttpHolder media downloader (including its proxy/cookies/redirect policy).
Network staging uses app-private temporary files; files are imported into the
existing content-addressed attachment draft store. No thumbnails are uploaded.
Unavailable or over-limit files require an explicit decision to continue without
them. Cancel never replaces the board's existing new-thread draft. An existing
draft needs replacement consent, and its exact object is rechecked immediately
before the handoff; a newer or queued draft is not overwritten.

Names, email, password, author identity, sage/OP options and the old captcha are
not copied. Unrelated future share inputs are not consumed on the initial open.
Draft construction uses the original (not translated) subject and comment. The
existing forum markup converter cannot guarantee a lossless source round-trip.

Preparation is ViewModel-owned and survives rotation. A lost-process operation
fails closed rather than replaying old replacement consent. App locking/profile
reset invalidates preparation. Temporary files and unreferenced prepared files
are released after cancellation; retained hashes protect shared draft files.

## Prepared tests (not executed)

`RolloverThreadDraftTest` checks a detached seed's new-thread target, markup,
identity/options reset, attachment order/list ownership and a text-only payload.
It does not access real user drafts, the network, or submit posts.
`PostingStateControllersTest` additionally checks with fake persistence that
rollover restoration does not consume a failed send, overwrite a draft or reuse
a queued draft. The existing draft controller remains the form's storage owner.

`RolloverHandoffTest` uses a debug-only FragmentActivity and fixture fragments to
check that the preparation dialog is synchronously removed BEFORE navigation
saves the source page. Restoring that page must not resurrect the dialog. The
handoff is posted out of lifecycle/LiveData delivery, rechecks visibility,
authorization, draft identity and saved state, then calls `dismissNow()` before
navigation. No state-loss dismissal or exception swallowing is used. A pending
handoff is removed when the dialog view is destroyed. This targets the reported
`IllegalStateException: Can not perform this action after onSaveInstanceState`.

## Device checks still required

- Both posting form designs and both distributions: OP menu item present only
  where creating a new thread is supported, never on a reply/deleted OP.
- Text-only OP; subject absent; markup, links, quotes, spoilers and line breaks.
- One/multiple image/GIF/video original attachments; cached/offline originals;
  remote originals via an authenticated per-forum proxy, with no credential leak.
- Existing new-thread draft: cancel preserves text/files; replace copies only
  the selected OP. Existing replies/failed sends/queued sends must remain intact.
- Missing file, changed attachment limit, empty response, download failure and
  storage exhaustion: explicit warning, no silently substituted thumbnail.
- Rotate during download, confirmation and missing-file warning; no duplicate
  download/duplicate attachment. Kill/restart during preparation: no stale consent.
- Complete a rollover from a thread and a nested OP dialog, then return to the
  source page: no crash, no saved preparation dialog, no repeat form opening.
- Cancel before/during download, rotate then cancel, lock/reset during preparation:
  no later form opening, no resurrected profile data, no leaked temporary files.
- Edit/remove/replace prepared files; fresh captcha; save/restore draft; manual
  submission creates a new thread, not a reply to the source thread.
- Previously queued future share text/files are not silently appended on initial
  open. Normal reply, new-thread and external sharing paths retain their behavior.

No Gradle, compilation, unit/instrumentation test run, installation or device
validation was performed. No version increment, commit, push or publication.
