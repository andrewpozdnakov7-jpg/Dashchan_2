#!/usr/bin/env python3
"""Source-set isolation only, not Gradle configuration or a final manifest/DEX/APK audit."""
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
    common_java = [*sorted((ROOT / "src").rglob("*.java")), *sorted((FDROID / "src").rglob("*.java"))]
    for path in common_java:
        source = text(path)
        assert "ExperimentalImageEditorActivity" not in source, f"Editor dependency in F-Droid: {path.relative_to(ROOT)}"
        assert "posting.photo." not in source, f"Engine dependency in F-Droid: {path.relative_to(ROOT)}"
    assert not (ROOT / POSTING / "ExperimentalImageEditorActivity.java").exists()
    assert not list((ROOT / POSTING / "photo").glob("*.java"))
    engine = list((GITHUB / POSTING / "photo").glob("*.java"))
    assert len(engine) == 15 and (GITHUB / POSTING / "ExperimentalImageEditorActivity.java").is_file()
    assert {"EditorPalette.java", "EditorColorState.java", "EditorColorPickerView.java"} <= {p.name for p in engine}
    shared = text(ROOT / POSTING / "ImageEditorActivity.java")
    assert "PhotoEditorBridge.createIntent(" in shared and "if (experimental != null) return experimental" in shared
    assert "new Intent(context, ImageEditorActivity.class)" in shared
    gh_bridge = text(GITHUB / POSTING / "PhotoEditorBridge.java")
    fd_bridge = text(FDROID / POSTING / "PhotoEditorBridge.java")
    assert "return true;" in gh_bridge and "Preferences.isNewPhotoEditorEnabled()" in gh_bridge
    assert "ExperimentalImageEditorActivity.createIntent(" in gh_bridge
    assert "return false;" in fd_bridge and "return null;" in fd_bridge and fd_bridge.count("return 0;") == 2
    assert "Preferences." not in fd_bridge and "R." not in fd_bridge
    assert "PhotoEditorBridge.isAvailable() && PREFERENCES.getBoolean(KEY_NEW_PHOTO_EDITOR, DEFAULT_NEW_PHOTO_EDITOR)" in text(ROOT / "src/com/mishiranu/dashchan/content/Preferences.java")
    for name in ("ExperimentalFragment.java", "SettingsSearchIndex.java"):
        source = text(ROOT / "src/com/mishiranu/dashchan/ui/preference" / name)
        block = source[source.index("if (PhotoEditorBridge.isAvailable())"):]
        block = block[:block.index("\n\t\t}")]
        assert "Preferences.KEY_NEW_PHOTO_EDITOR" in block
        assert "PhotoEditorBridge.getTitleResId()" in block and "PhotoEditorBridge.getSummaryResId()" in block
        assert "R.string.new_photo_editor" not in source
    android = "{http://schemas.android.com/apk/res/android}"
    main_manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
    gh_manifest = ET.parse(GITHUB / "AndroidManifest.xml").getroot()
    assert not any("ExperimentalImageEditorActivity" in n.attrib.get(android + "name", "") for n in main_manifest.iter())
    activities = [n for n in gh_manifest.iter("activity") if n.attrib.get(android + "name") == ".ui.posting.ExperimentalImageEditorActivity"]
    assert len(activities) == 1 and activities[0].attrib[android + "exported"] == "false"
    # Exclusive symbols must not exist in shared/F-Droid Java or the shared resource roots.
    symbols = {"PhotoEditorDialogTheme"}
    for locale in ("values", "values-ru"):
        resources = ET.parse(GITHUB / "res" / locale / "photo_editor_strings.xml").getroot()
        names = {n.attrib["name"] for n in resources}
        assert all(n.startswith("pe_") or n.startswith("new_photo_editor") for n in names)
        symbols.update(names)
        assert not (ROOT / "lang" / locale / "photo_editor_strings.xml").exists()
    for path in (GITHUB / "res/drawable").glob("*_pe_*.xml"):
        ET.parse(path)
        symbols.add(path.stem)
        assert not (ROOT / "res/drawable" / path.name).exists()
    assert not (ROOT / "res/values/photo_editor_styles.xml").exists()
    ET.parse(GITHUB / "res/values/photo_editor_styles.xml")
    pattern = re.compile(r"(?:R\.(?:string|drawable|style)\.|@(?:string|drawable|style)/|name=\")(" + "|".join(map(re.escape, symbols)) + r")(?![\w.])")
    for path in [*common_java, *sorted((ROOT / "res").rglob("*.xml")), *sorted((ROOT / "lang").rglob("*.xml"))]:
        assert not pattern.search(text(path)), f"Exclusive resource in F-Droid: {path.relative_to(ROOT)}"
    gradle = text(ROOT / "build.gradle")
    for line in ("sourceSets.github.java.srcDir 'distribution/github/src'", "sourceSets.github.res.srcDir 'distribution/github/res'",
                 "sourceSets.github.manifest.srcFile 'distribution/github/AndroidManifest.xml'", "sourceSets.fdroid.java.srcDir 'distribution/fdroid/src'",
                 "sourceSets.maybeCreate('testGithub').java.srcDir 'distribution/github/unitTests/src'",
                 "sourceSets.maybeCreate('androidTestGithub').java.srcDir 'distribution/github/instrumentationTests/src'",
                 "sourceSets.maybeCreate('testFdroid').java.srcDir 'distribution/fdroid/unitTests/src'"):
        assert line in gradle, f"Missing source-set wiring: {line}"
    assert "java.srcDirs = ['src']" in gradle and "res.srcDirs = ['res', 'lang']" in gradle
    for path in [*sorted((ROOT / "unitTests/src").rglob("*.java")), *sorted((ROOT / "instrumentationTests/src").rglob("*.java"))]:
        assert not re.search(r"import com\.mishiranu\.dashchan\.ui\.posting\.(?:photo\.|ExperimentalImageEditorActivity)", text(path)), path
    for path in (GITHUB / "instrumentationTests/src/com/mishiranu/dashchan/PhotoEditorEngineTest.java",
                 GITHUB / "unitTests/src/com/mishiranu/dashchan/ui/posting/EditorPreviewSchedulerTest.java",
                 GITHUB / "unitTests/src/com/mishiranu/dashchan/ui/posting/PhotoEditorSessionFilesTest.java",
                 FDROID / "unitTests/src/com/mishiranu/dashchan/ui/posting/PhotoEditorBridgeTest.java",
                 ROOT / "instrumentationTests/src/com/mishiranu/dashchan/PhotoEditorDistributionSmokeTest.java",
                 GITHUB / POSTING / "PhotoEditorBridge.java", FDROID / POSTING / "PhotoEditorBridge.java",
                 ROOT / POSTING / "ImageEditorActivity.java"):
        balance(path)
    print("PASS: 16 editor/engine classes, exclusive resources and Activity are GitHub-only")
    print("PASS: no F-Droid dependency; restored opt-in gated; settings/search availability guard")
    print("PASS: flavour-specific JVM/Android tests wired; shared tests have no excluded class imports")
    print("NOT RUN: Gradle configuration, compilation, JUnit, instrumentation, final manifest/DEX/APK audit")


if __name__ == "__main__":
    main()
