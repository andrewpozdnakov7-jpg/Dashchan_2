# Repository Instructions

Before making changes in this repository, read `CODEX.md` completely and
follow the working-mode rules defined there.

The default mode is `SOURCE PATCH ONLY`. Do not run Gradle, compile, build or
install APKs, execute packaging scripts, publish releases, push tags, or upload
artifacts unless the user explicitly switches to the corresponding mode
described in `CODEX.md`.

Never switch modes automatically. Preserve existing user changes and treat
this directory as the active Dashchan_2 checkout.

## Android user interface

Before changes to screens, dialogs, layout, UI resources, animations, or user-facing
copy, read `docs/design/README.md`. Choose the existing component family, preserve
the relevant opt-outs, and run `python tools/design/audit.py check --base HEAD`
for local work (use the merge base for committed work). Report visual checks
separately from static checks. These steps do not change the modes in CODEX.md.
