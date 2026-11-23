# Shared Material 3 presentation

`build.gradle` includes this directory's Java sources, resources, unit tests and
instrumentation fixtures in both the GitHub and F-Droid variants. Keep one
implementation of the interface, posting form, player and ordinary transitions.
The three disposable Activity fixture hosts under `debug/` are included only
in debug variants; the GitHub mock push receiver remains GitHub debug-only.
The application theme is still owned by `ThemeEngine`; Material dialog themes
are local wrappers.

The existing `ENABLE_EXPERIMENTAL_INTERFACE_MOTION` capability is enabled for
both distributions. The design, posting form and player default to enabled;
explicit saved opt-outs remain effective. The controls are under Settings →
Experimental features → New Material 3 interface and remain searchable.

The launch notice reuses the existing GitHub Material dialog, translated strings
and acknowledgement policy: at most one presentation per process launch, up to
three explicit confirmations. Recreation/backgrounding does not acknowledge it.
Both buttons persist the selected design before recreating the activity. A failed
write keeps the dialog open. The counter is independent of the release version.

Predictive Back is available in both distributions through `ENABLE_PREDICTIVE_BACK`.
`SystemNavigationMode` enables it on supported Android versions when the system
uses gesture navigation. The posting form keeps its own setting for closing in one gesture.
The Material 3 setting controls appearance independently.

Do not move push, Google/Gemini translation implementations or the GMS security provider here.
Local app security and predictive fixtures are shared in `distribution/features`;
the Google-specific sources, dependency gates and manifest exclusions remain separate.
The shared presentation dependency remains the pinned Material library. The F-Droid APK
gate permits Material while continuing to reject Firebase, GMS and ML Kit.

Device acceptance still needs a fresh F-Droid install and update, light/dark
themes, notice confirmation/recreation, design opt-out/re-enable, posting/player
layouts and ordinary Back (button/gesture/IME) with both design choices. Also
test system gesture and button navigation, including their interaction with protected windows.
Compile/test both variants and inspect the final merged F-Droid manifest and
DEX/dependencies before publication. Source checks alone do not establish this.
