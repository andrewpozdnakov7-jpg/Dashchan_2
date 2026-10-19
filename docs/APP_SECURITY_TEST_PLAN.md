# Application lock and additional PIN — local test candidate

This source patch is not a security certification. It does not encrypt existing
databases, conceal the existence of the additional PIN, guarantee forensic
erasure, or remove posts/copies on external systems. The feature remains
experimental. Automated checks do not replace device lifecycle, biometric
hardware and removable-storage validation.

## Safety first

Test in a disposable parallel installation/profile. Keep any wanted backup OUTSIDE
the folder approved for deletion. Entering the additional PIN really deletes data;
the main PIN cannot restore it. Merely setting the additional PIN does not delete
anything. Protection is off until an unlock method is successfully configured.

Settings → Security:

- Unlock method: PIN only, biometrics only, or either method. PIN is 6–12 ASCII
  digits, entered twice. Settings require fresh primary-method authorization:
  main PIN in PIN-capable modes, strong biometrics in biometric-only mode.
- Biometrics: system strong biometric prompt and successful crypto-backed
  authentication. Only the combined mode offers the app PIN as a fallback.
  Biometric-only mode warns of lockout if the key/sensor becomes unavailable;
  it never falls back to the phone PIN or silently creates a replacement key.
- Additional PIN: different from main, entered twice after the deletion warning.
- Allow screenshots: OFF by default. Enabling requires fresh primary-method
  approval and a risk confirmation. For the explicit design-release allowlist
  3.2.62 through3.2.69, opt-in permits all application windows including lock/setup
  and recovery screens. It cannot override Android's system biometric window.
  From3.2.70, sensitive authentication/recovery windows remain secure. Disabling
  app lock removes opt-in; a later new lock starts with capture blocked again.
- Optional download folder cleanup: OFF by default; primary method + exact local SAF
  directory + explicit ALL-CONTENTS warning. No cloud provider or volume root.
  It deletes files from other apps and backups too if they are in that directory.
  A newly selected download directory never inherits old cleanup consent.

## Required manual scenarios

1. With lock disabled, verify ordinary navigation, sharing/imports, update install,
   playback and screenshots remain unchanged.
2. Set main PIN. Home → return, screen off → on, launcher, notification/deep link,
   file open and incoming share all require authentication before private access.
   Back cannot reveal the underlying activity. Verify no old-content flash or
   readable recents preview. Check both gesture and three-button navigation.
3. Verify correct/wrong PIN, 5 failed attempts and the retained retry delay after
   process restart. Try both PIN changes and disabling protection. Additional PIN
   must not authorize security setting changes.
4. Enable biometrics; test success, cancellation, missing hardware, system lockout,
   enrollment change and fallback to the main app PIN. No device-PIN fallback.
5. Rotate while entering PIN, confirming a new PIN and during a biometric prompt.
   PIN input/authorization is intentionally not serialized; setup may restart.
   Background completion of a verification must not unlock a hidden app.
6. Open gallery/editor/private browser and switch between app activities without
   redundant authentication. Leaving for another app requires unlock on return.
   Lock-enabled background/PiP playback is stopped; lock-disabled PiP is unchanged.
7. Seed disposable history, own/reply marks and texts, favorites, custom communities,
   boards, drafts/outbox, cookies, proxy credentials, temporary media, translations,
   saved video position and diagnostic logs. Enter additional PIN. All must be gone,
   including recovery/backup copies inside app storage. Appearance/custom fonts,
   themes, wallpapers, language models and PIN/biometric configuration remain.
   Reply background checks/push are disabled. Public posts remain on forum servers.
8. With folder cleanup OFF, downloaded files and external backups remain. With it
   ON, place disposable files/subfolders in the shown directory and a control file
   OUTSIDE it. Only directory contents disappear; the root/control file remain.
9. Enable folder cleanup, then change the app download directory. The option must
   become disabled, and neither directory may be deleted without new approval.
10. Revoke SAF access/remove SD card before additional PIN. Internal data is cleared;
    unavailable external cleanup is NOT reported as successful. Retry after access
    is restored. Skipping remaining external files requires main PIN and retains a
    warning in Security. Wrong/additional PIN cannot skip. Test nested folders.
11. Interrupt/reset process during cleanup. On next launch the journal resumes
    before stores/providers read an old local profile. Main PIN cannot bypass a
    partial reset. Verify discarded histories are not restored by old writers.
12. After reset, main and additional PINs still work, but data does not return. SAF
    grants and folder-cleanup consent are reset; select folders again explicitly.
    Verify both distribution builds; security code uses platform APIs at minSdk30.

