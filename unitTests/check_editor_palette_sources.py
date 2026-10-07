#!/usr/bin/env python3
"""Regression contracts for source review only; does not execute Java or render Android UI."""
import runpy
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'distribution/github/src/com/mishiranu/dashchan/ui/posting'


def main():
    balance = runpy.run_path(str(ROOT / 'unitTests/check_photo_editor_sources.py'))['lexical_balance']
    activity = (JAVA / 'ExperimentalImageEditorActivity.java').read_text(encoding='utf-8')
    picker = (JAVA / 'photo/EditorColorPickerView.java').read_text(encoding='utf-8')
    state = (JAVA / 'photo/EditorColorState.java').read_text(encoding='utf-8')
    motion = (JAVA / 'photo/EditorPanelMotion.java').read_text(encoding='utf-8')
    theme = (ROOT / 'src/com/mishiranu/dashchan/widget/ThemeEngine.java').read_text(encoding='utf-8')
    for path in (JAVA / 'photo').glob('*.java'):
        balance(path)
    balance(ROOT / 'distribution/github/unitTests/src/com/mishiranu/dashchan/ui/posting/EditorColorStateTest.java')
    attrs = ET.parse(ROOT / 'res/values/attrs.xml').getroot()
    assert any(n.attrib.get('name') == 'themeEnginePreserveColors' and n.attrib.get('format') == 'boolean'
               for n in attrs.iter('attr'))
    styles = ET.parse(ROOT / 'distribution/github/res/values/photo_editor_styles.xml').getroot()
    assert any(n.attrib.get('name') == 'themeEnginePreserveColors' and n.text == 'true'
               for n in styles.iter('item'))
    assert 'ThemeContextAppearance_themeEnginePreserveColors, false' in theme
    assert '!preserveColors &&' in theme
    assert 'button.setBackgroundTintList(null)' in motion and 'button.setCompoundDrawableTintList(null)' in motion
    assert '-android.R.attr.state_enabled' in motion
    for hint in ('pe_brush_hint', 'pe_object_hint'):
        assert hint not in activity
    controls = activity[activity.index('private void drawingParameters('):activity.index('private void updateBrushSwatch(')]
    assert 'EditorDocument.Kind.ERASER' in controls and 'EditorDocument.Kind.MOSAIC' in controls and 'EditorDocument.Kind.BLUR' in controls
    assert 'kind != EditorDocument.Kind.COVER' in controls and 'value / 1000f' in controls
    assert 'buildPanel(' not in controls
    text_modal = activity[activity.index('private void editText('):activity.index('private void placeAtCropCenter(')]
    assert 'selectedColor =' not in text_modal and 'textColor[0] = color' in text_modal
    assert 'modal.addView(activePicker[0])' in text_modal and 'chooseColor(' not in text_modal
    for contract in ('input.setSelection(Math.min(cursor[0]', 'setOnKeyListener(', 'registerDialogBack(',
                     'Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU', 'unregisterBack[0].run()',
                     'WindowInsets.Type.ime()', 'item.background = bg.isChecked(); item.outline = outline.isChecked();'):
        assert contract in text_modal
    modes = picker[picker.index('private void setCircle('):picker.index('private void refresh(')]
    assert 'state.set' not in modes and 'EditorMotion.enabled()' in modes
    assert 'getVisibility() == VISIBLE && !applyHex()' in picker and 'closed = true' in picker
    assert picker.count('putString("recent-colors"') == 1 and picker.count('accept.accept(') == 1
    assert 'EditorDocument' not in picker and 'document.' not in picker
    assert 'COLUMNS = 12, ROWS = 10' in state and 'if (delta > 0)' in state
    assert '#[0-9a-fA-F]{6}' in state and 'result.size() < 8' in state
    assert 'setDuration(150)' in picker and 'ACTION_SET_PROGRESS' in picker
    print('PASS: opt-in theme color preservation, contrast and contextual controls')
    print('PASS: local RGB/HSV draft, grid/wheel, strict HEX, bounded confirmed recents')
    print('PASS: one text dialog, separate color, cursor/flags/IME and guarded Back integration')
    print('NOT RUN: Java/JUnit/instrumentation, attached dialogs, visual rendering and APK')


if __name__ == '__main__':
    main()
