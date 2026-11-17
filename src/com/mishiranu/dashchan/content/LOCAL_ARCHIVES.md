# Slooop local archives

This document describes the local thread archive pipeline and its on-disk format. Keep it in sync with
`LocalArchiveManager`, `SendLocalArchiveTask`, `PackLocalArchiveTask`, `LocalArchivesFragment`, and
`LocalArchiveViewerFragment`.

## Goals

- Keep a normal, portable HTML page that can be opened without Slooop.
- Offer a mobile post-feed view inside Slooop without rewriting the saved HTML.
- Keep old Dashchan/Wakaba HTML archives readable.
- Optionally package the page and downloaded resources into one ZIP file.
- Browse archives in additional folders selected through Android's Storage Access Framework and delete them when
  the provider grants write access.

## Deletion

Long-pressing an entry on the local archives screen opens a deletion confirmation. Deletion removes the matching
HTML, ZIP and JSON manifest. The resource directory is deleted only after those files have been removed and no
remaining snapshot in that source references it. Updated snapshots can share resources; deleting one must not break
another. Failed directory scans or unreadable possible references retain media conservatively. Deletion never removes
the archive root or a user-added source directory.

Deleting from a user-added Storage Access Framework directory requires a persisted write grant. A directory added
by an older build with read-only access may need to be added again before its archives can be deleted.

## Save pipeline

1. `SendLocalArchiveTask` snapshots the posts into Wakaba-style UTF-8 HTML in an internal temporary file. The page
   title is the same normalized thread title shown by the live posts page: subject first, then a short OP comment.
2. It builds a versioned JSON manifest and sends the HTML and manifest to `DownloadService`.
3. Requested thumbnails and original files are downloaded below the archive's resource directory.
   Selecting original files in the local archive dialog also selects thumbnails as a safe default. The user may
   explicitly clear the thumbnail option before starting the archive.
4. Whenever ZIP output is enabled, an initial self-contained ZIP with the frozen HTML and manifest is prepared and
   queued before the loose HTML and any media. The user therefore gets a valid ZIP without waiting for a long media
   queue.
5. When ZIP output includes media, `ArchiveCoordinator` then waits for the HTML, manifest and every queued resource,
   and checks all required files (including reused media) on a worker. `PackLocalArchiveTask` replaces the initial ZIP
   with the complete archive. Media is stored without
   recompression so that packaging is fast and does not heat the device unnecessarily.
6. Duplicate archive names are resolved before saving with `_1`, `_2`, and subsequent suffixes.

If requested media is unavailable, the new snapshot remains partial and the previous archive is retained. Loose HTML,
successfully saved files and an initial HTML-only ZIP may remain available. A partial archive is not reported as a
successful replacement. If required metadata fails, the incomplete new snapshot is discarded; previous media stays.

### Incremental updates

Saving the same thread again finds its newest readable Slooop archive in the default Archive folder and merges posts
by post number. Current posts win, but archived attachments omitted by the live version are retained. A deleted post
with an empty current comment keeps its previously archived text. Archives in added SAF folders are browsable, but
are not automatically selected for updates.

HTML, JSON and ZIP keep unique snapshot names (`thread`, `thread_1`, `thread_2`). Updates share the original resource
root (`thread/src`, `thread/thumb`). Source URI identities in `mediaSources` map new attachments back to saved files.
Legacy archives use conservative per-post filename, size, dimensions and original-name matching. Ambiguous matches
may produce an extra file rather than reuse unrelated data.

`LocalArchiveMediaPlan` queues only missing or unverified media. Board-reported sizes are display estimates (for example,
whole KiB on Dvach/E444), never exact completeness checks. After a successful download callback, a nonempty file's actual
byte length is recorded in optional `mediaBytes`. Only a file with that successful-save receipt and matching actual
length is reused. Failed/unknown downloads do not gain receipts even if a partial file exists. Existing incomplete or
unverified files receive a new name instead of being overwritten. Download requests also disable overwriting existing payloads.
If loose files are absent but exist in the
previous ZIP, they are extracted locally using an explicit ZIP-only resource URI. Otherwise the known original source
is used. ZIP-only recovery currently scans the previous ZIP per missing entry; ordinary loose-file reuse avoids this.

The finalized manifest includes successful files from a partial or cancelled operation and is written before final ZIP
packing and removal of previous snapshots. An old archive without receipts may need a one-time download or ZIP recovery
to establish them. An unverified legacy file without a remote source or ZIP entry keeps the update partial and preserves
the previous archive. Providers without size metadata are stream-counted on a worker. These are length checks, not hashes.

The previous snapshot is removed only after metadata, every requested payload and optional final ZIP succeed. Cleanup
removes superseded snapshots sharing that resource root and thread identity. Independent legacy copies remain intact.
After an interrupted download, save the thread again: partial metadata records the resource identities and requested
ZIP option, allowing another attempt without downloading complete media again. A malformed/missing partial manifest
or a provider without reliable file-size information can require extra work; this is not a durable database transaction.

A process-local lease spans preparation, download, ZIP and cleanup. It rejects simultaneous saves of the same thread
and reserves output names across other saves. Cancellation releases ownership and retains the previous snapshot.
Process death can leave partial output, but does not trigger deletion of the previous archive. HTML and ZIP are rebuilt
locally rather than edited in place; a ZIP still contains all requested resources, including reused files.

The archive list and viewer toolbar use the optional manifest `title` when it is present. The stable archive name
remains visible in the list details and is the fallback for legacy or third-party archives without title metadata.

## Loose archive layout

```text
Archive/
  dvach-b-123.html
  dvach-b-123.json
  dvach-b-123/
    thumb/
      thumbnail.jpg
    src/
      original.webm
```

