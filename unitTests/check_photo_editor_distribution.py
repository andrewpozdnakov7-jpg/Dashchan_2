#!/usr/bin/env python3
"""Shared editor wiring only; does not compile Java or inspect a final merged APK."""
import re
import runpy
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POSTING = "src/com/mishiranu/dashchan/ui/posting"
GITHUB = ROOT / "distribution/github"
FDROID = ROOT / "distribution/fdroid"


def text(path):
    return path.read_text(encoding="utf-8")


def main():
    balance = runpy.run_path(str(ROOT / "unitTests/check_photo_editor_sources.py"))["lexical_balance"]
    engine = list((ROOT / POSTING / "photo").glob("*.java"))
    activity = ROOT / POSTING / "ExperimentalImageEditorActivity.java"
    bridge = ROOT / POSTING / "PhotoEditorBridge.java"
    required = {"EditorDocument.java", "EditorRenderer.java", "EditorCanvasView.java",
                "EditorGeometry.java", "EditorPalette.java", "EditorColorState.java", "EditorColorPickerView.java"}
    assert required <= {p.name for p in engine}, "Incomplete shared photo engine"
    for path in [activity, bridge, *engine]:
        balance(path)
        assert not re.search(r"import com\.google\.", text(path)), path
    for variant in (GITHUB, FDROID):
        assert not (variant / POSTING / "PhotoEditorBridge.java").exists(), "Duplicate flavour routing"
        assert not (variant / POSTING / "ExperimentalImageEditorActivity.java").exists(), "Duplicate editor Activity"
        assert not list((variant / POSTING / "photo").glob("*.java")), "Duplicate engine"
    routing = text(bridge)
    assert "return true;" in routing and "Preferences.isNewPhotoEditorEnabled()" in routing
    assert "ExperimentalImageEditorActivity.createIntent(" in routing
    legacy = text(ROOT / POSTING / "ImageEditorActivity.java")
    assert "PhotoEditorBridge.createIntent(" in legacy and "if (experimental != null) return experimental" in legacy
    assert "new Intent(context, ImageEditorActivity.class)" in legacy
    preferences = text(ROOT / "src/com/mishiranu/dashchan/content/Preferences.java")
    assert "DEFAULT_NEW_PHOTO_EDITOR = true" in preferences
    assert "getBoolean(KEY_NEW_PHOTO_EDITOR, DEFAULT_NEW_PHOTO_EDITOR)" in preferences
    android = "{http://schemas.android.com/apk/res/android}"
    main_manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
    activities = [n for n in main_manifest.iter("activity")
                  if n.attrib.get(android + "name") == ".ui.posting.ExperimentalImageEditorActivity"]
    assert len(activities) == 1 and activities[0].attrib[android + "exported"] == "false"
    for variant in (GITHUB, FDROID):
        manifest = ET.parse(variant / "AndroidManifest.xml").getroot()
        assert not any("ExperimentalImageEditorActivity" in n.attrib.get(android + "name", "") for n in manifest.iter())
    for locale in ("values", "values-ru"):
        resource = ROOT / "lang" / locale / "photo_editor_strings.xml"
        names = {n.attrib["name"] for n in ET.parse(resource).getroot()}
        assert {"new_photo_editor", "new_photo_editor__summary", "pe_save"} <= names
        for variant in (GITHUB, FDROID):
            assert not (variant / "res" / locale / resource.name).exists(), "Duplicate flavour strings"
    ET.parse(ROOT / "res/values/photo_editor_styles.xml")
    for path in (ROOT / "res/drawable").glob("*_pe_*.xml"):
        ET.parse(path)
        assert not (GITHUB / "res/drawable" / path.name).exists()
    for path in (ROOT / "unitTests/src/com/mishiranu/dashchan/ui/posting").rglob("*Test.java"):
        if path.name.startswith(("Editor", "PhotoEditor")):
            balance(path)
    for path in (ROOT / "instrumentationTests/src/com/mishiranu/dashchan").glob("PhotoEditor*Test.java"):
        balance(path)
    assert (ROOT / "unitTests/src/com/mishiranu/dashchan/ui/posting/PhotoEditorBridgeTest.java").is_file()
    assert not (FDROID / "unitTests/src/com/mishiranu/dashchan/ui/posting/PhotoEditorBridgeTest.java").exists()
    gradle = text(ROOT / "build.gradle")
    assert "java.srcDirs = ['src']" in gradle and "res.srcDirs = ['res', 'lang']" in gradle
    assert "java.srcDirs = ['unitTests/src']" in gradle
    assert "sourceSets.androidTest.java.srcDirs = ['instrumentationTests/src']" in gradle
    fdroid = gradle[gradle.index("\n\t\tfdroid {"):gradle.index("\n\tsourceSets.main {")]
    for feature in ("ENABLE_APP_SECURITY_SETTINGS", "ENABLE_EXPERIMENTAL_INTERFACE_MOTION",
                    "ENABLE_GOOGLE_TRANSLATION", "ENABLE_GEMINI_NANO_TRANSLATION"):
        assert f"buildConfigField 'boolean', '{feature}', 'false'" in fdroid
    print(f"PASS: {len(engine) + 1} editor/engine classes, Activity, resources and tests are shared")
    print("PASS: enabled default with existing explicit opt-out; no duplicate flavour routing")
    print("PASS: unrelated F-Droid experimental gates remain disabled; no Google editor imports")
    print("NOT RUN: Gradle configuration, compilation, JUnit, instrumentation, final manifest/DEX/APK checks")


if __name__ == "__main__":
    main()
