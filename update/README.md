# Update Metadata

This directory contains the public metadata consumed by Dashchan_2.

## Files

- `data.json`: stable update manifest loaded through `BuildConfig.URI_UPDATES`;
- `themes.json`: downloadable theme catalog loaded through `BuildConfig.URI_THEMES`.

The opt-in beta channel reads `update/data.json` from the separate public [`Dashchan_2_Update_Test`](https://github.com/andrewpozdnakov7-jpg/Dashchan_2_Update_Test) repository. Stable clients never use beta releases unless the user changes the update channel.

## Stable Manifest

The `client` entry describes the current main APK:

- `name` and `code` must match `versionName` and `versionCode`;
- `length` is the exact APK byte length;
- `source` is the final GitHub release asset URL;
- `fingerprint` is the lowercase SHA-256 signing-certificate fingerprint;
- `minSdk` prevents incompatible Android versions from receiving the APK.

The legacy `dvach` entry remains for compatibility with installations that still have the old separate extension. Current Dashchan_2 releases bundle Dvach into the main APK and do not require that extension.

Update `data.json` only after the signed release asset is publicly downloadable. Download the asset again, compare its SHA-256 with the tested local APK, then publish the manifest in a separate commit.

## Themes

`themes.json` is a self-hosted JSON object with a top-level `themes` array and no redirect. The application can merge this catalog with additional compatible sources while deduplicating theme names. Built-in themes are stored separately under `res/raw`.

Validate both files before pushing:

```sh
python3 -m json.tool update/data.json >/dev/null
python3 -m json.tool update/themes.json >/dev/null
```
