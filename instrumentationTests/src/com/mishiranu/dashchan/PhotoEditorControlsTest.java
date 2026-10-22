package com.mishiranu.dashchan;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.ui.posting.photo.EditorCanvasView;
import com.mishiranu.dashchan.ui.posting.photo.EditorDocument;
import com.mishiranu.dashchan.ui.posting.photo.EditorRenderer;
import com.mishiranu.dashchan.ui.posting.photo.EditorStickers;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android tests prepared for the integrating Codex; not executed in source-only mode. */
@RunWith(AndroidJUnit4.class)
public class PhotoEditorControlsTest {
    @Test public void legacyMasksKeepPhotoOnlyAndNewScopeSurvivesHistoryAndJson() throws Exception {
        EditorDocument doc = new EditorDocument(); EditorDocument.Item mask = new EditorDocument.Item();
        mask.kind = EditorDocument.Kind.BLUR; mask.endX = mask.endY = 1; doc.state().items.add(mask);
        JSONObject legacy = doc.toJson(); legacy.getJSONObject("state").getJSONArray("items").getJSONObject(0).remove("affectAnnotations");
        assertFalse(EditorDocument.fromJson(legacy).state().items.get(0).affectAnnotations);
        doc.begin(); mask.affectAnnotations = true; doc.commit();
        EditorDocument restored = EditorDocument.fromJson(doc.toJson());
        assertTrue(restored.state().items.get(0).affectAnnotations);
        restored.undo(); assertFalse(restored.state().items.get(0).affectAnnotations);
        restored.redo(); assertTrue(restored.state().items.get(0).affectAnnotations);
    }
    @Test public void blurEverythingIncludesAnnotationsAndPreviewEqualsExport() {
        Bitmap photo = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888); photo.eraseColor(Color.WHITE);
        Bitmap asset = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); asset.eraseColor(Color.BLACK);
        Map<String, Bitmap> assets = Collections.singletonMap("asset.png", asset);
        EditorDocument.State state = new EditorDocument.State();
        EditorDocument.Item image = new EditorDocument.Item(); image.kind = EditorDocument.Kind.IMAGE;
        image.asset = "asset.png"; image.x = image.y = .5f; image.size = .25f; state.items.add(image);
        EditorDocument.Item mask = new EditorDocument.Item(); mask.kind = EditorDocument.Kind.BLUR;
        mask.endX = mask.endY = 1; mask.size = .2f; state.items.add(mask);
        Bitmap photoOnly = null, all = null, exported = null;
        try {
            photoOnly = EditorRenderer.prepareScene(photo, state, assets, () -> false);
            assertEquals(Color.BLACK, photoOnly.getPixel(64, 64));
            mask.affectAnnotations = true;
            all = EditorRenderer.prepareScene(photo, state, assets, () -> false);
            assertNotEquals(Color.BLACK, all.getPixel(64, 64));
            exported = EditorRenderer.export(photo, state, 0, false, assets);
            int[] a = new int[128 * 128], b = new int[a.length];
            all.getPixels(a, 0, 128, 0, 0, 128, 128); exported.getPixels(b, 0, 128, 0, 0, 128, 128);
            assertArrayEquals(a, b); assertEquals(Color.WHITE, photo.getPixel(64, 64));
        } finally {
            if (photoOnly != null) photoOnly.recycle(); if (all != null) all.recycle(); if (exported != null) exported.recycle(); photo.recycle(); asset.recycle();
        }
    }
    @Test public void laterAnnotationEditsRemainInsideLiveSceneMask() {
        Bitmap photo = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888); photo.eraseColor(Color.WHITE);
        EditorDocument.State state = new EditorDocument.State();
        EditorDocument.Item mask = new EditorDocument.Item(); mask.kind = EditorDocument.Kind.BLUR;
        mask.endX = mask.endY = 1; mask.size = .2f; mask.affectAnnotations = true; state.items.add(mask);
        EditorDocument.Item text = new EditorDocument.Item(); text.kind = EditorDocument.Kind.TEXT;
        text.x = text.y = .5f; text.text = "TEST"; text.color = Color.BLACK; text.size = .3f;
        state.items.add(text); Bitmap first = null, second = null;
        try {
            first = EditorRenderer.prepareScene(photo, state, Collections.emptyMap(), () -> false);
            text.color = Color.RED;
            second = EditorRenderer.prepareScene(photo, state, Collections.emptyMap(), () -> false);
            assertNotEquals(first.getPixel(64, 64), second.getPixel(64, 64));
            assertEquals(2, state.items.size()); assertEquals("TEST", text.text);
        } finally { if (first != null) first.recycle(); if (second != null) second.recycle(); photo.recycle(); }
    }
    @Test public void vectorStickersRoundTripAndProduceTransparentArtwork() throws Exception {
        for (String[] group : EditorStickers.GROUPS) for (String id : group) {
            EditorDocument doc = new EditorDocument(); EditorDocument.Item item = new EditorDocument.Item();
            item.kind = EditorDocument.Kind.STICKER; item.asset = id; item.x = item.y = .5f; item.size = .8f; item.angle = 27;
            doc.state().items.add(item); EditorDocument restored = EditorDocument.fromJson(doc.toJson());
            assertEquals(id, restored.state().items.get(0).asset); assertEquals(27, restored.state().items.get(0).angle, 0);
            Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888), output = null;
            try {
                output = EditorRenderer.export(source, restored.state(), 0, false, Collections.emptyMap());
                int nonTransparent = 0;
                for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) if (Color.alpha(output.getPixel(x, y)) > 0) nonTransparent++;
                assertTrue(id, nonTransparent > 200); assertTrue(id, nonTransparent < 9500);
                assertEquals(0, Color.alpha(output.getPixel(0, 0)));
            } finally { if (output != null) output.recycle(); source.recycle(); }
        }
    }
    @Test public void scenePreviewKeyTracksObjectEditsButPhotoOnlyKeyDoesNot() throws Exception {
        java.lang.reflect.Method key = EditorCanvasView.class.getDeclaredMethod("photoKey", EditorDocument.State.class); key.setAccessible(true);
        EditorDocument.State state = new EditorDocument.State();
        EditorDocument.Item mask = new EditorDocument.Item(); mask.kind = EditorDocument.Kind.BLUR; mask.endX = mask.endY = 1;
        EditorDocument.Item text = new EditorDocument.Item(); text.kind = EditorDocument.Kind.TEXT; text.text = "A"; text.x = text.y = .5f;
        state.items.add(mask); state.items.add(text); String photoKey = (String) key.invoke(null, state);
        text.angle = 45; assertEquals(photoKey, key.invoke(null, state));
        mask.affectAnnotations = true; String sceneKey = (String) key.invoke(null, state);
        text.angle = 90; assertNotEquals(sceneKey, key.invoke(null, state));
        sceneKey = (String) key.invoke(null, state); text.color = Color.RED; assertNotEquals(sceneKey, key.invoke(null, state));
        sceneKey = (String) key.invoke(null, state); state.items.remove(text); assertNotEquals(sceneKey, key.invoke(null, state));
    }
    private static void touch(EditorCanvasView canvas, int action, float x, float y) {
        long time = android.os.SystemClock.uptimeMillis(); MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
        try { canvas.onTouchEvent(event); } finally { event.recycle(); }
    }
    private static float[] handles(EditorCanvasView canvas) throws Exception {
        java.lang.reflect.Method update = EditorCanvasView.class.getDeclaredMethod("updateControls"); update.setAccessible(true); update.invoke(canvas);
        java.lang.reflect.Field points = EditorCanvasView.class.getDeclaredField("controlPoints"); points.setAccessible(true);
        return ((float[]) points.get(canvas)).clone();
    }
    @Test public void oneFingerRotationDeleteUndoAndCancelWorkWithMirroredPhoto() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (boolean mirror : new boolean[] {false, true}) {
                Bitmap source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
                EditorDocument doc = new EditorDocument(); doc.state().mirror = mirror;
                EditorDocument.Item item = new EditorDocument.Item(); item.kind = EditorDocument.Kind.TEXT; item.text = "X";
                item.x = item.y = .5f; item.size = .1f; item.mirrored = mirror; doc.state().items.add(item);
                ExecutorService worker = Executors.newSingleThreadExecutor();
                EditorCanvasView canvas = new EditorCanvasView(InstrumentationRegistry.getInstrumentation().getTargetContext(), source, doc,
                        Collections.emptyMap(), worker, new EditorCanvasView.Listener() {
                    public void onChange() {} public void onRenderFailure() { fail(); } public void onLimit() { fail(); }
                });
                try {
                    canvas.layout(0, 0, 900, 900); canvas.select(item); float[] points = handles(canvas);
                    touch(canvas, MotionEvent.ACTION_DOWN, points[2], points[3]);
                    float radius = (float) Math.hypot(points[2] - 450, points[3] - 450);
                    // A text frame is not necessarily square at every screen density/font metric.
                    float startAngle = (float) Math.toDegrees(Math.atan2(points[3] - 450, points[2] - 450));
                    float expected = -startAngle * (mirror ? -1 : 1);
                    touch(canvas, MotionEvent.ACTION_MOVE, 450 + radius, 450);
                    assertEquals(expected, doc.state().items.get(0).angle, 1);
                    touch(canvas, MotionEvent.ACTION_CANCEL, 450 + radius, 450);
                    assertEquals(0, doc.state().items.get(0).angle, 0); assertFalse(doc.canUndo());
                    assertSame(doc.state().items.get(0), canvas.selectedItem());
                    canvas.select(doc.state().items.get(0)); points = handles(canvas);
                    touch(canvas, MotionEvent.ACTION_DOWN, points[2], points[3]); touch(canvas, MotionEvent.ACTION_MOVE, 450 + radius, 450);
                    touch(canvas, MotionEvent.ACTION_UP, 450 + radius, 450); assertTrue(doc.canUndo());
                    doc.undo(); canvas.refresh(); assertEquals(0, doc.state().items.get(0).angle, 0);
                    canvas.select(doc.state().items.get(0)); points = handles(canvas);
                    touch(canvas, MotionEvent.ACTION_DOWN, points[0], points[1]); touch(canvas, MotionEvent.ACTION_UP, points[0], points[1]);
                    assertEquals(0, doc.state().items.size()); doc.undo(); assertEquals(1, doc.state().items.size());
                } catch (Exception e) { throw new AssertionError(e); }
                finally { canvas.release(); worker.shutdown(); source.recycle(); }
            }
        });
    }
    @Test public void brushSizePreviewNeverChangesDocumentOrHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            EditorDocument doc = new EditorDocument(); ExecutorService worker = Executors.newSingleThreadExecutor();
            EditorCanvasView canvas = new EditorCanvasView(InstrumentationRegistry.getInstrumentation().getTargetContext(), source, doc,
                    Collections.emptyMap(), worker, new EditorCanvasView.Listener() {
                public void onChange() {} public void onRenderFailure() { fail(); } public void onLimit() { fail(); }
            });
            try {
                canvas.layout(0, 0, 400, 400); canvas.setDrawingKind(EditorDocument.Kind.PEN);
                String before = doc.toJson().toString(); canvas.beginBrushPreview(); canvas.setBrush(Color.RED, .1f); canvas.showBrushPreview();
                canvas.endBrushPreview(); canvas.hideBrushPreview();
                assertEquals(before, doc.toJson().toString()); assertFalse(doc.canUndo()); assertEquals(0, doc.state().items.size());
            } catch (Exception e) { throw new AssertionError(e); }
            finally { canvas.release(); worker.shutdown(); source.recycle(); }
        });
    }

    @Test public void transparentNeighborsDoNotDarkenColorDuringBoxBlur() throws Exception {
        java.lang.reflect.Method blur = EditorRenderer.class.getDeclaredMethod("blurSmall", Bitmap.class, java.util.function.BooleanSupplier.class);
        blur.setAccessible(true);
        for (int color : new int[] {Color.RED, Color.GREEN, Color.BLUE}) {
            Bitmap original = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888), result = null;
            original.setPixel(2, 2, color);
            try {
                result = (Bitmap) blur.invoke(null, original, (java.util.function.BooleanSupplier) () -> false);
                int pixel = result.getPixel(2, 2);
                assertEquals(255 / 25, Color.alpha(pixel));
                assertEquals(Color.red(color), Color.red(pixel)); assertEquals(Color.green(color), Color.green(pixel));
                assertEquals(Color.blue(color), Color.blue(pixel)); assertEquals(color, original.getPixel(2, 2));
            } finally { if (result != null) result.recycle(); original.recycle(); }
        }
    }

    @Test public void blurOnTransparentPhotoReplacesOpaqueAnnotationPixels() {
        Bitmap photo = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Bitmap asset = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); asset.eraseColor(Color.BLACK);
        EditorDocument.State state = new EditorDocument.State();
        EditorDocument.Item image = new EditorDocument.Item(); image.kind = EditorDocument.Kind.IMAGE;
        image.asset = "asset.png"; image.x = image.y = .5f; image.size = .25f; state.items.add(image);
        EditorDocument.Item mask = new EditorDocument.Item(); mask.kind = EditorDocument.Kind.BLUR;
        mask.endX = mask.endY = 1; mask.size = .2f; mask.affectAnnotations = true; state.items.add(mask);
        Bitmap scene = null, exported = null;
        try {
            Map<String, Bitmap> assets = Collections.singletonMap("asset.png", asset);
            scene = EditorRenderer.prepareScene(photo, state, assets, () -> false);
            int alpha = Color.alpha(scene.getPixel(64, 64));
            assertTrue(alpha > 0); assertTrue("Unblurred opaque object must not remain underneath", alpha < 255);
            exported = EditorRenderer.export(photo, state, 0, false, assets);
            assertEquals(alpha, Color.alpha(exported.getPixel(64, 64)));
            assertEquals(0, Color.alpha(photo.getPixel(64, 64)));
        } finally {
            if (scene != null) scene.recycle(); if (exported != null) exported.recycle(); photo.recycle(); asset.recycle();
        }
    }
}
