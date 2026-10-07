#!/usr/bin/env python3
"""Static source contracts only. No Java compilation, JVM execution or SystemUI simulation."""
import re
import runpy
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GALLERY = ROOT / "src/com/mishiranu/dashchan/ui/gallery"


def method(source, name):
    match = re.search(r"(?:private|public|protected) [\w<>.]+ " + re.escape(name) + r"\(", source)
    assert match, f"Missing method {name}"
    start = source.index("{", match.end()) + 1
    depth, end = 1, start
    while depth:
        assert end < len(source), f"Unclosed method {name}"
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end - 1]


def main():
    balance = runpy.run_path(str(ROOT / "unitTests/check_photo_editor_sources.py"))["lexical_balance"]
    paths = [GALLERY / "PipDiagnosticMode.java", GALLERY / "VideoPipActivity.java",
             ROOT / "src/com/mishiranu/dashchan/content/Preferences.java",
             ROOT / "src/com/mishiranu/dashchan/ui/preference/ExperimentalFragment.java",
             ROOT / "src/com/mishiranu/dashchan/ui/preference/SettingsSearchIndex.java",
             ROOT / "unitTests/src/com/mishiranu/dashchan/ui/gallery/PipDiagnosticModeTest.java",
             ROOT / "unitTests/src/com/mishiranu/dashchan/ui/gallery/PipParameterContractTest.java"]
    for path in paths: balance(path)
    activity = (GALLERY / "VideoPipActivity.java").read_text(encoding="utf-8")
    policy = (GALLERY / "PipDiagnosticMode.java").read_text(encoding="utf-8")
    preferences = paths[2].read_text(encoding="utf-8")
    experimental = paths[3].read_text(encoding="utf-8")
    search = paths[4].read_text(encoding="utf-8")
    assert 'DEFAULT_PIP_DIAGNOSTIC_MODE = "normal"' in preferences
    assert 'NORMAL("normal", -1)' in policy and 'NO_HINT("no_hint", 0)' in policy
    assert 'ENTRY_HINT("entry_hint", 1)' in policy and 'LAYOUT_HINT("layout_hint", 2)' in policy
    assert 'NO_SEAMLESS_RESIZE("no_seamless_resize", 3)' in policy
    assert "return this == NORMAL || this == LAYOUT_HINT && inPictureInPicture;" in policy
    assert "return this != NO_HINT && this != NO_SEAMLESS_RESIZE;" in policy and "import android." not in policy
    assert "return this != NO_SEAMLESS_RESIZE;" in method(policy, "isSeamlessResizeEnabled")
    create = method(activity, "createPictureInPictureParams")
    assert "builder.setSeamlessResizeEnabled(pictureInPictureDiagnosticMode.isSeamlessResizeEnabled())" in create
    assert create.index("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S") < create.index("setSeamlessResizeEnabled(")
    assert activity.count("Preferences.getPipDiagnosticMode()") == 1
    assert "Preferences.getPipDiagnosticMode()" in method(activity, "onCreate")
    assert activity.count("setPictureInPictureParams(") == 1
    publish = method(activity, "publishPictureInPictureParams")
    assert publish.index("allowsParameterUpdate(") < publish.index("setPictureInPictureParams(")
    assert publish.index("allowsParameterUpdate(") < publish.index("setSeamlessResizeEnabled(")
    assert "seamlessResizeEnabled != lastPublishedSeamlessResizeEnabled" in publish
    assert publish.index("setPictureInPictureParams(") < publish.index("lastPublishedSeamlessResizeEnabled =")
    assert "lastPublishedSeamlessResizeEnabled = pictureInPictureDiagnosticMode.isSeamlessResizeEnabled()" in method(activity, "enterPictureInPicture")
    assert '!pictureInPictureDiagnosticMode.isSeamlessResizeEnabled()) pictureInPictureDiagnosticTrialValid = false' in method(activity, "onCreate")
    flag = method(activity, "getPictureInPictureSeamlessResizeDiagnosticValue")
    assert "Build.VERSION.SDK_INT >= Build.VERSION_CODES.S" in flag and '"unsupported"' in flag
    for name in ["recordPictureInPictureParams", "recordPictureInPictureProbe"]:
        assert "seamless_resize_requested=" in method(activity, name)
    for name in ["schedulePictureInPictureGeometryUpdate", "publishPictureInPictureGeometryBeforeDraw"]:
        assert "allowsParameterUpdate(isInPictureInPictureMode())" in method(activity, name)
    assert "allowsHintAtEntry() ? getPictureInPictureSourceRect() : null" in method(activity, "enterPictureInPicture")
    layout = method(activity, "updateDiagnosticPictureInPictureGeometry")
    assert "sourceRect.equals(lastSourceRectHint)" in layout and "getPictureInPictureSourceRect()" in layout
    assert "setActions(" not in layout and "setAspectRatio(" not in layout
    assert "player.setPlaying(playing)" in method(activity, "togglePlayback")
    assert "player.setPosition(position)" in method(activity, "seekBy")
    controls = method(activity, "updatePictureInPictureControls")
    assert controls.index("rootView.setKeepScreenOn(playing)") < controls.index("isDiagnostic()")
    geometry = method(activity, "updatePictureInPictureGeometry")
    assert "if (aspectChanged) builder.setAspectRatio(aspectRatio)" in geometry
    assert "if (controlsChanged) builder.setActions(" in geometry
    for name in ["endPictureInPictureDiagnosticTrial", "recordPictureInPictureProbe"]:
        body = method(activity, name)
        for forbidden in ["setRequestedOrientation", "startActivity(", "setPlaying(", "releaseVideoView",
                          "enterPictureInPictureMode(", "setAspectRatio(", "setActions("]:
            assert forbidden not in body, f"Unexpected side effect in {name}: {forbidden}"
    assert "PipDiagnosticMode.NORMAL" in method(activity, "endPictureInPictureDiagnosticTrial")
    assert "endPictureInPictureDiagnosticTrial" in method(activity, "replacePictureInPictureContent")
    assert 'Arrays.asList("normal", "no_hint", "entry_hint", "layout_hint", "no_seamless_resize")' in experimental
    assert "Preferences.KEY_PIP_DIAGNOSTIC_MODE" in search
    refs = set(re.findall(r"R.string.(pip_diagnostic_\w+)", activity + experimental + search))
    for locale in ["values", "values-ru"]:
        strings = ET.parse(ROOT / "lang" / locale / "pip_diagnostics_strings.xml").getroot()
        names = [node.attrib["name"] for node in strings]
        assert len(names) == len(set(names)) and refs <= set(names), f"Missing/duplicate {locale} strings"
    print("PASS: PiP lexical balance, default-off/frozen selection, final publication guard, hint-only mode 2")
    print("PASS: settings/search/localization, live playback controls, normal branch and invalidation safeguards")
    print("PASS: mode 3 isolates seamless resize from mode 0, API guard, flag logging and normal flag restoration")
    print("NOT RUN: compilation, JVM/Android tests, APK, device/SystemUI rotation")


if __name__ == "__main__":
    main()
