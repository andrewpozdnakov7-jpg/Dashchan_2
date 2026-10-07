package com.mishiranu.dashchan.ui.posting;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Source guards; real Bitmap/Intent behavior is covered by Android smoke tests. */
public class PhotoEditorIntegrationContractTest {
    private static String source(String relative) throws Exception {
        Path root = Path.of(".").toAbsolutePath();
        while (root != null) {
            Path file = root.resolve(relative);
            if (Files.isRegularFile(file)) return Files.readString(file, StandardCharsets.UTF_8);
            root = root.getParent();
        }
        throw new AssertionError("Missing source: " + relative);
    }

    @Test public void editorIsOptInAndSearchable() throws Exception {
        String preferences = source("src/com/mishiranu/dashchan/content/Preferences.java");
        assertTrue(preferences.contains("DEFAULT_NEW_PHOTO_EDITOR = false"));
        assertTrue(preferences.contains("getBoolean(KEY_NEW_PHOTO_EDITOR, DEFAULT_NEW_PHOTO_EDITOR)"));
        assertTrue(source("src/com/mishiranu/dashchan/ui/preference/ExperimentalFragment.java")
                .contains("addCheck(true, Preferences.KEY_NEW_PHOTO_EDITOR, Preferences.DEFAULT_NEW_PHOTO_EDITOR"));
        assertTrue(source("src/com/mishiranu/dashchan/ui/preference/SettingsSearchIndex.java")
                .contains("R.string.new_photo_editor__summary, Preferences.KEY_NEW_PHOTO_EDITOR"));
    }

    @Test public void legacyEditorAndPostingGuardRemain() throws Exception {
        String legacy = source("src/com/mishiranu/dashchan/ui/posting/ImageEditorActivity.java");
        assertTrue(legacy.contains("if (Preferences.isNewPhotoEditorEnabled())"));
        assertTrue(legacy.contains("ExperimentalImageEditorActivity.createIntent("));
        assertTrue(legacy.contains("new Intent(context, ImageEditorActivity.class)"));
        assertTrue(source("src/com/mishiranu/dashchan/ui/posting/PostingFragment.java")
                .contains("AttachmentResultGuard.matches("));
        String manifest = source("AndroidManifest.xml");
        assertTrue(manifest.contains(".ui.posting.ImageEditorActivity"));
        assertTrue(manifest.contains(".ui.posting.ExperimentalImageEditorActivity"));
    }

    @Test public void sessionIoUsesDetachedWorkerSnapshot() throws Exception {
        String activity = source("src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        String persist = activity.substring(activity.indexOf("private void persist()"),
                activity.indexOf("@Override protected void onSaveInstanceState"));
        assertTrue(persist.contains("snapshot = document.copy()"));
        assertTrue(persist.indexOf("worker.execute(") < persist.indexOf("file.startWrite()"));
        assertTrue(persist.contains("request != sessionGeneration"));
        assertFalse(persist.contains("document.toJson()"));
    }

    @Test public void cropGesturesUseExportGeometryAndRulersReadTheModel() throws Exception {
        String canvas = source("src/com/mishiranu/dashchan/ui/posting/photo/EditorCanvasView.java");
        assertTrue(canvas.contains("EditorGeometry.pinchCrop(document.state(), cropStart"));
        assertTrue(canvas.contains("EditorGeometry.resizeCrop(state, cropStart"));
        assertTrue(canvas.contains("event.findPointerIndex(pinchPointerId0)"));
        String activity = source("src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        assertTrue(activity.contains("() -> document.state().straighten"));
        assertTrue(activity.contains("control.syncValue(current.get())"));
        assertTrue(activity.contains("for (Runnable binding : rulerBindings) binding.run()"));
        String ruler = source("src/com/mishiranu/dashchan/ui/posting/photo/EditorRulerView.java");
        String sync = ruler.substring(ruler.indexOf("public void syncValue("), ruler.indexOf("private void setValue("));
        assertFalse(sync.contains("listener.onValue("));
    }

    @Test public void editorControlsRemainReadableAndTextErrorsKeepTheDialogOpen() throws Exception {
        String activity = source("src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        assertTrue(activity.contains("highlight(option, exportSide == side)"));
        assertFalse(activity.contains("setTextColor(selectedColor)"));
        assertFalse(activity.contains("ORIENTATION_LANDSCAPE ? View.GONE"));
        assertTrue(activity.contains("view.setBackgroundTintList(null)"));
        String edit = activity.substring(activity.indexOf("private void editText("), activity.indexOf("private void placeAtCropCenter("));
        assertTrue(edit.contains(".setPositiveButton(android.R.string.ok, null).create()"));
        assertTrue(edit.contains("input.setError(getString(R.string.pe_limit))"));
        assertTrue(edit.indexOf("input.setError(") < edit.indexOf("dialog.dismiss()"));
        assertTrue(edit.contains("scroll.addView(layout)"));
    }

    @Test public void presentationDoesNotBecomePartOfTheDocumentOrExportRecipe() throws Exception {
        String document = source("src/com/mishiranu/dashchan/ui/posting/photo/EditorDocument.java");
        assertFalse(document.contains("EditorPose")); assertFalse(document.contains("ValueAnimator"));
        String renderer = source("src/com/mishiranu/dashchan/ui/posting/photo/EditorRenderer.java");
        assertFalse(renderer.contains("EditorPose")); assertFalse(renderer.contains("ValueAnimator"));
        String transition = source("src/com/mishiranu/dashchan/ui/posting/photo/EditorPoseTransition.java");
        assertFalse(transition.contains("EditorDocument")); assertFalse(transition.contains("toJson("));
        String activity = source("src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        String filters = activity.substring(activity.indexOf("private void filterPanel()"), activity.indexOf("private void drawingPanel("));
        assertFalse(filters.contains("buildPanel()"));
        String ruler = source("src/com/mishiranu/dashchan/ui/posting/photo/EditorRulerView.java");
        assertTrue(ruler.contains("new AccessibilityNodeInfo.RangeInfo("));
        assertFalse(ruler.contains("RangeInfo.obtain("));
    }

    @Test public void phoneDialogsAndLiveMaskPreviewKeepTheirContracts() throws Exception {
        String activity = source("src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        assertTrue(activity.contains("new AlertDialog.Builder(this, R.style.PhotoEditorDialogTheme)"));
        assertTrue(activity.contains("Context context = builder.getContext()"));
        assertTrue(activity.contains("input.setSingleLine(false)")); assertTrue(activity.contains("input.setMinLines(3)"));
        assertTrue(activity.contains("InputType.TYPE_TEXT_FLAG_MULTI_LINE"));
        assertTrue(activity.contains("input.setHorizontallyScrolling(false)"));
        assertTrue(activity.contains("item.color = textColor[0]"));
        assertTrue(activity.contains("String text = input.getText().toString();"));
        String canvas = source("src/com/mishiranu/dashchan/ui/posting/photo/EditorCanvasView.java");
        String render = canvas.substring(canvas.indexOf("public void refresh()"), canvas.indexOf("private static String photoKey("));
        assertFalse(render.contains("removeCallbacks(renderRequest)"));
        assertTrue(canvas.contains("previewScheduler.cancelled(request)"));
        assertTrue(canvas.contains("previewScheduler.complete(request)"));
        assertTrue(canvas.contains("if (wasLive) previewScheduler.change(commit)"));
        assertTrue(canvas.contains("boolean immediate = request.live || liveMaskGesture || directInput"));
    }
}
