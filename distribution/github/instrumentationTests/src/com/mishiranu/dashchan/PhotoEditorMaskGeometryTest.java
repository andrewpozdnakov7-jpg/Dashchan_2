package com.mishiranu.dashchan;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.ui.posting.photo.EditorCanvasView;
import com.mishiranu.dashchan.ui.posting.photo.EditorDocument;
import com.mishiranu.dashchan.ui.posting.photo.EditorGeometry;
import com.mishiranu.dashchan.ui.posting.photo.EditorMaskGeometry;
import com.mishiranu.dashchan.ui.posting.photo.EditorRenderer;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Android regressions for mask geometry, editing transactions and object compositing. */
@RunWith(AndroidJUnit4.class)
public class PhotoEditorMaskGeometryTest {
    private static EditorDocument.Item mask() {
        EditorDocument.Item item=new EditorDocument.Item(); item.kind=EditorDocument.Kind.BLUR;
        item.orientedMask=true; item.x=item.y=item.endX=item.endY=.5f; item.maskWidth=.4f; item.maskHeight=.2f;
        return item;
    }
    private static void assertAxes(EditorDocument.Item item, Matrix sourceToView, int w, int h) {
        Matrix matrix=EditorMaskGeometry.matrix(item,w,h); matrix.postConcat(sourceToView);
        float[] basis={1,0,0,1}; matrix.mapVectors(basis);
        assertTrue(basis[0]>0); assertTrue(basis[3]>0); assertEquals(0,basis[1],.001);
        assertEquals(0,basis[2],.001);
    }
    @Test public void newMaskIsScreenAlignedForFineRotationQuarterTurnsMirrorZoomAndPan() {
        for(int q=0;q<4;q++) for(boolean mirror:new boolean[]{false,true}) for(float angle:new float[]{-33,0,33}) {
            EditorDocument.State state=new EditorDocument.State(); state.quarterTurns=q; state.mirror=mirror;
            state.straighten=angle; state.crop.set(.1f,.1f,.9f,.9f);
            Matrix forward=EditorGeometry.sourceToFrame(state,320,180); forward.postScale(1.7f,1.7f); forward.postTranslate(40,70);
            float[] center={160,90}; forward.mapPoints(center); Matrix inverse=new Matrix(); assertTrue(forward.invert(inverse));
            for(boolean reverse:new boolean[]{false,true}) {
                float left=center[0]-35, top=center[1]-20, right=center[0]+35, bottom=center[1]+20;
                EditorDocument.Item item=mask();
                EditorMaskGeometry.fromScreenDrag(item,320,180,inverse,reverse?right:left,reverse?bottom:top,reverse?left:right,reverse?top:bottom);
                assertAxes(item,forward,320,180);
                RectF bounds=EditorMaskGeometry.localBounds(item,320,180);
                Matrix matrix=EditorMaskGeometry.matrix(item,320,180); matrix.postConcat(forward); matrix.mapRect(bounds);
                assertEquals(left,bounds.left,.001); assertEquals(right,bounds.right,.001);
                assertEquals(top,bounds.top,.001); assertEquals(bottom,bounds.bottom,.001);
            }
        }
    }
    @Test public void oldJsonKeepsSourceAlignedRegionAndGeometrySurvivesHistory() throws Exception {
        EditorDocument doc=new EditorDocument(); EditorDocument.Item item=new EditorDocument.Item();
        item.kind=EditorDocument.Kind.BLUR; item.x=.2f; item.y=.3f; item.endX=.7f; item.endY=.8f;
        doc.state().items.add(item); JSONObject json=doc.toJson(); JSONObject old=json.getJSONObject("state").getJSONArray("items").getJSONObject(0);
        old.remove("orientedMask"); old.remove("maskWidth"); old.remove("maskHeight"); old.remove("aboveEffects");
        EditorDocument restored=EditorDocument.fromJson(json); item=restored.state().items.get(0);
        assertFalse(item.orientedMask); assertFalse(item.aboveEffects);
        RectF before=EditorMaskGeometry.localBounds(item,200,100); EditorMaskGeometry.matrix(item,200,100).mapRect(before);
        restored.begin(); EditorMaskGeometry.makeEditable(item,200,100);
        RectF after=EditorMaskGeometry.localBounds(item,200,100); EditorMaskGeometry.matrix(item,200,100).mapRect(after);
        assertEquals(before.left,after.left,.001); assertEquals(before.right,after.right,.001);
        assertEquals(before.top,after.top,.001); assertEquals(before.bottom,after.bottom,.001); item.angle=17; restored.commit();
        restored=EditorDocument.fromJson(restored.toJson()); assertEquals(17,restored.state().items.get(0).angle,0);
        restored.undo(); assertFalse(restored.state().items.get(0).orientedMask);
        restored.redo(); assertTrue(restored.state().items.get(0).orientedMask);
    }
    @Test public void rotatedRegionClipsActualEffectRatherThanItsBoundingBox() {
        Bitmap photo=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888); photo.eraseColor(Color.WHITE);
        Bitmap scene=null,output=null;
        try {
            EditorDocument.State state=new EditorDocument.State(); EditorDocument.Item item=mask();
            item.kind=EditorDocument.Kind.COVER; item.color=Color.RED; item.maskWidth=.5f; item.maskHeight=.15f; item.angle=33;
            item.affectAnnotations=true; state.items.add(item);
            scene=EditorRenderer.prepareScene(photo,state,Collections.emptyMap(),()->false);
            output=EditorRenderer.export(photo,state,0,false,Collections.emptyMap());
            assertEquals(Color.RED,scene.getPixel(80,50)); assertEquals(Color.WHITE,scene.getPixel(42,20));
            assertEquals(Color.WHITE,output.getPixel(42,20)); assertEquals(Color.RED,output.getPixel(80,50));
            assertEquals(Color.WHITE,photo.getPixel(80,50));
        } finally { if(scene!=null)scene.recycle(); if(output!=null)output.recycle(); photo.recycle(); }
    }
    @Test public void objectAboveEffectsAvoidsBlurAndWholePictureEraser() {
        Bitmap photo=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); photo.eraseColor(Color.WHITE);
        Bitmap asset=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888); asset.eraseColor(Color.BLACK);
        Bitmap below=null,above=null,erased=null,protectedImage=null;
        try {
            EditorDocument.State state=new EditorDocument.State(); EditorDocument.Item image=new EditorDocument.Item();
            image.kind=EditorDocument.Kind.IMAGE; image.asset="asset.png"; image.x=image.y=.5f; image.size=.2f; state.items.add(image);
            EditorDocument.Item blur=new EditorDocument.Item(); blur.kind=EditorDocument.Kind.BLUR; blur.endX=blur.endY=1;
            blur.size=.1f; blur.affectAnnotations=true; state.items.add(blur);
            below=EditorRenderer.prepareScene(photo,state,Collections.singletonMap("asset.png",asset),()->false);
            assertNotEquals(Color.BLACK,below.getPixel(50,50)); image.aboveEffects=true;
            above=EditorRenderer.export(photo,state,0,false,Collections.singletonMap("asset.png",asset));
            assertEquals(Color.BLACK,above.getPixel(50,50));
            EditorDocument.Item eraser=new EditorDocument.Item(); eraser.kind=EditorDocument.Kind.ERASER;
            eraser.drawOnComposition=true; eraser.size=.4f; eraser.points.add(.5f); eraser.points.add(.5f); state.items.add(eraser);
            image.aboveEffects=false; erased=EditorRenderer.export(photo,state,0,false,Collections.singletonMap("asset.png",asset));
            assertEquals(0,Color.alpha(erased.getPixel(50,50))); image.aboveEffects=true;
            protectedImage=EditorRenderer.export(photo,state,0,false,Collections.singletonMap("asset.png",asset));
            assertEquals(Color.BLACK,protectedImage.getPixel(50,50));
        } finally { if(below!=null)below.recycle(); if(above!=null)above.recycle(); if(erased!=null)erased.recycle(); if(protectedImage!=null)protectedImage.recycle(); photo.recycle(); asset.recycle(); }
    }
    @Test public void objectLayerPropertySurvivesCopyJsonUndoRedo() throws Exception {
        EditorDocument doc=new EditorDocument(); EditorDocument.Item item=new EditorDocument.Item();
        item.kind=EditorDocument.Kind.TEXT; item.text="TEXT"; doc.state().items.add(item);
        doc.begin(); item.aboveEffects=true; doc.commit(); assertTrue(doc.copy().state().items.get(0).aboveEffects);
        EditorDocument restored=EditorDocument.fromJson(doc.toJson()); assertTrue(restored.state().items.get(0).aboveEffects);
        restored.undo(); assertFalse(restored.state().items.get(0).aboveEffects);
        restored.redo(); assertTrue(restored.state().items.get(0).aboveEffects);
    }
    @Test public void previewKeyTracksMaskGeometryAndObjectLayer() throws Exception {
        java.lang.reflect.Method key=EditorCanvasView.class.getDeclaredMethod("photoKey",EditorDocument.State.class); key.setAccessible(true);
        EditorDocument.State state=new EditorDocument.State(); EditorDocument.Item mask=mask(); state.items.add(mask);
        String before=(String)key.invoke(null,state); mask.maskWidth=.5f; assertNotEquals(before,key.invoke(null,state));
        before=(String)key.invoke(null,state); mask.angle=20; assertNotEquals(before,key.invoke(null,state));
        before=(String)key.invoke(null,state); mask.scale=2; assertNotEquals(before,key.invoke(null,state));
        mask.affectAnnotations=true; EditorDocument.Item text=new EditorDocument.Item(); text.kind=EditorDocument.Kind.TEXT; state.items.add(text);
        before=(String)key.invoke(null,state); text.aboveEffects=true; assertNotEquals(before,key.invoke(null,state));
    }
    private static void touch(EditorCanvasView canvas,int action,float x,float y) {
        long time=android.os.SystemClock.uptimeMillis(); MotionEvent event=MotionEvent.obtain(time,time,action,x,y,0);
        try {canvas.onTouchEvent(event);} finally {event.recycle();}
    }
    private static Matrix viewport(EditorCanvasView canvas) throws Exception {
        java.lang.reflect.Field field=EditorCanvasView.class.getDeclaredField("sourceToView"); field.setAccessible(true);
        return new Matrix((Matrix)field.get(canvas));
    }
    private static float[] controls(EditorCanvasView canvas) throws Exception {
        java.lang.reflect.Method update=EditorCanvasView.class.getDeclaredMethod("updateControls"); update.setAccessible(true); update.invoke(canvas);
        java.lang.reflect.Field field=EditorCanvasView.class.getDeclaredField("controlPoints"); field.setAccessible(true);
        return ((float[])field.get(canvas)).clone();
    }
    private static EditorCanvasView canvas(Bitmap source,EditorDocument doc,ExecutorService worker) {
        return new EditorCanvasView(InstrumentationRegistry.getInstrumentation().getTargetContext(),source,doc,Collections.emptyMap(),worker,
                new EditorCanvasView.Listener(){public void onChange(){}public void onRenderFailure(){fail();}public void onLimit(){fail();}});
    }
    @Test public void createdMaskHasHandlesRotationCancelDeletionAndUndo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Bitmap source=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888); EditorDocument doc=new EditorDocument(); doc.state().straighten=33;
            ExecutorService worker=Executors.newSingleThreadExecutor(); EditorCanvasView canvas=canvas(source,doc,worker);
            try {
                canvas.layout(0,0,900,900); canvas.setDrawingKind(EditorDocument.Kind.BLUR);
                touch(canvas,MotionEvent.ACTION_DOWN,300,320); touch(canvas,MotionEvent.ACTION_MOVE,580,540); touch(canvas,MotionEvent.ACTION_UP,580,540);
                assertEquals(1,doc.state().items.size()); EditorDocument.Item item=doc.state().items.get(0);
                assertSame(item,canvas.selectedItem()); assertTrue(item.orientedMask); assertAxes(item,viewport(canvas),256,256);
                String before=doc.toJson().toString(); float[] points=controls(canvas); float[] center=EditorMaskGeometry.center(item,256,256); viewport(canvas).mapPoints(center);
                float dx=points[2]-center[0],dy=points[3]-center[1]; double radians=Math.toRadians(40);
                float x=center[0]+(float)(Math.cos(radians)*dx-Math.sin(radians)*dy);
                float y=center[1]+(float)(Math.sin(radians)*dx+Math.cos(radians)*dy);
                touch(canvas,MotionEvent.ACTION_DOWN,points[2],points[3]); touch(canvas,MotionEvent.ACTION_MOVE,x,y);
                assertNotEquals(item.angle, -33, .5f); touch(canvas,MotionEvent.ACTION_CANCEL,x,y);
                assertEquals(before,doc.toJson().toString()); assertSame(doc.state().items.get(0),canvas.selectedItem());
                points=controls(canvas); touch(canvas,MotionEvent.ACTION_DOWN,points[0],points[1]); touch(canvas,MotionEvent.ACTION_UP,points[0],points[1]);
                assertEquals(0,doc.state().items.size()); doc.undo(); assertTrue(doc.state().items.get(0).orientedMask);
            } catch(Exception e){throw new AssertionError(e);}
            finally {canvas.release(); worker.shutdown(); source.recycle();}
        });
    }
    @Test public void selectingLegacyMaskDoesNotMigrateItOrCreateHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Bitmap source=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888); EditorDocument doc=new EditorDocument();
            EditorDocument.Item item=new EditorDocument.Item(); item.kind=EditorDocument.Kind.BLUR; item.x=item.y=.2f; item.endX=item.endY=.7f; doc.state().items.add(item);
            ExecutorService worker=Executors.newSingleThreadExecutor(); EditorCanvasView canvas=canvas(source,doc,worker);
            try {
                canvas.layout(0,0,900,900); canvas.setDrawingKind(EditorDocument.Kind.BLUR);
                float[] center=EditorMaskGeometry.center(item,256,256); viewport(canvas).mapPoints(center);
                touch(canvas,MotionEvent.ACTION_DOWN,center[0],center[1]);
                float jitter=canvas.getResources().getDisplayMetrics().density;
                touch(canvas,MotionEvent.ACTION_MOVE,center[0]+jitter,center[1]+jitter);
                touch(canvas,MotionEvent.ACTION_UP,center[0]+jitter,center[1]+jitter);
                assertFalse(doc.canUndo()); assertFalse(doc.state().items.get(0).orientedMask);
            }catch(Exception e){throw new AssertionError(e);}
            finally {canvas.release(); worker.shutdown(); source.recycle();}
        });
    }
    @Test public void activeOlderMaskReceivesSettingsAndNewRegionModeDoesNotEditIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Bitmap source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888); EditorDocument doc=new EditorDocument();
            EditorDocument.Item first=mask(),last=mask(); doc.state().items.add(first); doc.state().items.add(last);
            ExecutorService worker=Executors.newSingleThreadExecutor(); EditorCanvasView canvas=canvas(source,doc,worker);
            try {
                canvas.setDrawingKind(EditorDocument.Kind.BLUR); canvas.select(first);
                canvas.updateLastMask(EditorDocument.Kind.BLUR,.045f,null); assertEquals(.045f,first.size,0); assertEquals(.025f,last.size,0);
                canvas.requestNewMask(); assertNull(canvas.activeMask(EditorDocument.Kind.BLUR));
                canvas.updateLastMask(EditorDocument.Kind.BLUR,.006f,null); assertEquals(.045f,first.size,0); assertEquals(.025f,last.size,0);
            }finally {canvas.release(); worker.shutdown(); source.recycle();}
        });
    }
    @Test public void selectedCoverReceivesColorAndUndoRestoresIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Bitmap source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888); EditorDocument doc=new EditorDocument();
            EditorDocument.Item first=mask(),last=mask(); first.kind=last.kind=EditorDocument.Kind.COVER;
            first.color=last.color=Color.BLACK; doc.state().items.add(first); doc.state().items.add(last);
            ExecutorService worker=Executors.newSingleThreadExecutor(); EditorCanvasView canvas=canvas(source,doc,worker);
            try {
                canvas.setDrawingKind(EditorDocument.Kind.COVER); canvas.select(first);
                canvas.updateMask(EditorDocument.Kind.COVER,null,null,Color.RED);
                assertEquals(Color.RED,first.color); assertEquals(Color.BLACK,last.color);
                canvas.requestNewMask(); canvas.updateMask(EditorDocument.Kind.COVER,null,null,Color.GREEN);
                assertEquals(Color.RED,first.color); assertEquals(Color.BLACK,last.color);
                doc.undo(); assertEquals(Color.BLACK,doc.state().items.get(0).color); assertFalse(doc.canUndo());
            }finally {canvas.release(); worker.shutdown(); source.recycle();}
        });
    }
    @Test public void tappingWithoutAreaDoesNotCreateMaskOrHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            Bitmap source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888); EditorDocument doc=new EditorDocument();
            ExecutorService worker=Executors.newSingleThreadExecutor(); EditorCanvasView canvas=canvas(source,doc,worker);
            try {
                canvas.layout(0,0,900,900); canvas.setDrawingKind(EditorDocument.Kind.BLUR);
                touch(canvas,MotionEvent.ACTION_DOWN,450,450); touch(canvas,MotionEvent.ACTION_UP,450,450);
                assertTrue(doc.state().items.isEmpty()); assertFalse(doc.canUndo()); assertNull(canvas.selectedItem());
                assertFalse(doc.isInTransaction());
            }finally {canvas.release(); worker.shutdown(); source.recycle();}
        });
    }
}