### Re-audit regressions (R01–R04, 2026-10-16)

These scenarios are requirements, not a record of successful execution.
Use only a disposable installation, synthetic backup and disposable folders.

- R01: approve tree A, keep grants to A/B, restore preferences selecting B through
  both supported backup formats. Folder-cleanup consent must be off before the
  restore is queued and when it is applied after restart. Entering additional PIN
  must not remove either folder. A queued older-version restore must also revoke
  consent. If state commit fails, do not replace current preferences; retain the
  pending restore. Restoring even the same folder requires fresh deletion consent.
  Merely revoking grant A, without applying a backup or selecting another folder,
  must still retain the exact A obligation and enter external recovery.
- R02: skip unavailable A using the primary PIN; then perform a local-only reset.
  A and its warning must remain. Approve/clear a different B: warning about A must
  still remain. Only proven cleanup of A closes its known-target result. A legacy
  boolean warning without a provable target remains unresolved, not silently cleared.
- R03: for primary PIN change, additional PIN, downloads and disabling protection,
  leave with Home and return to the same Activity. A fresh correct primary PIN
  must reopen the current RAM session and allow completion. Wrong/additional PIN,
  expired verification and the old approval callback must not authorize changes.
- R04: hold the disposable state lock or fail one startup read when no reset was
  requested. Release the fault and retry. The app must start a clean ordinary main
  process, preserve the profile and require PIN if configured; no synthetic reset
  receipt or deletion. Genuine pending cleanup still blocks normal startup. Test
  completed, expired, old-boot receipts and background completion separately.

## Architecture and verification scope

- Central application activity lifecycle gate and opaque secure shields, plus
  deferral of side-effect entry activities and navigation intents.
- PIN PBKDF2-HMAC-SHA256 (600,000 iterations, random 32-byte salt), then a Keystore
  HMAC verifier. Strong biometric authentication uses an auth-per-use Keystore Mac.
  No PIN is put into saved state, preferences, logs or an Intent.
- Bounded cross-process file locks and AtomicFile configuration under
  no_backup/app-security-v1. Authorization is RAM-only in the private session
  provider, with Binder lifetime tokens and revocable authentication generations;
  a saved `unlocked` field never authorizes access. Authentication resets on cold
  main-process startup. Screen-off/background revoke access; configuration
  recreation is not a login. Settings callbacks require a current authorization
  both when presenting approval and immediately before committing it.
- Separate :security reset activity stops other application processes and jobs;
  a component-factory recovery barrier prevents ordinary ContentProviders, stores
  and old saved Activities from starting while a reset is pending. Profile
  generations reject stale saved state/writers. Cleanup never runs recursively
  on the application startup or UI thread.
  Local-complete and pending-external phases are distinct to allow explicit recovery
  when removable storage is unavailable. All-content SAF deletion is pinned to the
  separately approved tree, with descendant checks; it never deletes the tree root.
  Availability loss does not revoke consent. Recovery accepts a newly granted
  permission only for the exact approved URI, never a replacement folder.
- Manual preference restores revoke destructive-folder consent before staging and
  before atomic startup application. Unresolved external-cleanup targets survive
  later local-only resets and success for other targets. Transient startup failure
  uses a separate normal-relaunch path once state is readable; it never starts
  deletion without a real pending journal or bypasses the configured main PIN.
- All registered private Activity/Dialog windows are secure and shielded when
  locked. Own/reply, download and audio notifications are redacted while enabled,
  including notifications that already existed when protection was configured.
  Explicit exports, existing externally granted/shared copies, system logs and
  remote push/server state are outside guaranteed deletion. An already submitted
  network post cannot be recalled by killing the local sender.
- JUnit covers PIN rules/derivation/clearing, retention allowlist, idempotent
  filesystem cleanup and fail-closed enumeration. Instrumentation uses isolated
  records/test-only Keystore aliases: PIN separation, primary-only checks,
  write failure, stale authorization, monotonic retry/boot behavior, profile
  generations, installed component factory, private windows and notification
  redaction. It never calls full profile cleanup on an ordinary installation.
- Destructive end-to-end scenarios must run only in an explicitly disposable
  emulator/profile with synthetic files and an outside-tree control file.
  Keep build/test results separate from this generic test plan. Review the
  remaining device scenarios above before trusting the feature with sensitive data.

## R05: retain player button placement (prepared; not device-verified)

