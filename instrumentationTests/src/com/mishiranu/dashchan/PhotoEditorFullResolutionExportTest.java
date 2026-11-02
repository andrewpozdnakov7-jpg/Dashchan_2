package com.mishiranu.dashchan;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.ui.posting.photo.EditorDocument;
import com.mishiranu.dashchan.ui.posting.photo.EditorExportSizing;
import com.mishiranu.dashchan.ui.posting.photo.EditorGeometry;
import com.mishiranu.dashchan.ui.posting.photo.EditorRenderer;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android pixel/ownership checks, intended for external device execution in source-only mode. */
@RunWith(AndroidJUnit4.class)
public class PhotoEditorFullResolutionExportTest {
    private static Bitmap image(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) row[x] = Color.argb((x + y) % 5 == 0 ? 90 : 255,
                    (x * 13 + y * 3) & 255, (x * 5 + y * 7) & 255, (x * 17 + y) & 255);
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
        return bitmap;
    }

    @Test public void fullSizeWithoutGeometryReusesOwnedPixelsAndKeepsLongEdges() {
        Bitmap source = image(5017, 13), result = null;
        try {
            result = EditorRenderer.exportMutable(source, new EditorDocument.State(), 5017, 13,
                    false, Collections.emptyMap());
            assertSame(source, result);
            assertEquals(5017, result.getWidth()); assertEquals(13, result.getHeight());
            assertFalse(source.isRecycled());
        } finally { if (result != null && result != source) result.recycle(); source.recycle(); }
    }

    private static EditorDocument.State recipe(boolean scene, boolean geometry) {
        EditorDocument.State state = new EditorDocument.State();
        state.adjustments[EditorDocument.Adjustment.BRIGHTNESS.storageIndex] = 17;
        state.adjustments[EditorDocument.Adjustment.CONTRAST.storageIndex] = -13;
        state.adjustments[EditorDocument.Adjustment.SATURATION.storageIndex] = 21;
        state.adjustments[EditorDocument.Adjustment.SHARPNESS.storageIndex] = 70;
        state.filter = EditorDocument.Filter.COOL; state.filterStrength = 35;
        EditorDocument.Item line = new EditorDocument.Item();
        line.kind = EditorDocument.Kind.MARKER; line.size = .08f; line.color = Color.YELLOW;
        line.points.add(.1f); line.points.add(.2f); line.points.add(.9f); line.points.add(.8f);
        state.items.add(line);
        EditorDocument.Item eraser = new EditorDocument.Item();
        eraser.kind = EditorDocument.Kind.ERASER; eraser.size = .06f;
        eraser.points.add(.4f); eraser.points.add(.1f); eraser.points.add(.6f); eraser.points.add(.9f);
        eraser.drawOnComposition = scene;
        state.items.add(eraser);
        EditorDocument.Item text = new EditorDocument.Item();
        text.kind = EditorDocument.Kind.TEXT; text.text = "Export"; text.x = .5f; text.y = .5f;
        text.color = Color.GREEN; text.size = .2f;
        state.items.add(text);
        EditorDocument.Item mask = new EditorDocument.Item();
        mask.kind = EditorDocument.Kind.BLUR; mask.x = .15f; mask.y = .35f; mask.endX = .85f; mask.endY = .7f;
        mask.size = .05f; mask.affectAnnotations = scene;
        state.items.add(mask);
        if (geometry) {
            state.quarterTurns = 1; state.mirror = true; state.straighten = 8f;
            state.crop.set(.1f, .15f, .87f, .9f);
        }
        return state;
    }

    private static Bitmap reference(Bitmap source, EditorDocument.State state, int width, int height, boolean opaque) {
        boolean scene = EditorRenderer.hasSceneMasks(state);
        Bitmap processed = scene ? EditorRenderer.prepareScene(source, state, Collections.emptyMap(), () -> false)
                : EditorRenderer.preparePhoto(source, state);
        try {
            Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            if (opaque) canvas.drawColor(Color.WHITE);
            RectF crop = EditorGeometry.cropPixels(state, source.getWidth(), source.getHeight());
            canvas.scale(width / crop.width(), height / crop.height());
            canvas.translate(-crop.left, -crop.top);
            canvas.concat(EditorGeometry.sourceToFrame(state, source.getWidth(), source.getHeight()));
            canvas.drawBitmap(processed, 0, 0, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            if (!scene) EditorRenderer.drawAnnotations(canvas, state, source.getWidth(), source.getHeight(), Collections.emptyMap());
            return output;
        } finally { processed.recycle(); }
    }

    @Test public void bandProcessingMatchesTheExistingRecipeAcrossBoundariesAndSceneMasks() {
        for (boolean scene : new boolean[] {false, true}) for (boolean geometry : new boolean[] {false, true})
            for (boolean opaque : new boolean[] {false, true}) {
                Bitmap source = image(73, 269), owned = source.copy(Bitmap.Config.ARGB_8888, true);
                Bitmap expected = null, actual = null;
                try {
                    EditorDocument.State state = recipe(scene, geometry);
                    RectF crop = EditorGeometry.cropPixels(state, source.getWidth(), source.getHeight());
                    int[] size = EditorExportSizing.dimensions(crop.width(), crop.height(), 0);
                    expected = reference(source, state, size[0], size[1], opaque);
                    actual = EditorRenderer.exportMutable(owned, state, size[0], size[1], opaque, Collections.emptyMap());
                    assertEquals(size[0], actual.getWidth()); assertEquals(size[1], actual.getHeight());
                    for (int y = 0; y < size[1]; y++) for (int x = 0; x < size[0]; x++) {
                        int a = actual.getPixel(x, y), e = expected.getPixel(x, y);
                        assertEquals(Color.alpha(e), Color.alpha(a), 2);
                        // Premultiplied bitmap compositing may round a channel at a different intermediate boundary.
                        assertEquals(Color.red(e), Color.red(a), 2);
                        assertEquals(Color.green(e), Color.green(a), 2);
                        assertEquals(Color.blue(e), Color.blue(a), 2);
                    }
                    assertFalse(source.isRecycled()); assertFalse(owned.isRecycled());
                } finally {
                    if (actual != null && actual != owned) actual.recycle();
                    if (expected != null) expected.recycle();
                    owned.recycle(); source.recycle();
                }
            }
    }

    @Test public void mutableDecodeRetainsActualFileDimensions() throws Exception {
        android.content.Context context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("editor-export-test-", ".png", context.getCacheDir());
        Bitmap source = image(511, 37), decoded = null;
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            FileHolder holder = FileHolder.obtain(file);
            assertNotNull(holder);
            decoded = holder.readImageBitmap(Integer.MAX_VALUE, false, false, true);
            assertNotNull(decoded); assertTrue(decoded.isMutable());
            assertEquals(Bitmap.Config.ARGB_8888, decoded.getConfig());
            assertEquals(511, decoded.getWidth()); assertEquals(37, decoded.getHeight());
            decoded.setPixel(510, 36, Color.MAGENTA);
            assertEquals(Color.MAGENTA, decoded.getPixel(510, 36));
        } finally { if (decoded != null) decoded.recycle(); source.recycle(); file.delete(); }
    }

    @Test public void maskDownsamplingDoesNotPullColorsFromOutsideItsIntegerRegion() {
        Bitmap source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), result = null;
        try {
            source.eraseColor(Color.GREEN);
            for (int y = 4; y < 12; y++) for (int x = 4; x < 12; x++) source.setPixel(x, y, Color.RED);
            EditorDocument.State state = new EditorDocument.State();
            EditorDocument.Item mask = new EditorDocument.Item();
            mask.kind = EditorDocument.Kind.MOSAIC; mask.x = .25f; mask.y = .25f;
            mask.endX = .75f; mask.endY = .75f; mask.size = .001f;
            state.items.add(mask);
            result = EditorRenderer.exportMutable(source, state, 16, 16, false, Collections.emptyMap());
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                assertEquals(x >= 4 && x < 12 && y >= 4 && y < 12 ? Color.RED : Color.GREEN, result.getPixel(x, y));
        } finally { if (result != null && result != source) result.recycle(); source.recycle(); }
    }
}
