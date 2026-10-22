package com.mishiranu.dashchan;

import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.mishiranu.dashchan.ui.posting.photo.EditorBlurPresets;
import com.mishiranu.dashchan.ui.posting.photo.EditorDocument;
import com.mishiranu.dashchan.ui.posting.photo.EditorExportRequest;
import com.mishiranu.dashchan.ui.posting.photo.EditorSessionCodec;
import com.mishiranu.dashchan.ui.posting.photo.EditorSessionSnapshot;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android JSON/geometry/Bitmap tests. Prepared for external execution, not run in source-only mode. */
@RunWith(AndroidJUnit4.class)
public class PhotoEditorSessionCodecTest {
    private static EditorSessionSnapshot.Builder seed() {
        return new EditorSessionSnapshot.Builder(new EditorDocument(), "source-hash", "photo.png");
    }

    private static JSONObject legacySession() throws JSONException {
        return new JSONObject().put("hash", "source-hash").put("name", "photo.png")
                .put("document", new EditorDocument().toJson()).put("section", "HOME");
    }

    @Test public void allFieldsHistoryAndAdjustmentPositionsRoundTrip() throws Exception {
        EditorSessionSnapshot.Builder captured = seed();
        captured.document.begin();
        int[] values = {-91, 72, -53, 34, -15, 96, -27};
        System.arraycopy(values, 0, captured.document.state().adjustments, 0, values.length);
        captured.document.commit();
        captured.document.begin();
        captured.document.state().mirror = true;
        captured.document.commit();
        captured.document.undo();
        captured.checkpoint = captured.document.toJson().toString();
        captured.section = EditorSessionSnapshot.Section.ADJUST;
        captured.adjustmentIndex = 6;
        captured.drawTool = EditorDocument.Kind.MARKER;
        captured.hideTool = EditorDocument.Kind.MOSAIC;
        captured.selectedColor = Color.MAGENTA;
        captured.brushSize = .031f;
        captured.mosaicSize = .071f;
        captured.blurPreset = 2;
        captured.drawAll = false;
        captured.objectAboveEffects = true;
        captured.maskAll = false;
        captured.exportSide = 3072;
        captured.exportQuality = 87;
        captured.exportPng = true;
        JSONObject encoded = EditorSessionCodec.encode(captured.build());
        EditorSessionSnapshot.Builder restored = seed();
        EditorSessionCodec.restore(encoded, restored);
        EditorSessionSnapshot result = restored.build();
        assertEquals("source-hash", result.sourceHash);
        assertEquals("photo.png", result.sourceName);
        assertEquals(captured.checkpoint, result.checkpoint);
        assertEquals(EditorSessionSnapshot.Section.ADJUST, result.section);
        assertEquals(6, result.adjustmentIndex);
        assertEquals(captured.drawTool, result.drawTool);
        assertEquals(captured.hideTool, result.hideTool);
        assertEquals(Color.MAGENTA, result.selectedColor);
        assertEquals(.031f, result.brushSize, 0f);
        assertEquals(.071f, result.mosaicSize, 0f);
        assertEquals(2, result.blurPreset);
        assertFalse(result.drawAll);
        assertTrue(result.objectAboveEffects);
        assertFalse(result.maskAll);
        assertEquals(3072, result.exportSide);
        assertEquals(87, result.exportQuality);
        assertTrue(result.exportPng);
        assertArrayEquals(values, result.document.state().adjustments);
        // Sharpness accepts legacy negative JSON values even though its UI starts at zero.
        assertEquals(-27, result.document.state().adjustments[EditorDocument.Adjustment.SHARPNESS.storageIndex]);
        assertTrue(result.document.canUndo());
        assertTrue(result.document.canRedo());
        assertFalse(result.document.state().mirror);
        result.document.redo();
        assertTrue(result.document.state().mirror);
        assertEquals(1, encoded.getJSONObject("document").getInt("version"));
        assertTrue(encoded.getBoolean("toolSettingsV2"));
        assertTrue(encoded.getBoolean("exportOptionsV2"));
        assertEquals(20, encoded.length());
    }

    @Test public void oldSessionsKeepDefaultsAndLegacyOptionMigration() throws Exception {
        EditorSessionSnapshot.Builder restored = seed();
        restored.exportPng = true; // Default derived from the source image, before loading a session.
        JSONObject legacy = legacySession().put("size", .045).put("side", 2048);
        EditorSessionCodec.restore(legacy, restored);
        assertNull(restored.checkpoint);
        assertEquals(EditorSessionSnapshot.Section.HOME, restored.section);
        assertEquals(Color.WHITE, restored.selectedColor);
        assertEquals(0, restored.adjustmentIndex);
        assertEquals(EditorDocument.Kind.PEN, restored.drawTool);
        assertEquals(EditorDocument.Kind.COVER, restored.hideTool);
        assertEquals(.045f, restored.mosaicSize, 0f);
        assertEquals(EditorBlurPresets.nearest(.045f), restored.blurPreset);
        assertTrue(restored.drawAll);
        assertFalse(restored.objectAboveEffects);
        assertTrue(restored.maskAll);
        assertTrue(restored.exportPng);
        assertEquals(0, restored.exportSide); // No exportOptionsV2 marker in an old session.
        assertEquals(94, restored.exportQuality);
        legacy.put("exportOptionsV2", true).put("side", 16384);
        EditorSessionCodec.restore(legacy, restored);
        assertEquals(0, restored.exportSide);
    }

    @Test public void malformedSelectionsSizesAndOptionsUseExistingBounds() throws Exception {
        JSONObject json = legacySession().put("adjustment", 900).put("quality", 12)
                .put("size", .9).put("mosaicSize", -.4).put("blurPreset", 900)
                .put("side", 99).put("exportOptionsV2", true);
        EditorSessionSnapshot.Builder restored = seed();
        EditorSessionCodec.restore(json, restored);
        assertEquals(6, restored.adjustmentIndex);
        assertEquals(50, restored.exportQuality);
        assertEquals(.1f, restored.brushSize, 0f);
        assertEquals(.001f, restored.mosaicSize, 0f);
        assertEquals(2, restored.blurPreset);
        assertEquals(0, restored.exportSide);
        json.put("adjustment", -9).put("quality", 120).put("side", 1024).put("toolSettingsV2", true);
        json.remove("blurPreset");
        EditorSessionCodec.restore(json, restored);
        assertEquals(0, restored.adjustmentIndex);
        assertEquals(100, restored.exportQuality);
        assertEquals(1024, restored.exportSide);
        assertEquals(1, restored.blurPreset);
    }

    @Test public void foreignSourceIsRejectedBeforeAnyBuilderMutation() throws Exception {
        EditorSessionSnapshot.Builder restored = seed();
        restored.selectedColor = Color.BLUE;
        EditorDocument original = restored.document;
        JSONObject json = legacySession().put("hash", "foreign").put("color", Color.RED);
        try {
            EditorSessionCodec.restore(json, restored);
            fail("Foreign source accepted");
        } catch (JSONException expected) {
            assertEquals("Source mismatch", expected.getMessage());
        }
        assertSame(original, restored.document);
        assertEquals(Color.BLUE, restored.selectedColor);
    }

    @Test public void unsupportedDocumentAndPartialRestoreKeepOldFailureOrder() throws Exception {
        JSONObject badVersion = legacySession();
        badVersion.getJSONObject("document").put("version", 2);
        EditorSessionSnapshot.Builder restored = seed();
        EditorDocument original = restored.document;
        try {
            EditorSessionCodec.restore(badVersion, restored);
            fail("Unknown document version accepted");
        } catch (JSONException expected) {
            assertSame(original, restored.document);
        }
        JSONObject badTool = legacySession().put("color", Color.CYAN).put("size", .9)
                .put("png", true).put("side", 3072).put("exportOptionsV2", true)
                .put("quality", 200).put("adjustment", 4).put("drawTool", "INVALID");
        try {
            EditorSessionCodec.restore(badTool, restored);
            fail("Invalid tool accepted");
        } catch (IllegalArgumentException expected) {
            // The Activity resets document/checkpoint/section, but preserves these already read values.
            assertEquals(Color.CYAN, restored.selectedColor);
            assertEquals(.9f, restored.brushSize, 0f); // Brush validation has not run yet.
            assertEquals(200, restored.exportQuality); // Quality validation has not run yet either.
            assertTrue(restored.exportPng);
            assertEquals(3072, restored.exportSide);
            assertEquals(4, restored.adjustmentIndex);
        }
    }

    @Test public void sessionSnapshotDocumentAndBuilderOptionsAreDetached() throws Exception {
        EditorDocument live = new EditorDocument();
        live.begin();
        live.state().mirror = true;
        live.commit();
        EditorSessionSnapshot.Builder builder = new EditorSessionSnapshot.Builder(live.copy(), "hash", "photo.jpg");
        builder.exportQuality = 72;
        EditorSessionSnapshot snapshot = builder.build();
        live.state().mirror = false;
        live.state().crop.left = .2f;
        builder.exportQuality = 99;
        assertTrue(snapshot.document.state().mirror);
        assertEquals(0f, snapshot.document.state().crop.left, 0f);
        assertEquals(72, snapshot.exportQuality);
        assertTrue(snapshot.document.canUndo());
        assertTrue(EditorSessionCodec.encode(snapshot).getJSONObject("document").getJSONObject("state").getBoolean("mirror"));
    }

    @Test public void exportRequestCopiesStateAndMapButBorrowsBitmapOwnership() throws Exception {
        EditorDocument live = new EditorDocument();
        EditorDocument.Item item = new EditorDocument.Item();
        item.kind = EditorDocument.Kind.TEXT;
        item.text = "before";
        live.state().items.add(item);
        Map<String, Bitmap> assets = new HashMap<>();
        Bitmap asset = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        assets.put("asset", asset);
        try {
            EditorExportRequest.Source source = new EditorExportRequest.Source("hash", "photo.jpg", 3, 2400, 1600);
            EditorExportRequest request = EditorExportRequest.capture(live,
                    new EditorExportRequest.Options(1024, 87, false), source, true, assets);
            String expectedKey = live.toJson().getJSONObject("state").toString() + ":1024:87:false";
            live.state().mirror = true;
            live.state().crop.left = .2f;
            item.text = "after";
            assets.clear();
            assertFalse(request.state.mirror);
            assertEquals(0f, request.state.crop.left, 0f);
            assertEquals("before", request.state.items.get(0).text);
            assertSame(asset, request.assets.get("asset"));
            assertFalse(asset.isRecycled());
            assertEquals(expectedKey, request.cacheKey);
            assertTrue(request.attach);
            assertEquals("hash", request.source.hash);
            assertEquals("photo.jpg", request.source.name);
            assertEquals(3, request.source.attachmentIndex);
            assertEquals(2400, request.source.width);
            assertEquals(1600, request.source.height);
        } finally {
            asset.recycle();
        }
    }

    @Test public void exportCacheKeyDependsOnStateAndOptionsRatherThanHistory() throws Exception {
        EditorDocument live = new EditorDocument();
        EditorExportRequest.Source source = new EditorExportRequest.Source("hash", "photo.png", 0, 800, 600);
        EditorExportRequest.Options jpeg = new EditorExportRequest.Options(0, 94, false);
        String initial = EditorExportRequest.capture(live, jpeg, source, false, new HashMap<>()).cacheKey;
        live.begin();
        live.state().mirror = true;
        live.commit();
        live.undo();
        assertEquals(initial, EditorExportRequest.capture(live, jpeg, source, false, new HashMap<>()).cacheKey);
        String png = EditorExportRequest.capture(live, new EditorExportRequest.Options(0, 94, true),
                source, false, new HashMap<>()).cacheKey;
        assertNotEquals(initial, png);
        String quality = EditorExportRequest.capture(live, new EditorExportRequest.Options(0, 80, false),
                source, false, new HashMap<>()).cacheKey;
        assertNotEquals(initial, quality);
    }
}