The presentation allowlist retains exactly `video_buttons_layout_v1`, containing
only button identifiers and the column count. No `video_*` prefix rule is used;
playback history, saved file positions and arbitrary future keys remain excluded.
The policy regression test is prepared, not a recorded execution result.

Only on an explicitly disposable profile: select a nondefault button order and
column count, perform the additional-PIN reset, then verify that the same layout
and number of columns remain after cold startup. Also verify that personal data
and the last-video position were removed as required. Do not run this scenario
against real user files. The previously prepared R01-R04 device scenarios still
require execution; this small retention fix does not establish runtime safety.

## Optional screen capture (prepared; no device execution recorded)

The capture setting is stored in no-backup security state, not normal restored
preferences. Authentication, shielding/accessibility gates, notification redaction
and background/PiP blocking do not use it. Invalid/unreadable state and real
pending cleanup fail closed. `FLAG_SECURE` is removed from ordinary unlocked
foreground windows when explicitly allowed. A temporary all-window design-review
override before 3.2.70 is documented below. Outside that override, dialogs, reset
and explicitly secure popup windows retain their normal policy. Activity pause
still restores concealment; secure flags depend on the temporary capture override;
resume re-evaluates them. Do not infer device-level capture guarantees from flags.

Prepared tests: four pure policy fixtures, three isolated SecurityStore tests and
three dialog/popup fixtures. They have not been compiled or run for this patch.
Required device checks, without deleting user data:

- Fresh/old installation: absent permission remains OFF. With main PIN/biometrics,
  an ordinary page, gallery, both player styles, editor and private browser are
  not capturable until the setting is enabled.
- Enable using the main PIN and explicit confirmation; cancel/wrong/additional
  PIN, Home/screen-off and stale confirmation must not save approval. Disabling
  again restores blocking. Reopening the setting must show its committed value.
- Ordinary unlocked Activity, Dialog and Popup become capturable consistently.
  Before 3.2.70, capture opt-in also permits approval/recovery/PIN windows and
  explicitly secure app popups. Outside that temporary override they stay protected.
- Home/recents, notification shade, screen-off, PiP, rotation and split-screen:
  no private content should appear while locked/paused; delayed popup updates
  cannot clear background capture protection. Return requires normal unlock.
- Screen recording/casting and OEM recents need separate validation: permitting
  screenshots also relaxes the same platform capture protection for other output.
  No claim is made that screenshots are permitted while all recording is blocked.
- Capture approval cannot unredact notifications, revive a revoked session,
  bypass pending cleanup, or be enabled by restoring ordinary preferences.
  Saved captures are outside profile cleanup guarantees and may remain afterward.

## Fresh audit N01-N03 (source fixes; device execution outstanding)

- N01: Reddit category selection and shared DropdownView use SecureSpinner. The
  native collapsed control/adapter remain, but selection uses a registered
  SecureDialogBuilder dialog, not an unregistered native popup. Restoration of
  legacy widget state cannot reopen the native dialog. ClickableToast uses the
  SecurePopupContext WindowManager, cancels protected messages on Activity pause,
  and refuses new content while paused/locked. Screenshot opt-in changes capture,
  never the requirement to conceal locked data. Two real dialog-creation fixtures
  are prepared; they do NOT show actual windows or prove capture/lifecycle behavior.
- N02: SecurityStateCodec checks 65536 serialized UTF-8 bytes BEFORE startWrite
  and in readback. Ledger stores up to 32 SHA-256 identities of exact URI strings;
  invalid/overflow entries retain a durable unknown/incomplete warning. Identities
  are not permissions or automatic deletion targets. The exact consent/reset URI,
  credentials, retries, generation and pending journal remain unchanged. Valid
  legacy input up to 1MiB is compacted under the existing lock only if the result
  fits 64KiB. Invalid, larger or still-oversized non-ledger state remains closed
  and untouched, never replaced by an empty profile. New consent checks projected
  state including reset_tree and leaves 16KiB reserve for future transitions.
  Six codec fixtures and three store fixtures are prepared, not executed.
- N03: after stopOtherProcesses confirms writers stopped, PrivacyReset reads the
  current XML directly through PresentationPreferencesFile, not the process cache.
  The same exact presentation allowlist and forced tracking-OFF values apply.
  String/boolean/int/long/float/string-set values use AtomicFile+sync and parsed
  readback comparison. The startup notice counter has its own exact allowlist.
  XML is bounded to 16MiB; DTD/malformed/oversized input fails before replacement,
  with pending journal intact. Five disposable XML fixtures are prepared, not run.

Required isolated device scenarios (none executed for this patch):

