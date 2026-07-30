# GitHub Actions Signing

The `Android Signed Candidate` workflow creates a temporary signed all-ABI APK for verification. It does not create a tag, GitHub Release, or update manifest entry.

## Security model

- The workflow is manual and runs only from protected `master`.
- Signing secrets are provided by the protected `release-signing` GitHub Environment.
- Gradle builds the unsigned APK without access to signing secrets.
- The keystore exists only in the runner temporary directory during the signing step.
- The artifact contains only the signed APK and `SHA256SUMS.txt`.
- The candidate must use the established public certificate fingerprint from [SIGNING.md](SIGNING.md).

Anyone who can modify a workflow on `master` could attempt to access Environment secrets. Protect `master`, require CI and pull-request review, disable force pushes, and restrict the Environment to `master` before adding the keystore.

## Environment setup

Create a GitHub Environment named:

```text
release-signing
```

Restrict its deployment branches to `master`. Add a required reviewer when a second trusted maintainer is available. The workflow does not create a deployment record, but Environment branch restrictions and reviewer gates still apply.

Add these Environment secrets through the GitHub settings interface:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Do not put their values in a commit, issue, pull request, workflow input, Actions variable, log, or chat message.

Before uploading the keystore, keep at least two offline backups and verify locally that its SHA-256 certificate fingerprint matches the fingerprint documented in [SIGNING.md](SIGNING.md).

## Encoding the keystore on Windows

Use a local PowerShell session. Replace the placeholder with the actual keystore path, but do not save the resulting Base64 text to the repository:

```powershell
$keystorePath = '<path-to-release-keystore>'
$keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
$keystoreBase64.Length
if ($keystoreBase64.Length -gt 49152) { throw 'Base64 keystore exceeds the 48 KB GitHub Secret limit' }
$keystoreBase64 | Set-Clipboard
```

Paste the clipboard contents directly into `ANDROID_KEYSTORE_BASE64`, save the secret, and clear the clipboard:

```powershell
Set-Clipboard -Value ''
Remove-Variable keystoreBase64, keystorePath
```

GitHub limits an individual secret to 48 KB. If the Base64 value does not fit, stop and redesign the storage method; never commit the keystore, an encrypted keystore, or a populated signing-properties file without a separate security review.

## Manual run

After the workflow has been reviewed and merged into protected `master`:

1. Open `Actions` and select `Android Signed Candidate`.
2. Select `Run workflow` on `master`.
3. Enter the exact confirmation text `SIGN CANDIDATE`.
4. Approve the `release-signing` Environment if a reviewer gate is configured.
5. Wait for every verification step to pass.

The workflow checks alignment, APK Signature Scheme v3, certificate continuity, application ID, version fields, and the exact `arm64-v8a`, `armeabi-v7a`, and `x86` ABI set.

## Candidate audit

Download the temporary artifact and independently verify it before testing:

```sh
sha256sum -c SHA256SUMS.txt
apksigner verify --verbose --print-certs Slooop-*-all-abi-signed.apk
zipalign -c -P 16 -v 4 Slooop-*-all-abi-signed.apk
```

Confirm that the certificate matches [SIGNING.md](SIGNING.md), then install the candidate over the latest public Slooop without uninstalling it. Verify that application data remains intact.

Passing this workflow does not authorize public distribution. Publishing still requires the explicit release process defined in `CODEX.md`.
