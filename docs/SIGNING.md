# APK Signing

## Release Key Policy

The private release key is not part of this repository or source archives. Never commit or upload:

- `.jks`, `.keystore`, `.pem`, or private-key files;
- passwords, tokens, or populated signing property files;
- shell history or build reports containing private paths or credentials.

`keystore.properties.template` documents the expected field names only. The main application build does not treat that template as a release credential.

## Certificate Continuity

Android accepts an update only when it is signed by the same key as the installed application. Before publishing, compare the candidate with the previous stable APK:

```sh
apksigner verify --verbose --print-certs candidate.apk
apksigner verify --verbose --print-certs previous.apk
```

The current public release certificate SHA-256 fingerprint is:

```text
a9fbac6c498f7ad8e7c56849afe43881966bed2ed2bc1dd1c73d0ffe0b9ebeba
```

This fingerprint is public verification data, not a private key. A different fingerprint means the APK cannot update existing installations and must not replace the stable release.

## Release Asset Rules

- Publish only the intended signed all-ABI APK.
- Do not attach unsigned APKs, build reports, keystores, or local configuration files.
- Record the APK SHA-256 and byte length before upload.
- Download the asset from GitHub after upload and verify its SHA-256 against the local candidate.
- Keep `update/data.json` fingerprint, length, version, and URL synchronized with the published asset.