1. Real Reddit category/DropdownView selection and short/long ClickableToast;
   selection/cancel/rotate/recreate, Home/screen-off/session revocation while open.
   Test lock OFF, default capture blocking and unlocked capture opt-in in both
   distributions. Locked/paused contents must hide despite capture opt-in.
2. Exact 64KiB and +1 byte; long-URI repeated skips; legacy journal migration with
   correct/wrong/additional PIN and pending state; failed migration/write and
   restart. Old warning/credentials/cleanup target must not disappear on failure.
3. Two or three full resets with the same live :security PID. Durably change
   theme, scale, player button order and notice counter between resets; verify
   current appearance survives, personal keys do not. Include interrupted writes.
   Use only an explicitly disposable installation and synthetic files.

No Gradle, compilation, lint, test execution, real capture/reset/SAF deletion,
installation or publication was performed. Prepared tests are not passing results;
prior R01-R05 and optional-capture device requirements still apply.

Platform references:

- https://developer.android.com/identity/sign-in/biometric-auth
- https://developer.android.com/privacy-and-security/keystore
- https://developer.android.com/reference/android/app/Application.ActivityLifecycleCallbacks
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE

## Temporary all-window design capture (expires at 3.2.70)

Supersedes the earlier PIN-only exception at the user's explicit request.
In exact versions 3.2.62 through 3.2.69, the approved screenshot checkbox permits
capture of ALL app-owned Activity/Dialog/Popup windows, including PIN, approvals,
cleanup and recovery. Pending cleanup does not revoke capture approval, but still
blocks profile access and runs the same recovery procedure. The capture override
does not authenticate, unshield private data, change retry delays, unredact
notifications, cancel cleanup or relax stale-authorization checks.

It requires readable state with a configured primary PIN and stored opt-in.
Unchecked/absent opt-in or unreadable state retain normal capture restrictions.
From exact version 3.2.70, and for unknown/beta names, the override is OFF; ordinary
unlocked screenshots can still use the normal opt-in behavior. Temporary summary
and enabling warning switch back to normal copy automatically at expiry.

App-owned capture flags are removed even if a popup initially specified SECURE.
Re-disabling opt-in restores the normal flags, including those originally secure.
Android-managed biometric/permission/DRM or device-policy windows are outside
the app's control. Screen recording/casting has the same app-side relaxation.

Prepared source fixtures were updated for version boundaries, explicit consent,
capture without unlocking, pending recovery, dialogs, locked-popup concealment
and restoration of explicitly secure popup flags. Not executed or compiled.

Device checks outstanding:

- Opt-in ON: capture PIN, changes, approval dialogs, popups, cleanup and recovery.
  Use disposable fixtures and never record a real PIN or private folder contents.
- Opt-in OFF: verify normal restrictions return across those same window types.
- Wrong/correct PIN, biometrics, attempts, Home/recents, rotation and background
  still enforce authorization and conceal private profile contents as before.
- A pending operation remains pending despite permitting a picture of its screen.
- Unknown/3.2.70 versions retain the normal protected PIN/recovery-window behavior.

## Optional recent-authentication interval (source-only, not executed)

The GitHub security screen offers Immediately (default), 3, 5, 10, 30, 60, 120
and 300 minutes. Configuration lives in the atomic no-backup security record,
requires fresh PRIMARY verification, and accepts only this exact allowlist.
It is not an idle timer and reopening cannot slide the original deadline.

The provider keeps a monotonic-clock recent-auth grant in RAM, bound to the
authenticating process's live Binder. Background/screen-off immediately suspend
the foreground session, conceal private windows and invalidate callback leases.
On a subsequent interactive foreground entry, an unexpired remembered grant can
restore the session without another prompt. Expiry is checked on entry; it does
not interrupt ongoing foreground use with a new PIN screen.

Explicit lock, main-process restart, dead authenticating Binder/provider, storage
failure, credential/interval change or pending cleanup revoke remembered grants.
An empty-profile receipt and ordinary internal unlock never create a new grant.
Settings authorization is still a fresh PRIMARY PIN operation, not inherited
from the remembered ordinary-unlock grant. Changed-interval confirmation uses
the actual preceding verification time, not time spent reading the dialog.

Prepared fixtures: exact allowlist/defaults, expiry boundary, invalid/restarted
clock, non-sliding reads, foreground-only restoration, explicit revocation,
process start, changed interval, pending reset and stale callback generations.
The on-device expired-grant fixture requires >3 minutes of monotonic uptime;
it explicitly skips on younger boots. Pure expiry tests have no such requirement.

