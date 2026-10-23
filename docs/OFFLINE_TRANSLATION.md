# Offline post translation

## User-visible behavior

Offline translation is an experimental feature and is disabled by default. When enabled, the user chooses Russian
or English as the native language. The corresponding English-to-Russian or Russian-to-English package can then be
downloaded or deleted from the same settings screen. Automatic translation is enabled by default inside the feature.

- Russian native language: translation controls are shown on 4chan, not on 2ch.
- English native language: translation controls are shown on 2ch, not on 4chan.
- The toolbar action switches between translated subjects/comments and the untouched original posts.
- Only posts bound to visible list items are submitted to the engine. Newly visible posts are translated lazily.

Post text is translated locally. It is never sent to Mozilla or another translation service. Network access is used
only when the user explicitly downloads a language package.

## Process and data boundary

`TranslationController` queues visible posts in the main process. `TranslationService` runs in the private
`:translation` process and hosts the Bergamot WebAssembly runtime in a locked-down WebView origin. The WebView can
load only bundled runtime files and verified model files from private application storage. It cannot browse arbitrary
URLs. Requests and results cross the process boundary through AIDL. The binding and engine are released after idle
time so the model does not permanently occupy memory.

The original `PostItem` HTML and spans are never overwritten. Translated HTML is parsed into a separate cache using
the same markup pipeline, so links, references, spoilers, and the original/translation toggle use the existing post
rendering architecture.

## Runtime provenance

The GitHub flavor prepares these pinned files during asset merging:

| File | Upstream | SHA-256 |
| --- | --- | --- |
| `bergamot-translator.js` | Firefox revision `8c19fa8bd6e924436ee5f8126b12b50f324348ba` | `fb2dfc1d7a416aa8850af223f3e494b4a90efa16e121941dd75671294f87871f` |
| `bergamot-translator.wasm` | Bergamot v0.6.0, revision `1de4a085d3a7afb625c51a60aabb5ad298e4059f` | `a3a89d9ad0a4ed8f27bf3e403701b23f5709816f6376438503f2fa5b0182c2dc` |

Firefox records the runtime as MPL-2.0 in its Remote Settings metadata and third-party manifest. The wrapper is used
without modification; `runner.js` is project code that supplies Firefox-compatible model configuration and request
handling.

## Language packages

The package definitions in `TranslationModel.java` pin every compressed and unpacked file by exact byte size and
SHA-256. Installation uses a staging directory and publishes the package only after all checks succeed.

| Direction | Mozilla model | Download | Installed |
| --- | --- | ---: | ---: |
| English to Russian | `retrain_base-memory_KJ23-iDVTcymG1ZldWY17w` | 23,786,432 bytes | 35,240,782 bytes |
| Russian to English | `spring-2024_QrcdYgbwS7e7xbhtOSdoNQ` | 14,995,467 bytes | 22,530,152 bytes |

The models come from Mozilla's public translations-data bucket and the Mozilla translations project. See
https://github.com/mozilla/translations and
https://github.com/mozilla/translations/issues/1434#issuecomment-4734030816 for model provenance and licensing
clarification.

## Distribution boundary

`BuildConfig.ENABLE_LOCAL_TRANSLATION` is true only for the GitHub flavor. The F-Droid flavor neither packages the
prebuilt WebAssembly runtime nor exposes translation settings. Enabling it for F-Droid is a separate task requiring:

1. a reproducible source build of Bergamot WebAssembly inside the F-Droid recipe;
2. scanner and license review of the produced runtime;
3. reviewer agreement for the optional model downloads;
4. reproducibility and on-device tests of the resulting F-Droid APK.

### F-Droid preparation: not enabled yet

The missing translation settings are an intentional build boundary, not a missing language download or a setting
that can be restored from a backup. `ContentsFragment` and `SettingsSearchIndex` hide the entry when
`ENABLE_LOCAL_TRANSLATION` is false; `TranslationController` and `Preferences` also enforce that flag. Separately,
`androidComponents.onVariants` adds `prepareBergamotRuntime` assets only to GitHub variants. Changing just one of
these conditions would expose an unavailable translator or package a prebuilt runtime without a source recipe.

Before enabling Mozilla translation in F-Droid:

- Pin the runtime source revision and all submodules, the Emscripten/toolchain version and build options. Produce
  the JavaScript wrapper and WebAssembly as a matched pair compatible with `assets/translation/runner.js` and the
  pinned models; do not assume another Bergamot release has the same API or model compatibility.
- Add a local-runtime input path to the build, with no fallback download of Mozilla's prebuilt WASM for F-Droid.
  Use the same source-built runtime and build inputs for signed references and the upstream recipe.
- Verify redistribution terms for the engine and both optional model packages, and resolve any reviewer concerns
  about model downloads. Keep Google translation, Gemini Nano, Material UI and app protection excluded.
- Test language-package download, cancellation, deletion and both translation directions, including offline use
  after installation. Verify the three ABI APKs and their reproducibility before making the update ready.

The current preparation does not enable Bergamot, publish reference APKs, change the stable release or assert that
an upstream build with translation has passed. Keep the source PR and F-Droid MR in draft until these prerequisites
are satisfied.
