# Downloaded update handoff

Source-only change based on 3.2.63. No release version, signing configuration,
server manifest or repository policy is changed. Compilation, automated tests and
device checks have not been run for this patch.

## Contract

- Persist the selected client version/code, size, expected SHA-256 and signer
  before downloading; do not persist source URLs or arbitrary filesystem paths.
  The legacy-shaped manifest parser now reads its optional `sha256sum` field;
  previously it silently discarded the checksum present in the current manifest.
  Client receipts require complete version/code/size/hash metadata. Existing
  legacy extension entries without checksums keep their previous parser behavior.
- A manual request for the same artifact reuses the existing APK only after full
  validation. Missing/partial/mismatched files are downloaded again.
- Before committing a client session, check package name, version, release mode,
  expected signer, installed signer and hash. Also hash the actual bytes copied
  into PackageInstaller, and reject a changed receipt before commit.
- A foreground, focused and unlocked MainActivity can open UpdaterActivity when
  downloading completes. A service/application Context must not launch it from
  the background. In the background publish a notification and keep the receipt.
- On the next eligible foreground opportunity, offer Install / Later, even if
  notifications are disabled. Mandatory dialogs and authentication take priority.
- After Later, wait 3 additional cold main-process launches, then 6, then 15,
  then 15 repeatedly. Rotation, unlock, and background/foreground transitions in
  the same process do not count. Closing and reopening an Activity in a retained
  process also does not count. This deliberately follows cold process launches.
- Starting an installation also records a postponement so cancelling the system
  installer cannot immediately reopen the offer. A retry/recovered session in
  the same process does not advance the schedule twice. Successful replacement
  clears a receipt whose target version is now installed.
- Re-selecting identical metadata retains the schedule. Selecting a different
  artifact starts a new receipt/schedule. The receipt does not follow backups.
- F-Droid keeps ALLOW_APPLICATION_SELF_UPDATE=false: no receipt or reminders for
  application updates. Shared visible-host tracking still allows the existing
  consent-based extension installation to open from the foreground.

## Prepared automated coverage

- `UpdateReminderPolicyTest`: exact 3/6/15/15 boundaries, repeated lifecycle events
  versus new process identifiers, counter saturation.
- `DownloadedUpdateTest` (GitHub instrumentation): receipt round-trip, exact
  reminder schedule using synthetic process boundaries, repeated retries,
  unchanged versus replaced metadata, stale receipt IDs, malformed/traversal
  records, missing/partial/corrupt/non-APK bytes, already installed version,
  copied-byte checksum/length rejection and failed persistence.
  Also checks that a legacy-shaped client manifest passes its checksum into the
  actual download request rather than silently dropping it.

The instrumentation fixture uses unique test preference names, unique synthetic
files, and never invokes PackageInstaller or clears an actual user profile.
Run instrumentation only on a disposable test installation. The source tests
do not prove OEM installer UI or real process-death behavior.

## Device checklist (not executed)

Use a disposable phone/profile with an older **officially signed** GitHub APK,
then update to a newer officially signed APK. Do not downgrade or uninstall a
valuable installation to reproduce this. Capture full system logcat before
starting; keep logs private.

1. Download while reading Slooop: one installer confirmation, no automatic
   installation and no duplicate session. Cancel: no immediate repeat offer.
2. Download while using a browser: notification only, no background Activity
   launch attempt. Return: Install / Later offers the downloaded APK.
3. Disable notifications and repeat case 2: return still offers installation.
4. Select Later: force-stop and manually relaunch for real cold launches. Check
   3, then 6, then 15, then another 15. Between these, rotate, unlock, open a
   browser and return: none of these should advance the persisted count.
5. Download, kill the process, relaunch: a complete valid APK is offered without
   network download; an interrupted/partial APK is never installed.
6. Select Download again for the same version: ready APK is reused, including a
   valid existing cached APK predating this patch once fresh metadata is read.
7. Deny/allow unknown-sources permission; cancel the system installer; rotate
   before/during confirmation; retry from notification and update settings.
8. Delete or corrupt **only a synthetic test APK**: report that it cannot be
   reused. Never enter PackageInstaller with those bytes.
9. Select a different newer artifact: old receipt callbacks/dialogs must not
   clear, snooze or commit the new request. Simultaneous Download presses must
   not create two writers for one cache file.
10. Complete the update: old receipt/reminders disappear; the existing Open
    Slooop notification remains subject to successful replacement/permission.
11. Test PIN, biometric-only and PIN-or-biometric, pending cleanup and storage
    setup. The prompt must never appear over authentication/mandatory dialogs.
12. Check extension-only and mixed extension/client update queues. Verify no
    application updater prompt or receipt appears in F-Droid.

Diagnostics use the `UpdateInstaller` tag and event/status codes only. They do
not include source URLs, APK paths, credentials, confirmation Intent extras or
raw PackageInstaller error messages.