Device checks still required, only on a disposable installation:

1. Immediately: Home/screen-off require authentication as before.
2. Three minutes: PIN and strong biometrics each establish a grant; repeated
   Home/app, screen-off/on, PiP and multiple Activity handoffs within the window
   do not ask again, but background content remains concealed.
3. Return after three minutes from the ORIGINAL successful authentication:
   ask again, even if the app was reopened repeatedly in between.
4. Stay foreground beyond the interval: do not interrupt current work; after
   the next background/return, require authentication.
5. Force-stop/main-process death and restart: ask again despite the saved interval.
6. During a valid grant, changing PIN, consent, biometrics or destructive settings
   still requires fresh primary-method authentication. Background/cancel prevents
   stale callbacks (main PIN in PIN-capable modes, strong biometrics in BIO-only).
7. Interrupted/pending cleanup cannot resume via the timer or an old receipt.
8. No settings entry or search route in F-Droid. Existing active locks are never
   silently disabled by hiding settings.

No build, lint, tests, installation or device validation was performed. The live
Wiki needs an interval-section update before release; no further publication is
authorized by the current source-edit request alone.

## Explicit unlock modes and automatic biometric prompt (source-only)

GitHub Settings -> Security -> Unlock method offers PIN only, Biometrics only,
or PIN and biometrics. The combined option means either method, not two-factor
authentication. On ordinary entry both biometric-capable modes automatically
request strong system biometrics once per Activity resume. Cancel/error leaves
the main PIN available only in the combined mode; retry is explicit. There is
no automatic enrollment or fallback to the device's PIN/password.

PIN-only and old optional-biometric records retain their configured methods.
Unknown/incomplete explicit modes and orphaned biometric flags fail closed.
Biometrics-only protection is recognized by every common lock/capture/grace
gate even though no primary PIN record exists. Its configured additional PIN
is accessible only as a clearly labeled destructive-cleanup action, never as a
regular unlock or settings credential.

Switching to BIO-only warns of lockout risk and removes the primary PIN only
after strong biometric CryptoObject proof and an atomic state commit. Every
enrollment uses a unique staged Keystore alias, without deleting/replacing the
active key. Cancelled pre-commit attempts delete only their own unused key;
uncertain commit outcomes retain the staged key to avoid deleting a reference
that may already be durable. No keys are extracted. Isolated test contexts
use separate key namespaces and cannot reference the production key.

The same fresh-method authorization applies to settings: main PIN in PIN-capable
modes, strong biometrics in BIO-only. Background/pause invalidates the Activity
generation and any staged credential/proof. Recent-auth intervals cannot approve
settings or a cleanup skip. External-cleanup recovery in BIO-only requires fresh
strong biometrics and the exact current key/lease; it never grants a normal
profile session while reset_pending remains set.

Prepared, not executed: pure legacy/mode validation fixtures; isolated missing
proof, stale authorization, invalid metadata, no-PIN lock/capture gates, rejection
of old PIN fallback, preservation of additional-PIN semantics and foreign/missing
key rejection. Hardware biometric success/cancellation must be verified manually.

Required disposable-device matrix, without using real profile/download data:

1. Fresh install: independently configure each mode. Cancel at each setup stage:
   no partial PIN or biometric configuration; an old working mode remains intact.
2. Combined: automatic prompt; cancel/error/unavailable sensor -> main PIN works.
   Success unlocks once; Back does not create a repeating automatic prompt loop.
3. PIN only: no biometric prompt or biometric fallback. BIO only: no primary PIN
   input/fallback; settings require fresh biometric proof, not the grace period.
4. All six transitions between modes: fresh current-method confirmation, new PIN
   when required, extra biometric proof before removing an old PIN, cancellation,
   rotation, Home/screen-off and force-stop before/after commit.
5. Change biometric enrollment/invalidate key: combined still accepts the main
   PIN; BIO only remains locked and does not silently recreate a key or disable
   security. Confirm the upfront warning before testing this on expendable data.
6. Additional PIN setup/use in BIO-only: only the destructive action accepts it.
   Interrupted external cleanup -> retry/restore folder, fresh biometric skip,
   cancelled or stale proof, then one-shot empty-profile launch.
7. Re-run interval/grace and screenshot opt-in checks with no primary PIN record.
8. Read/write failures and missing/foreign key references never open a profile.

No compilation, Gradle, lint, test execution, install, hardware-authentication or
destructive device checks were performed. No Wiki/release publication in this
source-only change. Update both public guide languages before release.
