package com.mishiranu.dashchan;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.ui.posting.photo.EditorBlurPresets;
import com.mishiranu.dashchan.ui.posting.photo.EditorCanvasView;
import com.mishiranu.dashchan.ui.posting.photo.EditorDocument;
import com.mishiranu.dashchan.ui.posting.photo.EditorRenderer;
import com.mishiranu.dashchan.ui.posting.photo.EditorStickers;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Prepared Android regressions; execution requires the integrating user's test environment. */
@RunWith(AndroidJUnit4.class)
public class PhotoEditorCompositionTest {
    private static EditorDocument.Item image() {
        EditorDocument.Item item = new EditorDocument.Item(); item.kind = EditorDocument.Kind.IMAGE;
        item.asset = "asset.png"; item.x = item.y = .5f; item.size = 1; return item;
    }
    private static EditorDocument.Item stroke(EditorDocument.Kind kind, boolean all) {
        EditorDocument.Item item = new EditorDocument.Item(); item.kind = kind; item.drawOnComposition = all;
        item.color = Color.RED; item.size = .08f; item.x = item.y = .3f; item.endX = item.endY = .7f;
        item.points.add(.3f); item.points.add(.5f); item.points.add(.7f); item.points.add(.5f); return item;
    }
    private static int redPixels(Bitmap bitmap) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            int c = bitmap.getPixel(x, y);
            if (Color.red(c) > Color.green(c) + 30 && Color.red(c) > Color.blue(c) + 30) count++;
        }
        return count;
    }
    @Test public void everyDrawingToolCanAppearAboveObjects() {
        Bitmap photo = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); photo.eraseColor(Color.WHITE);
        Bitmap asset = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); asset.eraseColor(Color.BLACK);
        try {
            for (EditorDocument.Kind kind : new EditorDocument.Kind[] {EditorDocument.Kind.PEN,
                    EditorDocument.Kind.MARKER, EditorDocument.Kind.LINE, EditorDocument.Kind.ARROW,
                    EditorDocument.Kind.RECTANGLE, EditorDocument.Kind.OVAL}) {
                EditorDocument.State state = new EditorDocument.State(); state.items.add(image());
                EditorDocument.Item drawing = stroke(kind,false); state.items.add(drawing);
                Bitmap below = null, above = null;
                try {
                    below = EditorRenderer.prepareScene(photo,state,Collections.singletonMap("asset.png",asset),()->false);
                    assertEquals(kind.name(),0,redPixels(below)); drawing.drawOnComposition = true;
                    above = EditorRenderer.prepareScene(photo,state,Collections.singletonMap("asset.png",asset),()->false);
                    assertTrue(kind.name(),redPixels(above) > 20);
                } finally { if (below != null) below.recycle(); if (above != null) above.recycle(); }
            }
            assertEquals(Color.WHITE,photo.getPixel(50,50));
        } finally { photo.recycle(); asset.recycle(); }
    }
    @Test public void wholeEraserClearsPhotoAndObjectsWithPngAndJpegBacking() {
        Bitmap photo = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); photo.eraseColor(Color.BLUE);
        Bitmap asset = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); asset.eraseColor(Color.BLACK);
        Bitmap scene = null, png = null, jpeg = null;
        try {
            EditorDocument.State state = new EditorDocument.State(); state.items.add(image());
            EditorDocument.Item eraser = stroke(EditorDocument.Kind.ERASER,true); eraser.size = .2f; state.items.add(eraser);
            assertTrue(EditorRenderer.hasSceneMasks(state));
            scene = EditorRenderer.prepareScene(photo,state,Collections.singletonMap("asset.png",asset),()->false);
            png = EditorRenderer.export(photo,state,0,false,Collections.singletonMap("asset.png",asset));
            jpeg = EditorRenderer.export(photo,state,0,true,Collections.singletonMap("asset.png",asset));
            assertEquals(0,Color.alpha(scene.getPixel(50,50))); assertEquals(0,Color.alpha(png.getPixel(50,50)));
            assertEquals(Color.WHITE,jpeg.getPixel(50,50)); assertEquals(Color.BLACK,png.getPixel(20,20));
            assertEquals(Color.BLUE,photo.getPixel(50,50)); assertEquals(Color.BLACK,asset.getPixel(50,50));
        } finally { if(scene!=null)scene.recycle(); if(png!=null)png.recycle(); if(jpeg!=null)jpeg.recycle(); photo.recycle(); asset.recycle(); }
    }
    @Test public void drawingOnlyEraserClearsBothDrawingLayersWithoutErasingPhotoOrObjects() {
        Bitmap photo = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); photo.eraseColor(Color.BLUE);
        Bitmap asset = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); asset.eraseColor(Color.BLACK);
        Bitmap output = null;
        try {
            EditorDocument.State state = new EditorDocument.State(); state.items.add(image());
            state.items.add(stroke(EditorDocument.Kind.PEN,false)); state.items.add(stroke(EditorDocument.Kind.PEN,true));
            EditorDocument.Item eraser = stroke(EditorDocument.Kind.ERASER,false); eraser.size = .2f; state.items.add(eraser);
            output = EditorRenderer.prepareScene(photo,state,Collections.singletonMap("asset.png",asset),()->false);
            assertEquals(Color.BLACK,output.getPixel(50,50)); assertEquals(0,redPixels(output));
            assertEquals(Color.BLUE,photo.getPixel(50,50));
        } finally { if(output!=null)output.recycle(); photo.recycle(); asset.recycle(); }
    }
    @Test public void legacyDrawingScopeAndNewScopeSurviveJsonAndHistory() throws Exception {
        EditorDocument doc = new EditorDocument(); doc.state().items.add(stroke(EditorDocument.Kind.PEN,true));
        JSONObject legacy = doc.toJson(); legacy.getJSONObject("state").getJSONArray("items").getJSONObject(0).remove("drawOnComposition");
        assertFalse(EditorDocument.fromJson(legacy).state().items.get(0).drawOnComposition);
        doc.begin(); doc.state().items.get(0).drawOnComposition = false; doc.commit();
        EditorDocument restored = EditorDocument.fromJson(doc.toJson());
        restored.undo(); assertTrue(restored.state().items.get(0).drawOnComposition);
        restored.redo(); assertFalse(restored.state().items.get(0).drawOnComposition);
    }
    @Test public void catalogHasSixtyUniqueStableIdsAndEditablePlaques() throws Exception {
        assertEquals(24,EditorStickers.GROUPS[0].length); assertEquals(24,EditorStickers.GROUPS[1].length);
        assertEquals(12,EditorStickers.GROUPS[2].length); HashSet<String> ids = new HashSet<>();
        for(String[] group:EditorStickers.GROUPS)for(String id:group) assertTrue(id,ids.add(id));
        assertEquals(60,ids.size());
        EditorDocument doc = new EditorDocument(); EditorDocument.Item item = new EditorDocument.Item();
        item.kind=EditorDocument.Kind.STICKER; item.asset="rounded_label"; item.text="Привет\nHello"; item.angle=31;
        item.x=item.y=.5f; item.size=.8f; doc.state().items.add(item);
        EditorDocument restored = EditorDocument.fromJson(doc.toJson());
        assertEquals(item.text,restored.state().items.get(0).text); assertEquals(31,restored.state().items.get(0).angle,0);
        assertTrue(EditorStickers.isPlaque(item.asset)); assertFalse(EditorStickers.isPlaque("heart"));
    }
    @Test public void changingBlurPresetEditsOnlyLatestMaskAndCanBeUndone() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Bitmap source = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);
            EditorDocument doc = new EditorDocument();
            EditorDocument.Item first=new EditorDocument.Item(); first.kind=EditorDocument.Kind.BLUR; first.endX=first.endY=1;
            EditorDocument.Item last=first.copy(); last.size=.023f; last.affectAnnotations=true;
            doc.state().items.add(first); doc.state().items.add(last);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            EditorCanvasView canvas=new EditorCanvasView(InstrumentationRegistry.getInstrumentation().getTargetContext(),source,doc,
                    Collections.emptyMap(),worker,new EditorCanvasView.Listener(){
                public void onChange(){} public void onRenderFailure(){fail();} public void onLimit(){fail();}
            });
            try {
                canvas.updateLastMask(EditorDocument.Kind.BLUR,EditorBlurPresets.size(2),null);
                assertEquals(.025f,first.size,0); assertEquals(.045f,last.size,0); assertTrue(last.affectAnnotations);
                assertTrue(doc.canUndo()); doc.undo(); assertEquals(.023f,doc.state().items.get(1).size,0);
                assertFalse(doc.canUndo()); doc.redo(); assertEquals(.045f,doc.state().items.get(1).size,0);
            } finally { canvas.release(); worker.shutdown(); source.recycle(); }
        });
    }
    @Test public void sceneBlurIncludesDrawingAboveObjects() {
        Bitmap photo=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888); photo.eraseColor(Color.WHITE);
        Bitmap plain=null, blurred=null;
        try {
            EditorDocument.State state=new EditorDocument.State(); state.items.add(stroke(EditorDocument.Kind.PEN,true));
            plain=EditorRenderer.prepareScene(photo,state,Collections.emptyMap(),()->false);
            EditorDocument.Item mask=new EditorDocument.Item(); mask.kind=EditorDocument.Kind.BLUR;
            mask.endX=mask.endY=1; mask.size=EditorBlurPresets.size(2); mask.affectAnnotations=true; state.items.add(mask);
            blurred=EditorRenderer.prepareScene(photo,state,Collections.emptyMap(),()->false);
            assertNotEquals(plain.getPixel(64,64),blurred.getPixel(64,64));
        } finally {if(plain!=null)plain.recycle();if(blurred!=null)blurred.recycle();photo.recycle();}
    }
    @Test public void wholeEraserClearsEarlierSceneCoverAndLaterTopDrawingCanPaintAgain() {
        Bitmap photo=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); photo.eraseColor(Color.BLUE);
        Bitmap cleared=null, painted=null, exported=null;
        try {
            EditorDocument.State state=new EditorDocument.State();
            EditorDocument.Item cover=new EditorDocument.Item(); cover.kind=EditorDocument.Kind.COVER;
            cover.endX=cover.endY=1; cover.color=Color.BLACK; cover.affectAnnotations=true; state.items.add(cover);
            EditorDocument.Item eraser=stroke(EditorDocument.Kind.ERASER,true); eraser.size=.2f; state.items.add(eraser);
            cleared=EditorRenderer.prepareScene(photo,state,Collections.emptyMap(),()->false);
            assertEquals(0,Color.alpha(cleared.getPixel(50,50)));
            state.items.add(stroke(EditorDocument.Kind.PEN,true));
            painted=EditorRenderer.prepareScene(photo,state,Collections.emptyMap(),()->false);
            exported=EditorRenderer.export(photo,state,0,false,Collections.emptyMap());
            assertEquals(Color.RED,painted.getPixel(50,50)); assertEquals(Color.RED,exported.getPixel(50,50));
            assertEquals(Color.BLACK,exported.getPixel(20,20)); assertEquals(Color.BLUE,photo.getPixel(50,50));
        } finally {if(cleared!=null)cleared.recycle();if(painted!=null)painted.recycle();if(exported!=null)exported.recycle();photo.recycle();}
    }
    @Test public void plaqueCaptionIsVisibleInExportAndSharesStickerTransform() {
        Bitmap photo=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888), output=null;
        try {
            EditorDocument.State state=new EditorDocument.State(); EditorDocument.Item plaque=new EditorDocument.Item();
            plaque.kind=EditorDocument.Kind.STICKER; plaque.asset="rounded_label"; plaque.text="HELLO";
            plaque.x=plaque.y=.5f; plaque.size=.8f; plaque.angle=31; plaque.color=Color.RED; state.items.add(plaque);
            output=EditorRenderer.export(photo,state,0,false,Collections.emptyMap());
            assertTrue(redPixels(output)>10); assertEquals(0,Color.alpha(output.getPixel(0,0)));
            assertEquals(31,plaque.angle,0); assertEquals("HELLO",plaque.text);
        } finally {if(output!=null)output.recycle();photo.recycle();}
    }

    @Test public void everyStickerHasLightArtworkOrBackingOnDarkPhotos() {
        Bitmap bitmap = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        RectF bounds = new RectF(0,0,100,100);
        try {
            for (String[] group : EditorStickers.GROUPS) for (String id : group) {
                bitmap.eraseColor(0xff28292e);
                EditorStickers.draw(canvas,id,bounds);
                int visible = 0;
                for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) {
                    int color = bitmap.getPixel(x,y);
                    if (Math.max(Color.red(color),Math.max(Color.green(color),Color.blue(color))) > 150) visible++;
                }
                assertTrue(id,visible > 30);
            }
        } finally { bitmap.recycle(); }
    }
}
