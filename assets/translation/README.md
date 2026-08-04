# Offline translation assets

Slooop uses the Mozilla Bergamot engine for optional on-device post translation.

The GitHub distribution bundles the following pinned runtime at build time:

- Firefox Bergamot JavaScript wrapper, Firefox revision
  `8c19fa8bd6e924436ee5f8126b12b50f324348ba`, SHA-256
  `fb2dfc1d7a416aa8850af223f3e494b4a90efa16e121941dd75671294f87871f`;
- Bergamot WebAssembly runtime v0.6.0, upstream revision
  `1de4a085d3a7afb625c51a60aabb5ad298e4059f`, SHA-256
  `a3a89d9ad0a4ed8f27bf3e403701b23f5709816f6376438503f2fa5b0182c2dc`.

Both runtime components are licensed under MPL-2.0. The runtime URLs and checksum verification are declared in
`build.gradle`. Language models are not bundled in the APK. They are downloaded only after explicit user consent,
verified against the sizes and SHA-256 checksums in `TranslationModel.java`, and stored in private application data.
The complete license text is bundled as `assets/translation/MPL-2.0.txt`.

Source and license references:

- https://searchfox.org/mozilla-central/source/toolkit/components/translations/bergamot-translator
- https://github.com/mozilla/translations
- https://www.mozilla.org/MPL/2.0/

The F-Droid flavor does not package or expose the translator until the WebAssembly runtime can be built from source
inside the F-Droid build environment and the model-download policy is accepted by its reviewers.
