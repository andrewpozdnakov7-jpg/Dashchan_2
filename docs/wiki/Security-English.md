# How protection works

## Overview

- Enable it in **Settings → Security → Unlock method**: PIN, biometrics, or either.
- **The additional PIN deletes the local profile.** The main PIN cannot recover it.
- Downloads remain unless cleanup of a separately approved folder is enabled.
- This is an application lock, **not database encryption or a guarantee of forensic erasure**.

Protection remains experimental. Test the additional PIN and file deletion only
with disposable data. This guide follows sources based on **3.2.82**; subsequent
prepared fixes still require validation and release. It is not evidence of a
successful device test. Security settings are available in GitHub builds, not F-Droid.

## Unlock methods

- **PIN only:** a main PIN of 6–12 digits.
- **Biometrics only:** strong system biometrics without a backup main PIN. Losing
  the key or biometric capability may make the local profile inaccessible.
- **PIN or biometrics:** either method is sufficient, not two-factor authentication.
  Biometrics open automatically; the main app PIN remains the fallback.

Changing to biometrics only removes the main PIN only after successful new
biometric proof. Cancellation leaves the previous method intact. Every security
settings change requires fresh primary-method approval: the main app PIN in
PIN-capable modes, strong biometrics in biometric-only mode. An unlocked session
or remembered interval is not settings authorization. Main and additional PINs
must differ; wrong attempts are subject to a retained retry delay.

In biometric-only mode the additional PIN is a separately labelled destructive
cleanup action. It is not a fallback to access the existing profile.

## Reauthentication interval

Choose **Immediately** (default), or **3, 5, 10, 30, 60, 120 or 300 minutes**.
The interval measures continuous background absence or screen-off, not time since
the last PIN entry. Foreground reading does not consume it. Return before expiry
and the next departure may start another full interval: with three minutes,
return after 2:30, read, then leave again for a fresh three minutes. A complete
absence of the selected duration requires authentication on return.

A verified receipt preserves the remaining interval across ordinary Android
process loss. Background startup and duplicate departure events cannot extend it.
Failure to record departure may require fresh authentication. Reboot, explicit
revocation, credential/mode/interval changes and profile cleanup invalidate the
authority. Clock/date/timezone changes cannot extend the monotonic interval.
Malformed or unverifiable state never grants access. Device validation is still
required; settings changes always need fresh proof.

The prepared post-3.2.82 fix resets the interval to Immediately when protection
is disabled, so configuring a new lock cannot silently inherit an old 300 minutes.

## Picture-in-picture

A visible **built-in player** PiP window currently counts as an active client,
even when its video is paused. Returning to an ordinary window may therefore
require no extra PIN, including with Immediately selected. This policy is under
separate review and is not changed by the current fixes.

**Hide video in PiP** covers the picture, not the audio or session authority.
Screen-off closes PiP and applies the ordinary lock/interval policy. YouTube PiP
does not receive the built-in player's exception and may close with protection on.

## Notifications

**Hide reply text in the background** is on by default. Reply text is visible in
an unlocked foreground app, hidden in the background or while locked. Disable
this option only with fresh approval; Android's notification/lock-screen settings
also apply. Download, posting and audio notification details remain hidden while
protection is enabled, independently of this reply option. Previously saved system
notification history and captures cannot be recalled.

## Additional PIN and cleanup

Entering the additional PIN removes the old local profile and opens an empty one.
Setting or changing the PIN alone does not start deletion. It is not another account.
Personal history, searches, open pages, own/reply tracking, favorites, custom boards,
drafts/outbox, cookies/credentials, temporary media, translations and diagnostic
records are in the cleanup scope. Appearance, themes, wallpapers, fonts, language
models and security configuration are retained according to the explicit policy.
Public forum posts and external copies remain outside the application's control.

Downloads and exported backups remain by default. **Delete download folder contents**
requires an additional PIN, fresh primary-method approval and an exact displayed
local SAF folder with persistent read/write access. Volume roots and cloud folders
are not accepted. The approved folder's entire contents, including other apps'
files and backups, are deleted; the folder itself and outside files remain.
Changing the download folder or restoring preferences requires fresh deletion
consent. Completion resets folder grants and consent.

Unavailable storage or revoked access may leave files behind. Restore access to
the **same folder** and retry. Continuing without external deletion requires fresh
primary-method approval and leaves an incomplete-cleanup warning. Interrupted
internal cleanup keeps ordinary profile access closed and must resume; external
cleanup is tracked separately and may remain incomplete after the local reset.

## Screenshots and recordings

Capture is blocked by default. Allow screenshots requires fresh approval and an
explicit risk confirmation. It applies to ordinary unlocked content; PIN entry,
confirmation, recovery and locked windows retain protection. The Security settings
list itself is ordinary content and may be captured, including its approved-folder
path. The historical all-window design exception applied only to 3.2.62–3.2.69 and
ended at 3.2.70. Task-preview behavior with capture allowed still needs OEM/device
validation; an independent Recents snapshot policy has not been added yet.

The option also affects recording and casting. It cannot override Android's own
biometric window, device policies or other apps' restrictions. Captures outside
the agreed cleanup scope remain after reset.

## Limits and loss of access

The lock does not protect against a modified/rooted OS or external filming and
does not encrypt existing databases. Cleanup cannot guarantee unrecoverability
or erase copies on forums, cloud services, other applications or outside the
approved folder. Forgotten PIN recovery is unavailable; biometric-only mode has
no backup main PIN. Clearing app data destroys its local profile but does not
prove deletion of downloads or exported backups.

The in-app **Protection limits** item explains these boundaries. For a suspected
vulnerability, follow the project's [private reporting policy](https://github.com/andrewpozdnakov7-jpg/Dashchan_2/blob/master/SECURITY.md).
Do not publish real PINs, passwords, cookies, tokens or personal content in issues.
