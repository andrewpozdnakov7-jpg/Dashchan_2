# Process-independent continuous-background reauthentication interval

Source patch; no device, Gradle, compilation or test execution claimed.

## Contract

- Successful PRIMARY PIN / strong biometric authentication establishes an unlocked session.
  Foreground reading does not consume the absence interval. A foreground-to-background
  transition starts a fresh interval; an early return allows a full interval on the NEXT
  departure. Example: 3 minutes selected, return after 2:30, read for ten minutes, leave
  again: another complete 3 minutes.
- Only the last foreground client leaving (or screen-off) issues a generation-bound
  background-write grant. Duplicate inactive/screen-off callbacks and background startup
  cannot renew it. RAM access closes before the receipt write. No disk I/O in the provider.
- Android process reclamation and a cold launch can restore the remaining interval, but
  background services alone cannot unlock. No change to the default Immediate setting.
- The same continuous absence includes sleep/process loss. Restore/query during that absence
  never moves its deadline. A missing departure event may require fresh proof; a guessed
  departure time is never used to grant extra access after a crash.
- Calendar time, timezone and automatic time adjustments are not inputs. Use elapsedRealtime
  (including deep sleep) plus BOOT_COUNT. A reboot or unavailable boot counter denies restore.
- A versioned HMAC receipt lives in the existing atomic no-backup security record. Its separate
  Android Keystore key is installation/sandbox-specific and is NOT the PIN verifier key.
- The receipt is bound to credentials, authentication mode, biometric key, interval and profile.
  Missing, expired, malformed, tampered or unverifiable receipts cannot authorize restoration.
  Only the separate background-write grant can renew an expired but otherwise verified
  receipt after a long foreground session; ordinary unlock cannot create that grant/proof.
- Explicit lock, credential/mode/interval/profile changes, pending cleanup and storage failures
  revoke the separate key. Revocation precedes state commit, so a failed file write cannot
  revive the old readable receipt. RAM leases/generation guards still protect stale callbacks.
- Fresh proof is still required to change security settings. No UI, screenshot policy, PIN
  derivation, biometric verification, reset targets or F-Droid feature gates are changed.
- Not protection against a rooted/modified OS or arbitrary code running as the app UID.

## Prepared automated coverage (not executed)

`ReauthenticationPolicyTest`: exact boundaries for every interval, unknown/new boot, elapsed
clock rollback, no sliding WITHIN an absence, renewal after long reading, the 2:30 return
and next full interval, and overflow-safe comparisons. No wall-clock dependency exists.

`SecurityStoreTest`: isolated cache sandbox and separate Keystore aliases, no real profile
deletion. Covers the process-entry lifecycle and foreground receipt restoration; original
receipt byte stability; interval/reset/credential/profile revocation; malformed/MAC-tampered
receipts; explicit lock plus replay; failed ordinary write and failed lock commit; Immediate
and stale-callback behavior, fresh settings-lease commits and a missing key without breaking
PIN verification. Also feeds a verified receipt to a freshly constructed, isolated session
authority with a new client Binder and no previous RAM state. Added background timestamp
persistence/duplicate callbacks, next departure, failed background commit, last-of-two-client
departure and duplicate screen-off grant cases. Process entry is simulated;
this does not replace an actual OS process-kill/provider-restart test on a device.

## Manual check on a disposable installation

1. Set 3 minutes, authenticate, actively read for ten minutes, then background for 2:30:
   return without a prompt. Leave again: another full 3 minutes, not the remaining 30 seconds.
2. Repeat after Android reclaims the background process (or use an external test harness to
   kill only that disposable app process). Cold-open before background expiry: no prompt; no protected
   content should flash before restoration. Do not kill the process from inside its own test.
3. Stay away for at least 3 minutes in one continuous absence: prompt. Repeated lifecycle
   callbacks/background startup must not move the saved departure time. Reading in the
   foreground does not expire the NEXT absence allowance.
4. Repeat while moving date/time backwards and forwards, changing timezone and enabling
   automatic time. These adjustments must neither renew nor prematurely expire the interval.
5. Leave screen off for the interval: sleep counts. Reboot before expiry: prompt regardless.
6. Immediate: every background return still prompts. Change interval or credentials: old
   receipt cannot authorize; security-setting changes still require fresh proof.
7. Check rotation, external intents, private WebView process, biometric-only mode, failed or
   cancelled biometric prompts, stale callbacks and screen-off during authentication.
8. On isolated fixtures only, inject a failed state-file commit / missing Keystore key /
   malformed receipt. Verify denial after another process entry. Never edit production state.

Platform references: https://developer.android.com/reference/android/os/SystemClock and
https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT
