Dashchan_2 update metadata is served from this directory.

- `data.json` is the legacy client/extension update metadata used by
  the stable update channel through `BuildConfig.URI_UPDATES`.
- The opt-in beta channel uses `update/data.json` from the separate public
  `Dashchan_2_Update_Test` repository. Keeping beta releases there prevents
  old stable clients from discovering GitHub pre-releases.
- `themes.json` is the theme metadata used by `BuildConfig.URI_THEMES`.

Both files are intentionally self-hosted in
`andrewpozdnakov7-jpg/Dashchan_2` and do not redirect to upstream metadata.
