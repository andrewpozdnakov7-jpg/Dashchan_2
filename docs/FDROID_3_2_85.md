# F-Droid 3.2.85 distribution

Material 3, the photo editor, FFmpeg playback, local Mozilla translation, ordinary reply notifications,
local app security and predictive Back are available. Security is configured by the user; predictive Back
remains opt-in. Existing user choices and memory budgets are preserved.

Local security UI and predictive tests are shared through `distribution/features`. The F-Droid manifest
retains the security component factory, biometric permission, authentication activity, recovery activity
and session provider. Google security-provider installation remains disabled.

Video conversion is GitHub-only. Its exporter, codec probes, normalizer, Media3 dependencies, settings,
service and interactive conversion UI are outside the F-Droid source sets. Inert data/UI contracts allow
the shared posting controller to compile; restored settings cannot enable the excluded engine. Ordinary
attachment options and upload limits remain available. Existing original attachments and their accepted
conversion descriptors remain referenced in drafts; F-Droid sends the original through its ordinary path.

Firebase/FCM, Google Translation add-on, Gemini Nano and application self-update remain unavailable.
Mozilla runtime is built from pinned sources using the F-Droid recipe. This document describes source
boundaries, not evidence of a device test or catalog publication.