The HTML uses relative paths, so move it together with the resource directory declared in its manifest. For example,
`dvach-b-123_1.html` can still reference `dvach-b-123/src/original.webm`. New pages also contain harmless
`slooop-local-archive` and `slooop-local-archive-resources` meta markers, allowing recognition and resource-root recovery
if the JSON sidecar is lost. The JSON sidecar is optional for old and third-party pages. A resource root must be a safe
single segment equal to the snapshot name or its ancestor followed by a generated numeric suffix.

## ZIP layout

```text
manifest.json
dvach-b-123.html
dvach-b-123/
  thumb/...
  src/...
```

ZIP entries retain the same paths as the loose archive. Never accept absolute paths, backslashes, `.` or `..`
segments when resolving resources.

## Manifest format

New archives use schema `slooop-local-archive`, currently format version `2`:

```json
{
  "schema": "slooop-local-archive",
  "format": 2,
  "generator": "Slooop",
  "created": 1784690000000,
  "name": "dvach-b-123",
  "html": "dvach-b-123.html",
  "resources": "dvach-b-123",
  "view": "native",
  "chan": "dvach",
  "board": "b",
  "thread": "123",
  "posts": 120,
  "attachments": 48,
  "savedFiles": true,
  "savedThumbnails": true,
  "zipRequested": true,
  "mediaSources": {
    "dvach-b-123/src/original.webm": "https://example.org/src/original.webm"
  },
  "mediaBytes": {
    "dvach-b-123/src/original.webm": 102123
  },
  "title": "Optional thread title"
}
```

Rules for future changes:

- Add optional fields without changing `format`.
- Increment `format` for incompatible structural changes.
- Readers must ignore unknown fields.
- `resources`, `mediaSources`, `mediaBytes` and `zipRequested` are optional. Old archives default to their own resource root.
- Source URIs are local archive metadata, not diagnostics. They can contain original URL query parameters; treat an
  exported archive accordingly. They are used only to locate requested missing resources during a manual save.
- Absence of a manifest means a legacy archive, not a broken archive.
- The package name may remain `io.dashchan2` for Android update compatibility; it is not the archive schema name.

## Viewer modes

`LocalArchiveViewerFragment` keeps the source HTML unchanged in memory and can display it in three modes:

- **Original HTML**: renders the page as saved. Legacy archives open in this mode by default.
- **Adaptive view**: reads the post markers and `data-*` metadata embedded by `WakabaLikeHtmlBuilder`, then builds
  a separate theme-aware mobile HTML feed with headers, attachments, and comments. Old pages without structured
  post markers retain the responsive CSS fallback.
- **Slooop view**: reconstructs `Post` and `PostItem` objects from the same structured markers and renders them with
  the application's normal `PostsAdapter`. New format-2 Slooop archives open in this mode by default. Archives that
  do not contain enough board/thread metadata automatically fall back to an HTML mode.

The overflow menu contains one view-mode command. It opens a single-choice dialog instead of relying on checkable
submenu items, because Android devices may maintain separate toolbar and overflow `Menu` instances.

The generated feed and fallback CSS are not written back to disk. JavaScript stays disabled. Local resources are
served through the synthetic `https://local.archive/` origin; non-local links are delegated to the external browser.

## Additional archive folders

The Local archives screen can register multiple folders with `ACTION_OPEN_DOCUMENT_TREE`. Only a persisted read
permission is requested. URI strings are stored separately from the application's main download directory, so adding
an archive folder must never replace or release the normal download permission.

Each selected folder is scanned only at its top level for matching `.html`, `.json`, and `.zip` files. Loose resources
are resolved below the same folder using the paths embedded in the HTML. Entries are identified by both source URI
and archive name, which allows equal filenames in different folders.

If a provider is removed or permission is revoked, that source is skipped while all other archive sources continue to
load. Removing a folder from the app releases its persisted read permission but never deletes user files.

## Compatibility checklist

When extending this subsystem, verify these cases:

1. Legacy standalone HTML without JSON opens in Original HTML mode.
2. Legacy format-1 ZIP opens and resolves embedded media.
3. New loose HTML plus JSON defaults to Slooop view and can switch to both HTML modes.
4. New ZIP defaults to Slooop view and resolves thumbnails and originals.
5. Equal archive names in the default folder and an added SAF folder remain separate list entries.
6. Revoking one SAF permission does not prevent the default Archive folder from loading.
7. Malformed manifests fall back to legacy behavior.
8. Unsafe resource paths are rejected.
9. Switching viewer modes through the dialog immediately changes the visible renderer and never modifies the saved
   HTML.
10. A Slooop/Wakaba page with structured post markers renders both as an Adaptive HTML feed and through the native
    post adapter.
11. A thread without a dedicated subject stores the live page's short OP-comment title in both HTML and the manifest.
12. The archive list and viewer show the manifest title, while archives without one fall back to the stable name.
13. Save a thread with media, add posts, save again: old files stay at their paths and only missing media downloads.
14. A failed/cancelled media download retains the old archive; retry fetches missing files and then cleans old snapshots.
15. Same filenames with different source URIs do not overwrite each other; incomplete old files are retained.
16. ZIP-only recovery, loose/ZIP viewing after update, and deletion in default and added SAF folders preserve resources
    while any snapshot still references them.
17. A simultaneous second save is rejected; after completion/cancellation a new save is allowed.
18. An original whose actual byte count differs from the board's rounded size saves and is reused on the next update.
19. App diagnostics contain `archive_update` events for planning, callbacks, actual-size validation, receipt writes,
    packing, cancellation, cleanup and final outcome. Per-operation identifiers, file indices, kinds, byte lengths and
    fixed reason codes are logged; paths, filenames, board/thread IDs, URLs and post content are omitted.
