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

    @Test public void photoEditorDefaultAndDistributionBoundaryRemain() throws Exception {
        String preferences = source("src/com/mishiranu/dashchan/content/Preferences.java");
        assertTrue(preferences.contains("DEFAULT_NEW_PHOTO_EDITOR = true"));
        assertTrue(preferences.contains("getBoolean(KEY_NEW_PHOTO_EDITOR, DEFAULT_NEW_PHOTO_EDITOR)"));
        assertTrue(preferences.contains("PhotoEditorBridge.isAvailable() && PREFERENCES.getBoolean("));
        // Descriptor availability and the real search/navigation result are covered by
        // ExperimentalPreferenceDescriptorsTest in both Android distribution variants.
    }

    @Test public void legacyEditorAndPostingGuardRemain() throws Exception {
        String legacy = source("src/com/mishiranu/dashchan/ui/posting/ImageEditorActivity.java");
        assertTrue(legacy.contains("PhotoEditorBridge.createIntent("));
        assertFalse(legacy.contains("ExperimentalImageEditorActivity"));
        assertTrue(source("distribution/github/src/com/mishiranu/dashchan/ui/posting/PhotoEditorBridge.java")
                .contains("ExperimentalImageEditorActivity.createIntent("));
        assertTrue(legacy.contains("new Intent(context, ImageEditorActivity.class)"));
        assertTrue(source("src/com/mishiranu/dashchan/ui/posting/PostingAttachmentsController.java")
                .contains("AttachmentResultGuard.matches("));
        String manifest = source("AndroidManifest.xml");
        assertTrue(manifest.contains(".ui.posting.ImageEditorActivity"));
        assertFalse(manifest.contains(".ui.posting.ExperimentalImageEditorActivity"));
        assertTrue(source("distribution/github/AndroidManifest.xml").contains(".ui.posting.ExperimentalImageEditorActivity"));
    }

    @Test public void sessionIoUsesDetachedWorkerSnapshot() throws Exception {
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        String persist = activity.substring(activity.indexOf("private void persist()"),
                activity.indexOf("@Override protected void onSaveInstanceState"));
        assertTrue(persist.contains("sessionBuilder(document.copy()).build()"));
        assertTrue(persist.indexOf("worker.execute(") < persist.indexOf("file.startWrite()"));
        assertTrue(persist.contains("request != sessionGeneration"));
        assertFalse(persist.contains("document.toJson()"));
    }

    @Test public void cropGesturesUseExportGeometryAndRulersReadTheModel() throws Exception {
        String canvas = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorCanvasView.java");
        assertTrue(canvas.contains("EditorGeometry.pinchCrop(document.state(), cropStart"));
        assertTrue(canvas.contains("EditorGeometry.resizeCrop(state, cropStart"));
        assertTrue(canvas.contains("event.findPointerIndex(pinchPointerId0)"));
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        assertTrue(activity.contains("() -> document.state().straighten"));
        assertTrue(activity.contains("control.syncValue(current.get())"));
        assertTrue(activity.contains("for (Runnable binding : rulerBindings) binding.run()"));
        String ruler = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorRulerView.java");
        String sync = ruler.substring(ruler.indexOf("public void syncValue("), ruler.indexOf("private void setValue("));
        assertFalse(sync.contains("listener.onValue("));
    }

    @Test public void editorControlsRemainReadableAndTextErrorsKeepTheDialogOpen() throws Exception {
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        assertTrue(activity.contains("highlight(jpeg, !png[0])"));
        assertTrue(activity.contains("highlight(pngButton, png[0])"));
        assertFalse(activity.contains("setTextColor(selectedColor)"));
        assertFalse(activity.contains("ORIENTATION_LANDSCAPE ? View.GONE"));
        assertTrue(activity.contains("view.setBackgroundTintList(null)"));
        String edit = activity.substring(activity.indexOf("private void editText("), activity.indexOf("private void placeAtCropCenter("));
        assertTrue(edit.contains(".setPositiveButton(android.R.string.ok, null).create()"));
        assertTrue(edit.contains("input.setError(getString(R.string.pe_limit))"));
        assertTrue(edit.indexOf("input.setError(") < edit.indexOf("dialog.dismiss()"));
        assertTrue(edit.contains("scroll.addView(layout)"));
    }

    @Test public void primarySaveIsDirectAndOptionsAreAnIndependentDraft() throws Exception {
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        assertTrue(activity.contains("save = button(R.string.pe_save, this::saveDirect)"));
        String direct = activity.substring(activity.indexOf("private void saveDirect()"), activity.indexOf("private int exportSample("));
        assertTrue(direct.contains("prepareSave();"));
        assertTrue(direct.contains("encode(true, null);"));
        assertTrue(direct.indexOf("prepareSave();") < direct.indexOf("encode(true, null);"));
        assertTrue(activity.contains("R.string.pe_export_settings, this::showExport"));
        String options = activity.substring(activity.indexOf("private void showExport()"), activity.indexOf("private void encode("));
        assertFalse(options.contains("closeSection("));
        assertFalse(options.contains("encode("));
        assertTrue(options.contains("int[] side = {exportSide}"));
        assertTrue(options.contains("int[] quality = {exportQuality}"));
        assertTrue(options.contains("boolean[] png = {exportPng}"));
        assertTrue(options.contains("setPositiveButton(R.string.pe_color_done"));
        assertTrue(options.contains("exportSide = side[0]"));
        assertTrue(options.contains("exportQuality = quality[0]"));
        assertTrue(options.contains("exportPng = png[0]"));
        for (String oldControl : new String[] {"pe_export_hint", "pe_measure", "pe_png", "pe_size_unknown"}) {
            assertFalse(options.contains(oldControl));
        }
    }

    @Test public void sceneMasksUseOneWorkerQueueAndNeverRedrawSharpObjectsOnTop() throws Exception {
        String canvas = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorCanvasView.java");
        assertTrue(canvas.contains("previewScheduler.start(liveMaskGesture || directInput)"));
        assertTrue(canvas.contains("final Map<String, Bitmap> assetSnapshot = new java.util.HashMap<>(assets)"));
        assertTrue(canvas.contains("EditorRenderer.prepareScene(source, state, assetSnapshot"));
        assertTrue(canvas.contains("processedIsScene = scene"));
        String draw = canvas.substring(canvas.indexOf("private void drawCurrentScene()"), canvas.indexOf("public void resetViewport()"));
        assertTrue(draw.indexOf("if (processedIsScene) return") < draw.indexOf("EditorRenderer.drawAnnotations("));
        String renderer = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorRenderer.java");
        assertTrue(renderer.contains("if (!scene) drawAnnotations("));
        assertTrue(renderer.contains("PorterDuff.Mode.SRC"));
        String document = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorDocument.java");
        assertTrue(document.contains("json.optBoolean(\"affectAnnotations\", false)"));
        assertTrue(document.contains("out.affectAnnotations = affectAnnotations"));
    }

    @Test public void presentationDoesNotBecomePartOfTheDocumentOrExportRecipe() throws Exception {
        String document = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorDocument.java");
        assertFalse(document.contains("EditorPose")); assertFalse(document.contains("ValueAnimator"));
        String renderer = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorRenderer.java");
        assertFalse(renderer.contains("EditorPose")); assertFalse(renderer.contains("ValueAnimator"));
        String transition = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorPoseTransition.java");
        assertFalse(transition.contains("EditorDocument")); assertFalse(transition.contains("toJson("));
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        String filters = activity.substring(activity.indexOf("private void filterPanel()"), activity.indexOf("private void drawingPanel("));
        assertFalse(filters.contains("buildPanel()"));
        String ruler = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorRulerView.java");
        assertTrue(ruler.contains("new AccessibilityNodeInfo.RangeInfo("));
        assertFalse(ruler.contains("RangeInfo.obtain("));
    }

    @Test public void compositionFlagsAndIndependentToolSettingsSurviveRestoration() throws Exception {
        String document = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorDocument.java");
        assertTrue(document.contains("out.drawOnComposition = drawOnComposition"));
        assertTrue(document.contains("put(\"drawOnComposition\", drawOnComposition)"));
        assertTrue(document.contains("json.optBoolean(\"drawOnComposition\", false)"));
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        String codec = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorSessionCodec.java");
        for (String key : new String[] {"mosaicSize", "blurPreset", "drawAll", "toolSettingsV2"}) {
            assertTrue(key, codec.contains("put(\"" + key + "\","));
            assertTrue(key, codec.contains("json.opt") && codec.contains("\"" + key + "\""));
        }
        assertTrue(activity.contains("canvas.setDrawingScope(drawAll)"));
        assertTrue(activity.contains("canvas.updateLastMask(kind, toolSize(kind), null)"));
        String parameters = activity.substring(activity.indexOf("private void drawingParameters("),
                activity.indexOf("private void updateBrushSwatch("));
        assertFalse(parameters.contains("pe_blur_strength"));
        assertTrue(parameters.contains("pe_blur_weak"));
        assertTrue(parameters.contains("pe_blur_medium"));
        assertTrue(parameters.contains("pe_blur_strong"));
    }

    @Test public void wholeEraserRequiresWorkerSceneAndPlaquesStayStickerObjects() throws Exception {
        String renderer = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorRenderer.java");
        assertTrue(renderer.contains("item.kind == EditorDocument.Kind.ERASER && item.drawOnComposition"));
        assertTrue(renderer.contains("drawTopDrawing(scene, state, i"));
        assertTrue(renderer.contains("EditorStickers.drawCaption(canvas, item, bounds)"));
        assertTrue(renderer.contains("Color.red(c) * alpha"));
        assertTrue(renderer.contains("Color.argb(a / count, r / a, g / a, b / a)"));
        String canvas = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorCanvasView.java");
        assertTrue(canvas.contains("drawing.drawOnComposition = EditorRenderer.isDrawing(drawingKind) && drawOnComposition"));
        assertTrue(canvas.contains("append(item.drawOnComposition)"));
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        assertTrue(activity.contains("if (!plaque) item.kind = EditorDocument.Kind.TEXT"));
        assertTrue(activity.contains("text.length() > 120"));
        assertTrue(activity.contains("!document.isInTransaction() && toolBinding != null"));
    }

    @Test public void phoneDialogsAndLiveMaskPreviewKeepTheirContracts() throws Exception {
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        assertTrue(activity.contains("new AlertDialog.Builder(this, R.style.PhotoEditorDialogTheme)"));
        assertTrue(activity.contains("Context context = builder.getContext()"));
        assertTrue(activity.contains("input.setSingleLine(false)")); assertTrue(activity.contains("input.setMinLines(3)"));
        assertTrue(activity.contains("InputType.TYPE_TEXT_FLAG_MULTI_LINE"));
        assertTrue(activity.contains("input.setHorizontallyScrolling(false)"));
        assertTrue(activity.contains("item.color = textColor[0]"));
        assertTrue(activity.contains("String text = input.getText().toString();"));
        String canvas = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorCanvasView.java");
        String render = canvas.substring(canvas.indexOf("public void refresh()"), canvas.indexOf("private static String photoKey("));
        assertFalse(render.contains("removeCallbacks(renderRequest)"));
        assertTrue(canvas.contains("previewScheduler.cancelled(request)"));
        assertTrue(canvas.contains("previewScheduler.complete(request)"));
        assertTrue(canvas.contains("if (wasLive) previewScheduler.change(commit)"));
        assertTrue(canvas.contains("boolean immediate = request.live || liveMaskGesture || directInput"));
    }

    @Test public void cropKeepsSharedNavigationOutOfPanelTransitionsAndCanvasInsets() throws Exception {
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        String build = activity.substring(activity.indexOf("private void buildPanel()"), activity.indexOf("private void mutate("));
        assertTrue(build.contains("categories.setVisibility(View.VISIBLE)"));
        assertFalse(build.contains("categories.setVisibility(crop"));
        assertFalse(build.contains("categories.setAlpha("));
        String panels = activity.substring(activity.indexOf("private java.util.List<View> visiblePanels()"),
                activity.indexOf("private void buildPanel()"));
        assertFalse(panels.contains("toolScroll, categories"));
        assertTrue(panels.contains("toolScroll, cropToolbar, cropScale"));
        String crop = activity.substring(activity.indexOf("private void cropPanel()"), activity.indexOf("private String ratioLabel()"));
        assertTrue(crop.contains("canvas.setEditorInsets(cropToolbar.getHeight() + dp(8), cropScale.getHeight() + dp(8))"));
        assertFalse(crop.contains("categories.getHeight()"));
        assertTrue(activity.contains("root.addView(categories, new LinearLayout.LayoutParams(-1, -2))"));
        assertEquals(1, activity.split("categories = new LinearLayout", -1).length - 1);
    }

    @Test public void toolViewportAndAnimationCaptureKeepScrollingContentInsideThePanel() throws Exception {
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        String viewport = activity.substring(activity.indexOf("private static final class ToolScroll"),
                activity.indexOf("public static Intent createIntent"));
        assertTrue(viewport.contains("setClipBounds(new Rect(0, 0, width, height))"));
        assertTrue(viewport.contains("super.onSizeChanged(width, height, oldWidth, oldHeight)"));
        assertTrue(activity.contains("root.setClipChildren(false)"));
        String motion = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/photo/EditorPanelMotion.java");
        int clip = motion.indexOf("canvas.clipRect(0, 0, panel.getWidth(), panel.getHeight())");
        int scroll = motion.indexOf("canvas.translate(-panel.getScrollX(), -panel.getScrollY())");
        int draw = motion.indexOf("panel.draw(canvas)");
        assertTrue(clip >= 0 && clip < scroll && scroll < draw);
    }

    @Test public void directToolSwitchCommitsBeforeCheckpointWithoutHomeOrFakeHistory() throws Exception {
        String activity = source("distribution/github/src/com/mishiranu/dashchan/ui/posting/ExperimentalImageEditorActivity.java");
        String open = activity.substring(activity.indexOf("private void openSection("), activity.indexOf("private void closeSection("));
        assertTrue(open.contains("target == section) return"));
        assertTrue(open.indexOf("target == section") < open.indexOf("canvas.finishGesture(true)"));
        assertTrue(open.indexOf("canvas.finishGesture(true)") < open.indexOf("document.toJson()"));
        assertTrue(open.contains("checkpoint = nextCheckpoint; section = target; buildPanel()"));
        assertFalse(open.contains("closeSection("));
        assertFalse(open.contains("section = Section.HOME"));
        assertFalse(open.contains("document.begin()"));
        assertFalse(open.contains("document.commit()"));
        assertFalse(open.contains("new AlertDialog"));
        String close = activity.substring(activity.indexOf("private void closeSection("), activity.indexOf("private java.util.List<View> visiblePanels()"));
        assertTrue(close.contains("canvas.finishGesture(apply)"));
        assertTrue(close.contains("if (!apply && checkpoint != null)"));
        assertTrue(close.contains("document = restored; canvas.setDocument(document)"));
        assertTrue(activity.contains("if (section != Section.HOME) closeSection(false); else requestClose()"));
    }
}
