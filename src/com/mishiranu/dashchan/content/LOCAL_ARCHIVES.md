# Slooop local archives

This document describes the local thread archive pipeline and its on-disk format. Keep it in sync with
`LocalArchiveManager`, `SendLocalArchiveTask`, `PackLocalArchiveTask`, `LocalArchivesFragment`, and
`LocalArchiveViewerFragment`.

## Goals

- Keep a normal, portable HTML page that can be opened without Slooop.
- Offer a mobile post-feed view inside Slooop without rewriting the saved HTML.
- Keep old Dashchan/Wakaba HTML archives readable.
- Optionally package the page and downloaded resources into one ZIP file.
- Allow read-only browsing of archives in additional folders selected through Android's Storage Access Framework.

## Save pipeline

1. `SendLocalArchiveTask` snapshots the posts into Wakaba-style UTF-8 HTML in an internal temporary file.
2. It builds a versioned JSON manifest and sends the HTML and manifest to `DownloadService`.
3. Requested thumbnails and original files are downloaded to sibling resource directories.
4. When ZIP output is enabled, `ZipCoordinator` waits for the HTML, manifest, thumbnails, and original files.
5. `PackLocalArchiveTask` calls `LocalArchiveManager.createZip`. Media is stored without recompression so that
   packaging is fast and does not heat the device unnecessarily.
6. Duplicate archive names are resolved before saving with `_1`, `_2`, and subsequent suffixes.

An incomplete media download prevents creation of the ZIP copy. The loose HTML and successfully downloaded files
remain available.

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

The HTML uses relative paths, so moving the HTML together with its same-named resource directory preserves it as a
standalone archive. New pages also contain a harmless `slooop-local-archive` meta marker, allowing Slooop to recognize
them if the JSON sidecar is lost. The JSON sidecar is optional for old and third-party pages.

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
  "view": "adaptive",
  "chan": "dvach",
  "board": "b",
  "thread": "123",
	"posts": 120,
	"attachments": 48,
	"savedFiles": true,
	"savedThumbnails": true,
  "title": "Optional thread title"
}
```

Rules for future changes:

- Add optional fields without changing `format`.
- Increment `format` for incompatible structural changes.
- Readers must ignore unknown fields.
- Absence of a manifest means a legacy archive, not a broken archive.
- The package name may remain `io.dashchan2` for Android update compatibility; it is not the archive schema name.

## Viewer modes

`LocalArchiveViewerFragment` keeps the source HTML unchanged in memory and can reload it in either mode:

- **Original HTML**: renders the page as saved. Legacy archives open in this mode by default.
- **Adaptive view**: reads the post markers and `data-*` metadata embedded by `WakabaLikeHtmlBuilder`, then builds
  a separate theme-aware mobile post feed with headers, attachments, and comments. New format-2 Slooop archives
  open in this mode by default. Old pages without structured post markers retain the responsive CSS fallback.

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
3. New loose HTML plus JSON defaults to Adaptive view and can switch back to Original HTML.
4. New ZIP defaults to Adaptive view and resolves thumbnails and originals.
5. Equal archive names in the default folder and an added SAF folder remain separate list entries.
6. Revoking one SAF permission does not prevent the default Archive folder from loading.
7. Malformed manifests fall back to legacy behavior.
8. Unsafe resource paths are rejected.
9. Switching viewer modes immediately moves the checked radio item and never modifies the saved HTML.
10. A Slooop/Wakaba page with structured post markers renders as a post feed in Adaptive view.
