#!/usr/bin/env python3
"""Source-only integration checks. Does not compile Java, run Gradle or claim runtime correctness."""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / "src/com/mishiranu/dashchan/ui/posting"
JAVA = ROOT / "distribution/github/src/com/mishiranu/dashchan/ui/posting"


def lexical_balance(path):
    text = path.read_text(encoding="utf-8")
    stack, index = [], 0
    pairs = {")": "(", "]": "[", "}": "{"}
    while index < len(text):
        char = text[index]
        if text.startswith("//", index):
            end = text.find("\n", index); index = len(text) if end < 0 else end; continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            assert end >= 0, f"Unclosed comment: {path}"
            index = end + 2; continue
        if char in "\"'":
            quote = char; index += 1
            while index < len(text):
                if text[index] == "\\": index += 2; continue
                if text[index] == quote: index += 1; break
                assert text[index] not in "\r\n", f"Unclosed literal: {path}"
                index += 1
            else: raise AssertionError(f"Unclosed literal: {path}")
            continue
        if char in "([{": stack.append(char)
        elif char in pairs: assert stack and stack.pop() == pairs[char], f"Unbalanced delimiter: {path}"
        index += 1
    assert not stack, f"Unclosed delimiter: {path}"


def main():
    files = [JAVA / "ExperimentalImageEditorActivity.java", *sorted((JAVA / "photo").glob("*.java")),
             *sorted((ROOT / "distribution/github/instrumentationTests/src/com/mishiranu/dashchan").glob("PhotoEditor*Test.java"))]
    for path in files: lexical_balance(path)
    texts = "\n".join(path.read_text(encoding="utf-8") for path in files)
    default = ET.Element("resources"); russian = ET.Element("resources")
    for directory, tree in (("values", default), ("values-ru", russian)):
        for base in (ROOT / "lang", ROOT / "distribution/github/res"):
            for path in (base / directory).glob("*.xml"): tree.extend(ET.parse(path).getroot())
    en = {node.attrib["name"]: node.text or "" for node in default if node.tag == "string"}
    ru = {node.attrib["name"]: node.text or "" for node in russian if node.tag == "string"}
    for tree in (default, russian):
        names = [node.attrib.get("name") for node in tree if node.attrib.get("name", "").startswith("pe_")]
        assert len(names) == len(set(names)), "Duplicate photo editor strings"
    required = set(re.findall(r"(?<!android\.)R\.string\.(pe_\w+|image_editor\w*|new_photo_editor\w*)", texts))
    assert required <= en.keys() and required <= ru.keys(), f"Missing strings: {required - (en.keys() & ru.keys())}"
    formats = re.compile(r"%\d+\$[.\d]*[dfs]")
    for key in required: assert formats.findall(en[key]) == formats.findall(ru[key]), f"Format mismatch: {key}"
    drawables = set(re.findall(r"R\.drawable\.(\w+)", texts))
    for name in drawables:
        path = ROOT / "res/drawable" / (name + ".xml")
        if not path.is_file(): path = ROOT / "distribution/github/res/drawable" / (name + ".xml")
        assert path.is_file(), f"Missing drawable: {name}"
        ET.parse(path)
    activity = files[0].read_text(encoding="utf-8")
    assert "new AlertDialog.Builder(this, R.style.PhotoEditorDialogTheme)" in activity
    assert "input.setSingleLine(false)" in activity and "input.setMinLines(3)" in activity
    assert "item.color = textColor[0]" in activity and "input.getText().toString().trim()" not in activity
    build = activity[activity.index("private void buildPanel()"):activity.index("private void mutate(")]
    assert "categories.setVisibility(View.VISIBLE)" in build and "categories.setVisibility(crop" not in build
    panels = activity[activity.index("private java.util.List<View> visiblePanels()"):activity.index("private void buildPanel()")]
    assert "toolScroll, cropToolbar, cropScale" in panels and "toolScroll, categories" not in panels
    crop = activity[activity.index("private void cropPanel()"):activity.index("private String ratioLabel()")]
    assert "canvas.setEditorInsets(cropToolbar.getHeight() + dp(8), cropScale.getHeight() + dp(8))" in crop
    assert "categories.getHeight()" not in crop and activity.count("categories = new LinearLayout") == 1
    switch = activity[activity.index("private void openSection("):activity.index("private void closeSection(")]
    assert switch.index("target == section") < switch.index("canvas.finishGesture(true)") < switch.index("document.toJson()")
    assert "checkpoint = nextCheckpoint; section = target; buildPanel()" in switch
    for forbidden in ("closeSection(", "section = Section.HOME", "document.begin()", "document.commit()"):
        assert forbidden not in switch, f"Unexpected section-switch side effect: {forbidden}"
    lexical_balance(ROOT / "unitTests/src/com/mishiranu/dashchan/ui/posting/PhotoEditorIntegrationContractTest.java")
    canvas = (JAVA / "photo/EditorCanvasView.java").read_text(encoding="utf-8")
    assert "previewScheduler.start(liveMaskGesture || directInput)" in canvas
    assert "processedIsScene = scene" in canvas and "if (processedIsScene) return" in canvas
    assert "EditorRenderer.prepareScene(source, state, assetSnapshot" in canvas
    assert "this::saveDirect" in activity and "prepareSave(); encode(true, null)" in activity
    export_options = activity[activity.index("private void showExport()"):activity.index("private void encode(")]
    assert "closeSection(" not in export_options and "encode(" not in export_options
    assert "int[] side = {exportSide}, quality = {exportQuality}; boolean[] png = {exportPng}" in export_options
    assert "exportSide = side[0]; exportQuality = quality[0]; exportPng = png[0]" in export_options
    assert "EditorStickers.GROUPS[stickerGroup]" in activity and "new EditorStickers.Tile(" in activity
    render = canvas[canvas.index("public void refresh()"):canvas.index("private static String photoKey(")]
    assert "handler.removeCallbacks(renderRequest)" not in render
    lexical_balance(ROOT / "distribution/github/unitTests/src/com/mishiranu/dashchan/ui/posting/EditorPreviewSchedulerTest.java")
    contract = {"EXTRA_SOURCE_HASH": "sourceHash", "EXTRA_SOURCE_NAME": "sourceName",
                "EXTRA_ATTACHMENT_INDEX": "attachmentIndex", "EXTRA_RESULT_HASH": "resultHash",
                "EXTRA_RESULT_NAME": "resultName", "EXTRA_RESULT_ATTACHMENT_INDEX": "resultAttachmentIndex",
                "EXTRA_RESULT_SOURCE_HASH": "resultSourceHash", "EXTRA_RESULT_SOURCE_NAME": "resultSourceName"}
    for constant, value in contract.items():
        assert re.search(r"\b" + constant + r'\s*=\s*"' + value + '"', activity), f"Changed contract: {constant}"
    assert "static Intent createIntent(Context context, String sourceHash, String sourceName, int attachmentIndex)" in activity
    posting_fragment = (COMMON / "PostingFragment.java").read_text(encoding="utf-8")
    assert "attachmentController.handleActivityResult(requestCode, resultCode, data)" in posting_fragment
    posting = (COMMON / "PostingAttachmentsController.java").read_text(encoding="utf-8")
    assert "AttachmentResultGuard.matches(" in posting, "Missing stale attachment guard"
    legacy = (COMMON / "ImageEditorActivity.java").read_text(encoding="utf-8")
    assert "PhotoEditorBridge.createIntent(" in legacy and "if (experimental != null) return experimental" in legacy
    bridge = (JAVA / "PhotoEditorBridge.java").read_text(encoding="utf-8")
    assert "Preferences.isNewPhotoEditorEnabled()" in bridge and "ExperimentalImageEditorActivity.createIntent(" in bridge
    assert "new Intent(context, ImageEditorActivity.class)" in legacy
    print(f"PASS: {len(files)} Java files have balanced delimiters and literals")
    print(f"PASS: {len(required)} localized strings and format placeholders")
    print(f"PASS: {len(drawables)} referenced drawable resources parse as XML")
    print("PASS: input/result extras and stale attachment guard")
    print("PASS: persistent crop navigation, no shared-panel crossfade or doubled insets, direct switch/checkpoint")
    print("NOT RUN: Java compilation, Android tests, APK, native UI rendering")


if __name__ == "__main__":
    try: main()
    except (AssertionError, OSError, ET.ParseError) as error:
        print(f"FAIL: {error}", file=sys.stderr); sys.exit(1)
