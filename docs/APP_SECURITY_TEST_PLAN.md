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
anything. The entire feature is off until the main PIN is configured.

Settings → Security:

- Main PIN: 6–12 ASCII digits, entered twice; PIN changes require the main PIN.
- Biometrics: system strong biometric prompt, explicitly enabled after main PIN
  confirmation and a successful crypto-backed authentication. Unavailable or
  invalidated biometrics fall back to the app PIN, not the phone PIN.
- Additional PIN: different from main, entered twice after the deletion warning.
- Optional download folder cleanup: OFF by default; main PIN + exact local SAF
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

Platform references:

- https://developer.android.com/identity/sign-in/biometric-auth
- https://developer.android.com/privacy-and-security/keystore
- https://developer.android.com/reference/android/app/Application.ActivityLifecycleCallbacks
- https://developer.android.com/training/data-storage/shared/documents-files
