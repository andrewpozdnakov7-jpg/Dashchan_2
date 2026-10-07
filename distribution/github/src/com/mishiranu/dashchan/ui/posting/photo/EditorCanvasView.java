package com.mishiranu.dashchan.ui.posting.photo;

import android.content.Context;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Path;
import com.mishiranu.dashchan.R;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CancellationException;

/** Gesture arbitration and display only; no attachment IO or export settings live in this view. */
public final class EditorCanvasView extends View {
    public interface Listener { void onChange(); void onRenderFailure(); void onLimit(); }
    private final Bitmap source;
    private final Map<String, Bitmap> assets;
    private final ExecutorService worker;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint uiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix frameToView = new Matrix(), sourceToView = new Matrix(), viewToSource = new Matrix();
    private final RectF frame = new RectF();
    private final RectF displayCrop = new RectF(), available = new RectF(), overlayBounds = new RectF();
    private final Matrix displayImage = new Matrix(), displayFrame = new Matrix();
    private final Matrix sceneFromTransform = new Matrix();
    private final Matrix sceneFromBase = new Matrix(), sceneOrientation = new Matrix();
    private final EditorPoseTransition presentation = new EditorPoseTransition();
    private final EditorPreviewBlend previewBlend = new EditorPreviewBlend();
    private final Runnable drawCurrentScene = this::drawCurrentScene;
    private Canvas sceneCanvas;
    private Picture annotations = new Picture();
    private boolean annotationsDirty = true;
    private ValueAnimator geometryAnimator, pixelAnimator, gridAnimator;
    private ViewTreeObserver.OnPreDrawListener preDraw;
    private EditorPose pendingPose;
    private final int[] transitionOrigin = new int[2], currentOrigin = new int[2];
    private float pendingRotation, gridStrength, pixelProgress = 1;
    private long pendingDuration, pixelGeneration;
    private Bitmap sceneFrom;
    private boolean sceneWaiting, sceneMirror, directInput;
    private float sceneAngle;
    private String processedKey = "";
    private EditorDocument document;
    private Bitmap processed;
    private boolean processedIsScene, affectAnnotations = true, drawOnComposition = true;
    private String renderKey = "";
    private final EditorPreviewScheduler previewScheduler = new EditorPreviewScheduler();
    private boolean renderPosted, liveMaskGesture;
    private long lastRenderStart;
    private volatile boolean released;
    private boolean enabled = true, cropMode, comparing;
    private EditorDocument.Kind drawingKind;
    private int color = Color.WHITE;
    private float brushSize = .025f;
    private EditorDocument.Item selected, drawing;
    private float zoom = 1, offsetX, offsetY;
    private float insetTop, insetBottom;
    private float downX, downY, lastX, lastY;
    private float sourceDownX, sourceDownY, objectStartX, objectStartY, objectStartScale, objectStartAngle;
    private boolean dragging, objectResize, objectRotate, objectDelete, twoPointers, objectGesture, downAccepted, limitReported;
    private float rotateLast, rotateRaw;
    private final float[] controlPoints = new float[6], selectionCorners = new float[8];
    private final Path selectionPath = new Path();
    private final RectF controlIcon = new RectF();
    private float brushPreviewAlpha;
    private boolean brushTracking;
    private ValueAnimator brushAnimator;
    private final Runnable dismissBrushPreview = () -> {
        if (released || brushTracking) return;
        if (!EditorMotion.enabled()) { hideBrushPreview(); return; }
        brushAnimator = ValueAnimator.ofFloat(brushPreviewAlpha, 0); brushAnimator.setDuration(140);
        brushAnimator.addUpdateListener(a -> { brushPreviewAlpha = (float) a.getAnimatedValue(); invalidate(); });
        brushAnimator.start();
    };
    private float pinchDistance, pinchAngle, pinchZoom, focusX, focusY;
    private float pinchOffsetX, pinchOffsetY;
    private int pinchPointerId0, pinchPointerId1, singlePointerId;
    private boolean ignoreUntilUp;
    private final Matrix pinchViewToFrame = new Matrix();
    private final RectF pinchCropView = new RectF();
    private RectF cropStart;
    private int cropEdges;
    private final Runnable compareHold = () -> { if (!dragging && selected == null && drawingKind == null && !cropMode) {
        comparing = true; invalidate();
    }};
    private final Runnable renderRequest = this::submitRender;

    public EditorCanvasView(Context context, Bitmap source, EditorDocument document, Map<String, Bitmap> assets,
            ExecutorService worker, Listener listener) {
        super(context);
        this.source = source; this.document = document; this.assets = assets; this.worker = worker; this.listener = listener;
        setBackgroundColor(0xff121316); setFocusable(true); refresh();
    }

    public void setDocument(EditorDocument document) {
        finishGesture(false); invalidatePreview(); this.document = document; selected = null; refresh();
    }
    public void setInputEnabled(boolean enabled) { this.enabled = enabled; if (!enabled) finishGesture(true); }
    public void setCropMode(boolean crop) { finishGesture(true); cropMode = crop; selected = null; setBackgroundColor(crop ? Color.BLACK : 0xff121316); resetViewport(); }
    public void setEditorInsets(float top, float bottom) {
        if (insetTop != top || insetBottom != bottom) { insetTop = top; insetBottom = bottom; resetViewport(); }
    }
    public void setDrawingKind(EditorDocument.Kind kind) {
        finishGesture(true); if (drawingKind != kind) invalidatePreview();
        drawingKind = kind; selected = null; invalidate();
    }
    public void setBrush(int color, float size) { this.color = color; brushSize = size; invalidate(); }
    public void setMaskScope(boolean all) { affectAnnotations = all; }
    public void setDrawingScope(boolean all) { drawOnComposition = all; }
    /** The most recently created region is the active region while its tool is open. */
    public EditorDocument.Item lastMask(EditorDocument.Kind kind) {
        for (int i = document.state().items.size() - 1; i >= 0; i--) {
            EditorDocument.Item item = document.state().items.get(i);
            if (item.kind == kind) return item;
        }
        return null;
    }
    public void updateLastMask(EditorDocument.Kind kind, Float size, Boolean all) {
        if (!enabled || released) return;
        finishGesture(true);
        EditorDocument.Item item = lastMask(kind);
        if (item == null || (size == null || item.size == size) && (all == null || item.affectAnnotations == all)) return;
        document.begin();
        if (size != null) item.size = size;
        if (all != null) item.affectAnnotations = all;
        document.commit(); refresh(); listener.onChange();
    }
    public void beginBrushPreview() { settlePresentation(); brushTracking = true; showBrushPreview(); }
    public void showBrushPreview() {
        if (released || drawingKind == null || isMask(drawingKind)) return;
        handler.removeCallbacks(dismissBrushPreview);
        if (brushAnimator != null) { brushAnimator.cancel(); brushAnimator.removeAllUpdateListeners(); brushAnimator = null; }
        brushPreviewAlpha = 1; invalidate();
        if (!brushTracking) handler.postDelayed(dismissBrushPreview, 500);
    }
    public void endBrushPreview() { brushTracking = false; if (brushPreviewAlpha > 0) handler.postDelayed(dismissBrushPreview, 500); }
    public void hideBrushPreview() {
        brushTracking = false; handler.removeCallbacks(dismissBrushPreview);
        if (brushAnimator != null) { brushAnimator.cancel(); brushAnimator.removeAllUpdateListeners(); brushAnimator = null; }
        brushPreviewAlpha = 0; invalidate();
    }
    public EditorDocument.Item selectedItem() { return selected; }
    public void select(EditorDocument.Item item) { selected = item; invalidate(); }
    public void compare(boolean compare) { if (compare) settlePresentation(); comparing = compare; invalidate(); }

    /** Freeze what is visible before changing the document/layout. No history is mutated. */
    public void beginPresentation(long duration, float quarterDelta, boolean pixels) {
        boolean pending = pendingPose != null;
        if (!pending) {
            pendingPose = presentation.initialized() ? presentation.current().copy() : capturePose();
            getLocationInWindow(transitionOrigin); pendingRotation = 0;
        }
        pendingRotation += quarterDelta; pendingDuration = duration;
        cancelGeometry();
        if (pixels) beginSceneChange();
    }
    public void endPresentation() {
        removePreDraw();
        if (pendingPose == null) return;
        preDraw = () -> {
            removePreDraw(); updateMatrices();
            EditorPose start = pendingPose; pendingPose = null;
            getLocationInWindow(currentOrigin);
            start.offset(transitionOrigin[0] - currentOrigin[0], transitionOrigin[1] - currentOrigin[1]);
            EditorPose target = capturePose();
            long token = presentation.begin(start, target, pendingRotation);
            finishSceneChange();
            if (!EditorMotion.enabled()) { presentation.finish(token); invalidate(); return true; }
            long duration = Math.min(EditorMotion.MAX_RETARGET, Math.max(pendingDuration,
                    Math.round(pendingDuration * Math.abs(target.frameAngle - start.frameAngle) / 90f)));
            ValueAnimator animator = ValueAnimator.ofFloat(0, 1); geometryAnimator = animator;
            animator.setDuration(duration); animator.setInterpolator(EditorMotion.GEOMETRY);
            animator.addUpdateListener(a -> { if (presentation.sample(token, (float) a.getAnimatedValue())) invalidate(); });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (geometryAnimator == animation && presentation.finish(token)) { geometryAnimator = null; invalidate(); }
                }
            }); animator.start(); return true;
        };
        getViewTreeObserver().addOnPreDrawListener(preDraw); invalidate();
    }
    private void removePreDraw() {
        if (preDraw != null) { if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(preDraw); preDraw = null; }
    }
    private void cancelGeometry() {
        presentation.invalidate();
        if (geometryAnimator != null) { ValueAnimator old = geometryAnimator; geometryAnimator = null; old.cancel(); old.removeAllListeners(); old.removeAllUpdateListeners(); }
    }
    public void settlePresentation() {
        hideBrushPreview(); removePreDraw(); pendingPose = null; cancelGeometry(); updateMatrices();
        finishPixels();
        if (gridAnimator != null) { gridAnimator.cancel(); gridAnimator.removeAllUpdateListeners(); gridAnimator = null; }
        gridStrength = 0; invalidate();
    }
    public void beginControl(boolean horizon) {
        settlePresentation(); directInput = true; if (horizon) animateGrid(1, EditorMotion.GRID_IN);
    }
    public void endControl(boolean horizon) { directInput = false; if (horizon) animateGrid(0, EditorMotion.GRID_OUT); }
    private void animateGrid(float target, long duration) {
        if (gridAnimator != null) { gridAnimator.cancel(); gridAnimator.removeAllUpdateListeners(); }
        if (!EditorMotion.enabled()) { gridStrength = target; invalidate(); return; }
        gridAnimator = ValueAnimator.ofFloat(gridStrength, target); gridAnimator.setDuration(duration); gridAnimator.setInterpolator(EditorMotion.FADE);
        gridAnimator.addUpdateListener(a -> { gridStrength = (float) a.getAnimatedValue(); invalidate(); }); gridAnimator.start();
    }

    /** One bounded composition; preserve rotated old pixels outside the new source rectangle. */
    public void beginSceneChange() {
        if (!EditorMotion.enabled() || released) { finishPixels(); return; }
        Bitmap snapshot = null;
        Matrix base = new Matrix();
        try {
            RectF bounds = new RectF(0, 0, source.getWidth(), source.getHeight());
            if (sceneFrom != null) {
                RectF previous = new RectF(0, 0, sceneFrom.getWidth(), sceneFrom.getHeight());
                sceneFromTransform.mapRect(previous); bounds.union(previous);
                // Empty corners in successive rotated snapshots must not expand without bound.
                float radius = (float) Math.hypot(source.getWidth(), source.getHeight()) / 2;
                bounds.intersect(source.getWidth() / 2f - radius, source.getHeight() / 2f - radius,
                        source.getWidth() / 2f + radius, source.getHeight() / 2f + radius);
            }
            float scale = Math.min(1, EditorMotion.SCENE_SIDE / Math.max(bounds.width(), bounds.height()));
            snapshot = Bitmap.createBitmap(Math.max(1, Math.min(EditorMotion.SCENE_SIDE, (int) Math.ceil(bounds.width() * scale))),
                    Math.max(1, Math.min(EditorMotion.SCENE_SIDE, (int) Math.ceil(bounds.height() * scale))), Bitmap.Config.ARGB_8888);
            Canvas composition = new Canvas(snapshot); composition.scale(scale, scale); composition.translate(-bounds.left, -bounds.top);
            drawScene(composition);
            base.setScale(1 / scale, 1 / scale); base.postTranslate(bounds.left, bounds.top);
        } catch (RuntimeException | OutOfMemoryError e) { if (snapshot != null) snapshot.recycle(); snapshot = null; }
        EditorPose pose = presentation.initialized() ? presentation.current() : capturePose();
        boolean mirror = pose.mirror; float angle = pose.angle;
        finishPixels(); sceneFrom = snapshot; sceneMirror = mirror; sceneAngle = angle;
        sceneFromBase.set(base); sceneFromTransform.set(base);
        sceneWaiting = snapshot != null; pixelProgress = 0;
    }
    public void finishSceneChange() {
        if (sceneFrom == null || pendingPose != null) return;
        sceneFromTransform.set(sceneFromBase);
        if (sceneMirror != document.state().mirror) {
            // The retained composition is in source coordinates. Express its old orientation
            // in the new pose, including quarter turns and the reversed fine horizon angle.
            EditorPreviewBlend.sourceOrientation(sceneOrientation, sceneAngle, sceneMirror,
                    presentation.current().angle, document.state().mirror, source.getWidth(), source.getHeight());
            sceneFromTransform.postConcat(sceneOrientation);
        }
        sceneWaiting = !processedKey.equals(renderKey);
        if (!sceneWaiting) startPixelFade();
    }
    private void finishPixels() {
        ++pixelGeneration;
        if (pixelAnimator != null) { ValueAnimator old = pixelAnimator; pixelAnimator = null; old.cancel(); old.removeAllListeners(); old.removeAllUpdateListeners(); }
        if (sceneFrom != null) { sceneFrom.recycle(); sceneFrom = null; }
        sceneWaiting = false; sceneFromTransform.reset(); sceneFromBase.reset(); pixelProgress = 1;
    }
    private void startPixelFade() {
        if (sceneFrom == null || !EditorMotion.enabled() || directInput) { finishPixels(); invalidate(); return; }
        sceneWaiting = false; pixelProgress = 0; long token = ++pixelGeneration;
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1); pixelAnimator = animator;
        animator.setDuration(sceneMirror != document.state().mirror ? EditorMotion.MIRROR : EditorMotion.PIXELS);
        animator.setInterpolator(EditorMotion.FADE);
        animator.addUpdateListener(a -> { if (token == pixelGeneration) { pixelProgress = (float) a.getAnimatedValue(); invalidate(); } });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { if (token == pixelGeneration && pixelAnimator == animation) { finishPixels(); invalidate(); } }
        }); animator.start();
    }
    private void drawScene(Canvas target) {
        sceneCanvas = target;
        try { previewBlend.draw(target, sceneFrom, sceneFromTransform, sceneWaiting ? 0 : pixelProgress,
                source.getWidth(), source.getHeight(), drawCurrentScene); }
        finally { sceneCanvas = null; }
    }
    private void drawCurrentScene() {
        sceneCanvas.drawBitmap(processed != null ? processed : source, 0, 0, bitmapPaint);
        if (processedIsScene) return;
        if (annotationsDirty) {
            Canvas recording = annotations.beginRecording(source.getWidth(), source.getHeight());
            try { EditorRenderer.drawAnnotations(recording, document.state(), source.getWidth(), source.getHeight(), assets); }
            finally { annotations.endRecording(); }
            annotationsDirty = false;
        }
        sceneCanvas.drawPicture(annotations);
    }

    public void resetViewport() { zoom = 1; offsetX = offsetY = 0; updateMatrices(); invalidate(); }

    public void refresh() {
        annotationsDirty = true;
        if (selected != null && !document.state().items.contains(selected)) selected = null;
        updateMatrices(); invalidate();
        String key = photoKey(document.state());
        if (!key.equals(renderKey)) {
            renderKey = key; previewScheduler.change(liveMaskGesture || directInput);
        }
        scheduleRender();
    }

    public void invalidatePreview() { previewScheduler.change(false); scheduleRender(); }
    private void scheduleRender() {
        if (released || renderPosted || !previewScheduler.canStart()) return;
        renderPosted = true;
        handler.postDelayed(renderRequest, Math.max(0, 60 - (SystemClock.uptimeMillis() - lastRenderStart)));
    }

    private static String photoKey(EditorDocument.State state) {
        boolean scene = EditorRenderer.hasSceneMasks(state);
        StringBuilder key = new StringBuilder(scene ? "scene:" : "photo:").append(Arrays.toString(state.adjustments))
                .append(state.filter).append(':').append(state.filterStrength);
        for (EditorDocument.Item item : state.items) {
            if (scene || isMask(item.kind)) {
                key.append('|').append(item.kind).append(':').append(item.x).append(':').append(item.y).append(':')
                        .append(item.endX).append(':').append(item.endY).append(':').append(item.size).append(':').append(item.color)
                        .append(':').append(item.affectAnnotations).append(':').append(item.drawOnComposition);
                if (scene) {
                    // Every composited property must invalidate a scene mask, without allocating JSON on MOVE.
                    key.append(':').append(item.angle).append(':').append(item.scale).append(':').append(item.mirrored)
                            .append(':').append(item.background).append(':').append(item.outline)
                            .append(':').append(item.text.length()).append('#').append(item.text)
                            .append(':').append(item.asset.length()).append('#').append(item.asset).append(':').append(item.points);
                }
            }
        }
        return key.toString();
    }

    private void submitRender() {
        renderPosted = false;
        if (released) return;
        final EditorPreviewScheduler.Request request = previewScheduler.start(liveMaskGesture || directInput);
        if (request == null) return;
        lastRenderStart = SystemClock.uptimeMillis();
        final EditorDocument.State state;
        try { state = document.state().copy(); }
        catch (RuntimeException | OutOfMemoryError e) {
            previewScheduler.complete(request); renderKey = ""; finishPixels(); listener.onRenderFailure(); return;
        }
        final Map<String, Bitmap> assetSnapshot = new java.util.HashMap<>(assets);
        final boolean scene = EditorRenderer.hasSceneMasks(state);
        worker.execute(() -> {
            Bitmap result = null; boolean failed = false;
            try {
                if (!released && !previewScheduler.cancelled(request)) {
                    result = scene ? EditorRenderer.prepareScene(source, state, assetSnapshot,
                            () -> released || previewScheduler.cancelled(request))
                            : EditorRenderer.preparePhoto(source, state, () -> released || previewScheduler.cancelled(request));
                }
            } catch (CancellationException ignored) {
                // A new incompatible operation superseded this job, not a MOVE in the same mask gesture.
            } catch (RuntimeException | OutOfMemoryError e) { failed = true; }
            final Bitmap ready = result; final boolean failure = failed;
            handler.post(() -> {
                boolean publish = previewScheduler.complete(request);
                if (released || !publish || ready == null) {
                    if (ready != null) ready.recycle();
                    if (!released && failure && !previewScheduler.cancelled(request)) {
                        renderKey = ""; finishPixels(); listener.onRenderFailure();
                    }
                } else {
                    boolean immediate = request.live || liveMaskGesture || directInput;
                    if (immediate) finishPixels();
                    else if (sceneFrom == null || !sceneWaiting) beginSceneChange();
                    if (processed != null) processed.recycle();
                    processed = ready; processedIsScene = scene; processedKey = photoKey(state); finishSceneChange(); invalidate();
                }
                scheduleRender();
            });
        });
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (pendingPose == null) cancelGeometry(); updateMatrices();
    }

    private EditorPose capturePose() {
        displayCrop.set(EditorGeometry.cropPixels(document.state(), source.getWidth(), source.getHeight())); frameToView.mapRect(displayCrop);
        return EditorPose.capture(sourceToView, frame, displayCrop, available, cropMode, document.state().quarterTurns,
                document.state().mirror, source.getWidth(), source.getHeight());
    }

    private void updateMatrices() {
        if (getWidth() == 0 || getHeight() == 0) return;
        EditorDocument.State state = document.state();
        float width = EditorGeometry.width(state, source.getWidth(), source.getHeight());
        float height = EditorGeometry.height(state, source.getWidth(), source.getHeight());
        RectF visible = cropMode ? new RectF(0, 0, width, height)
                : EditorGeometry.cropPixels(state, source.getWidth(), source.getHeight());
        float margin = dp(cropMode ? 26 : 12);
        float fit = Math.min(Math.max(1, getWidth() - margin * 2) / visible.width(),
                Math.max(1, getHeight() - insetTop - insetBottom - margin * 2) / visible.height());
        float scale = fit * zoom;
        float overflowX = Math.max(0, visible.width() * scale - getWidth() + margin * 2) / 2;
        float overflowY = Math.max(0, visible.height() * scale - getHeight() + insetTop + insetBottom + margin * 2) / 2;
        // Crop gestures constrain the selection to the image themselves. Viewport clamping
        // here would move the fixed selection while zooming near an image boundary.
        if (!cropMode) {
            offsetX = Math.max(-overflowX, Math.min(overflowX, offsetX));
            offsetY = Math.max(-overflowY, Math.min(overflowY, offsetY));
        }
        frameToView.reset(); frameToView.postTranslate(-visible.centerX(), -visible.centerY());
        frameToView.postScale(scale, scale);
        frameToView.postTranslate(getWidth() / 2f + offsetX, (getHeight() + insetTop - insetBottom) / 2f + offsetY);
        sourceToView.set(EditorGeometry.sourceToFrame(state, source.getWidth(), source.getHeight()));
        sourceToView.postConcat(frameToView); sourceToView.invert(viewToSource);
        frame.set(visible); frameToView.mapRect(frame);
        available.set(margin, insetTop + margin, Math.max(margin + 1, getWidth() - margin),
                Math.max(insetTop + margin + 1, getHeight() - insetBottom - margin));
        if (pendingPose == null && !presentation.running()) presentation.direct(capturePose());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (released) return;
        if (comparing) {
            RectF target = new RectF(dp(12), insetTop + dp(12), getWidth() - dp(12), getHeight() - insetBottom - dp(12));
            Matrix matrix = new Matrix(); matrix.setRectToRect(new RectF(0, 0, source.getWidth(), source.getHeight()),
                    target, Matrix.ScaleToFit.CENTER);
            canvas.drawBitmap(source, matrix, bitmapPaint); return;
        }
        EditorPose pose = presentation.current();
        pose.imageMatrix(displayImage, source.getWidth(), source.getHeight()); pose.frameMatrix(displayFrame);
        int saved = canvas.save();
        canvas.translate(pose.x, pose.y); canvas.rotate(pose.frameAngle); canvas.clipRect(pose.clip);
        canvas.rotate(-pose.frameAngle); canvas.translate(-pose.x, -pose.y); canvas.concat(displayImage);
        drawScene(canvas);
        canvas.restoreToCount(saved);
        if (brushPreviewAlpha > 0 && !cropMode) drawBrushPreview(canvas);
        if (drawing != null && isMask(drawing.kind)) drawLiveMask(canvas);
        else if (drawing == null && drawingKind != null && isMask(drawingKind) && !cropMode && !presentation.running())
            drawMaskOutline(canvas, lastMask(drawingKind));
        if (pose.cropVisibility > 0) {
            overlayBounds.set(pose.clip); displayFrame.mapRect(overlayBounds);
            overlayBounds.union(0, 0, getWidth(), getHeight());
            int overlay = pose.cropVisibility >= 1 ? canvas.save()
                    : canvas.saveLayerAlpha(overlayBounds, Math.round(255 * pose.cropVisibility));
            canvas.concat(displayFrame); drawCrop(canvas, pose); canvas.restoreToCount(overlay);
        } else if (selected != null && !presentation.running() && pendingPose == null) drawSelection(canvas);
    }

    private void drawCrop(Canvas canvas, EditorPose pose) {
        RectF crop = pose.crop, frame = pose.clip;
        uiPaint.setStyle(Paint.Style.FILL); uiPaint.setColor(0xa0000000);
        canvas.drawRect(frame.left, frame.top, frame.right, crop.top, uiPaint);
        canvas.drawRect(frame.left, crop.bottom, frame.right, frame.bottom, uiPaint);
        canvas.drawRect(frame.left, crop.top, crop.left, crop.bottom, uiPaint);
        canvas.drawRect(crop.right, crop.top, frame.right, crop.bottom, uiPaint);
        uiPaint.setColor(0xc0000000); uiPaint.setStyle(Paint.Style.STROKE); uiPaint.setStrokeWidth(dp(3));
        canvas.drawRect(crop, uiPaint);
        uiPaint.setColor(0xffeeeeee); uiPaint.setStrokeWidth(dp(1));
        canvas.drawRect(crop, uiPaint);
        int count = 3;
        for (int pass = 0; pass < 2; pass++) {
            uiPaint.setColor(pass == 0 ? 0x60000000 : 0x90ffffff); uiPaint.setStrokeWidth(dp(pass == 0 ? 2 : 1));
            for (int i = 1; i < count; i++) {
                float x = crop.left + crop.width() * i / count, y = crop.top + crop.height() * i / count;
                canvas.drawLine(x, crop.top, x, crop.bottom, uiPaint); canvas.drawLine(crop.left, y, crop.right, y, uiPaint);
            }
        }
        if (gridStrength > 0) {
            for (int pass = 0; pass < 2; pass++) {
                uiPaint.setColor(pass == 0 ? 0x60000000 : 0x90ffffff);
                uiPaint.setAlpha(Math.round((pass == 0 ? 96 : 144) * gridStrength)); uiPaint.setStrokeWidth(dp(pass == 0 ? 2 : 1));
                for (int i = 1; i < 9; i++) if (i % 3 != 0) {
                    float x = crop.left + crop.width() * i / 9, y = crop.top + crop.height() * i / 9;
                    canvas.drawLine(x, crop.top, x, crop.bottom, uiPaint); canvas.drawLine(crop.left, y, crop.right, y, uiPaint);
                }
            }
        }
        float length = dp(16);
        for (int pass = 0; pass < 2; pass++) {
            uiPaint.setColor(pass == 0 ? 0xc0000000 : Color.WHITE); uiPaint.setStrokeWidth(dp(pass == 0 ? 6 : 4));
            for (int i = 0; i < 4; i++) {
                float x = (i & 1) == 0 ? crop.left : crop.right, y = i < 2 ? crop.top : crop.bottom;
                canvas.drawLine(x, y, x + ((i & 1) == 0 ? length : -length), y, uiPaint);
                canvas.drawLine(x, y, x, y + (i < 2 ? length : -length), uiPaint);
            }
        }
    }

    private Matrix selectionMatrix() {
        Matrix matrix = EditorRenderer.itemMatrix(selected, source.getWidth(), source.getHeight());
        matrix.postConcat(sourceToView); return matrix;
    }

    private RectF selectionBounds() {
        RectF bounds = EditorRenderer.localBounds(selected, source.getWidth(), source.getHeight(), assets);
        selectionMatrix().mapRect(bounds); return bounds;
    }

    private void updateControls() {
        Matrix matrix = selectionMatrix();
        RectF local = EditorRenderer.localBounds(selected, source.getWidth(), source.getHeight(), assets);
        float[] vector = {1, 0}; matrix.mapVectors(vector);
        float scale = Math.max(.001f, (float) Math.hypot(vector[0], vector[1]));
        local.inset(-dp(6) / scale, -dp(6) / scale);
        float halfW = Math.max(local.width() / 2, dp(32) / scale), halfH = Math.max(local.height() / 2, dp(32) / scale);
        local.set(local.centerX() - halfW, local.centerY() - halfH, local.centerX() + halfW, local.centerY() + halfH);
        float[] corners = selectionCorners;
        corners[0] = local.left; corners[1] = local.top; corners[2] = local.right; corners[3] = local.top;
        corners[4] = local.right; corners[5] = local.bottom; corners[6] = local.left; corners[7] = local.bottom;
        matrix.mapPoints(corners); selectionPath.reset(); selectionPath.moveTo(corners[0], corners[1]);
        for (int i = 2; i < 8; i += 2) selectionPath.lineTo(corners[i], corners[i + 1]); selectionPath.close();
        for (int i = 0; i < 6; i += 2) {
            controlPoints[i] = Math.max(dp(26), Math.min(getWidth() - dp(26), corners[i]));
            controlPoints[i + 1] = Math.max(insetTop + dp(26), Math.min(getHeight() - insetBottom - dp(26), corners[i + 1]));
        }
        // Keep 48dp targets separate even for small objects or clipped corners.
        boolean overlap = false;
        for (int i = 0; i < 3; i++) for (int j = i + 1; j < 3; j++)
            if (Math.hypot(controlPoints[i * 2] - controlPoints[j * 2], controlPoints[i * 2 + 1] - controlPoints[j * 2 + 1]) < dp(52)) overlap = true;
        if (overlap) {
            float cx = Math.max(dp(78), Math.min(getWidth() - dp(78), (controlPoints[0] + controlPoints[2] + controlPoints[4]) / 3));
            float cy = Math.max(insetTop + dp(26), Math.min(getHeight() - insetBottom - dp(26), controlPoints[1]));
            for (int i = 0; i < 3; i++) { controlPoints[i * 2] = cx + (i - 1) * dp(52); controlPoints[i * 2 + 1] = cy; }
        }
    }
    private void drawSelection(Canvas canvas) {
        updateControls();
        uiPaint.setStyle(Paint.Style.STROKE); uiPaint.setStrokeWidth(dp(3.5f)); uiPaint.setColor(0xc0000000);
        canvas.drawPath(selectionPath, uiPaint); uiPaint.setStrokeWidth(dp(1.5f)); uiPaint.setColor(Color.WHITE); canvas.drawPath(selectionPath, uiPaint);
        for (int i = 0; i < 3; i++) {
            float x = controlPoints[i * 2], y = controlPoints[i * 2 + 1];
            uiPaint.setStyle(Paint.Style.FILL); uiPaint.setColor(EditorPalette.SURFACE); canvas.drawCircle(x, y, dp(17), uiPaint);
            uiPaint.setStyle(Paint.Style.STROKE); uiPaint.setStrokeWidth(dp(1.5f)); uiPaint.setColor(Color.WHITE); canvas.drawCircle(x, y, dp(17), uiPaint);
            uiPaint.setStrokeWidth(dp(2)); uiPaint.setStrokeCap(Paint.Cap.ROUND);
            if (i == 0) { // delete
                canvas.drawLine(x - dp(5), y - dp(5), x + dp(5), y + dp(5), uiPaint);
                canvas.drawLine(x + dp(5), y - dp(5), x - dp(5), y + dp(5), uiPaint);
            } else if (i == 1) { // rotation arrow
                controlIcon.set(x - dp(7), y - dp(7), x + dp(7), y + dp(7)); canvas.drawArc(controlIcon, -90, 285, false, uiPaint);
                canvas.drawLine(x, y - dp(7), x - dp(4), y - dp(10), uiPaint);
                canvas.drawLine(x, y - dp(7), x - dp(4), y - dp(3), uiPaint);
            } else { // diagonal resize arrow
                canvas.drawLine(x - dp(6), y + dp(6), x + dp(6), y - dp(6), uiPaint);
                canvas.drawLine(x - dp(6), y + dp(6), x - dp(6), y + dp(1), uiPaint);
                canvas.drawLine(x - dp(6), y + dp(6), x - dp(1), y + dp(6), uiPaint);
                canvas.drawLine(x + dp(6), y - dp(6), x + dp(1), y - dp(6), uiPaint);
                canvas.drawLine(x + dp(6), y - dp(6), x + dp(6), y - dp(1), uiPaint);
            }
        }
        if (objectRotate) {
            float angle = visibleObjectAngle();
            String text = String.format(java.util.Locale.getDefault(), "%.0f°", angle);
            uiPaint.setStyle(Paint.Style.FILL); uiPaint.setTextSize(dp(14)); uiPaint.setTextAlign(Paint.Align.CENTER);
            float x = controlPoints[2], y = Math.max(insetTop + dp(22), controlPoints[3] - dp(34));
            float half = uiPaint.measureText(text) / 2 + dp(10);
            controlIcon.set(x - half, y - dp(17), x + half, y + dp(6)); uiPaint.setColor(EditorPalette.SURFACE);
            canvas.drawRoundRect(controlIcon, dp(10), dp(10), uiPaint); uiPaint.setColor(EditorPalette.TEXT); canvas.drawText(text, x, y, uiPaint);
        }
    }
    private int hitControl(float x, float y) {
        if (selected == null) return -1;
        updateControls();
        for (int i = 0; i < 3; i++) if (Math.hypot(x - controlPoints[i * 2], y - controlPoints[i * 2 + 1]) <= dp(24)) return i;
        return -1;
    }
    private float pointerAngle(float x, float y) {
        float[] center = {selected.x * source.getWidth(), selected.y * source.getHeight()}; sourceToView.mapPoints(center);
        return (float) Math.toDegrees(Math.atan2(y - center[1], x - center[0]));
    }
    private float visibleObjectAngle() {
        float[] v = {1, 0}; selectionMatrix().mapVectors(v);
        return (float) Math.toDegrees(Math.atan2(v[1], v[0]));
    }
    private void deleteSelection() {
        if (selected == null) return;
        document.begin(); document.state().items.remove(selected); document.commit(); selected = null; refresh(); listener.onChange();
    }
    private void drawBrushPreview(Canvas canvas) {
        if (drawingKind == null || isMask(drawingKind)) return;
        float[] v = {1, 0}; displayImage.mapVectors(v);
        float diameter = Math.max(1, brushSize * Math.min(source.getWidth(), source.getHeight())) * (float) Math.hypot(v[0], v[1]);
        float x = Math.max(0, Math.min(getWidth(), frame.centerX())), y = Math.max(insetTop, Math.min(getHeight() - insetBottom, frame.centerY()));
        int saved = canvas.save();
        canvas.clipRect(frame);
        uiPaint.setStyle(Paint.Style.FILL); uiPaint.setColor(color);
        uiPaint.setAlpha(Math.round(brushPreviewAlpha * (drawingKind == EditorDocument.Kind.MARKER ? 90 : 255)));
        if (drawingKind == EditorDocument.Kind.MARKER) {
            controlIcon.set(x - diameter * 1.5f, y - diameter / 2, x + diameter * 1.5f, y + diameter / 2);
            canvas.drawRoundRect(controlIcon, diameter / 2, diameter / 2, uiPaint);
        } else if (drawingKind != EditorDocument.Kind.ERASER) canvas.drawCircle(x, y, diameter / 2, uiPaint);
        uiPaint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 2; i++) {
            uiPaint.setColor(i == 0 ? Color.BLACK : Color.WHITE); uiPaint.setAlpha(Math.round(255 * brushPreviewAlpha));
            uiPaint.setStrokeWidth(dp(i == 0 ? 3 : 1));
            if (drawingKind == EditorDocument.Kind.MARKER) canvas.drawRoundRect(controlIcon, diameter / 2, diameter / 2, uiPaint);
            else canvas.drawCircle(x, y, diameter / 2, uiPaint);
        }
        uiPaint.setAlpha(255); canvas.restoreToCount(saved);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static boolean isMask(EditorDocument.Kind kind) {
        return kind == EditorDocument.Kind.COVER || kind == EditorDocument.Kind.MOSAIC || kind == EditorDocument.Kind.BLUR;
    }
    private void drawLiveMask(Canvas canvas) {
        int saved = canvas.save(); canvas.clipRect(frame); canvas.concat(sourceToView);
        RectF rect = new RectF(Math.min(drawing.x, drawing.endX) * source.getWidth(),
                Math.min(drawing.y, drawing.endY) * source.getHeight(), Math.max(drawing.x, drawing.endX) * source.getWidth(),
                Math.max(drawing.y, drawing.endY) * source.getHeight());
        if (drawing.kind == EditorDocument.Kind.COVER) {
            uiPaint.setStyle(Paint.Style.FILL); uiPaint.setColor(drawing.color | 0xff000000); canvas.drawRect(rect, uiPaint);
        }
        float[] values = new float[9]; sourceToView.getValues(values);
        float scale = (float) Math.hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]);
        uiPaint.setStyle(Paint.Style.STROKE); uiPaint.setColor(0xb0000000); uiPaint.setStrokeWidth(dp(3) / Math.max(.001f, scale));
        canvas.drawRect(rect, uiPaint); uiPaint.setColor(Color.WHITE); uiPaint.setStrokeWidth(dp(1) / Math.max(.001f, scale));
        canvas.drawRect(rect, uiPaint); canvas.restoreToCount(saved);
    }
    private void drawMaskOutline(Canvas canvas, EditorDocument.Item item) {
        if (item == null) return;
        int saved = canvas.save(); canvas.clipRect(frame); canvas.concat(sourceToView);
        RectF rect = new RectF(Math.min(item.x, item.endX) * source.getWidth(),
                Math.min(item.y, item.endY) * source.getHeight(), Math.max(item.x, item.endX) * source.getWidth(),
                Math.max(item.y, item.endY) * source.getHeight());
        float[] values = new float[9]; sourceToView.getValues(values);
        float scale = Math.max(.001f, (float) Math.hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]));
        uiPaint.setStyle(Paint.Style.STROKE); uiPaint.setColor(0xb0000000); uiPaint.setStrokeWidth(dp(3) / scale);
        canvas.drawRect(rect, uiPaint); uiPaint.setColor(EditorPalette.ACCENT); uiPaint.setStrokeWidth(dp(1) / scale);
        canvas.drawRect(rect, uiPaint); canvas.restoreToCount(saved);
    }

    private float[] sourcePoint(float x, float y) {
        float[] p = {x, y}; viewToSource.mapPoints(p);
        p[0] = Math.max(0, Math.min(1, p[0] / source.getWidth()));
        p[1] = Math.max(0, Math.min(1, p[1] / source.getHeight())); return p;
    }
    private static float distance(MotionEvent e, int a, int b) { return (float) Math.hypot(e.getX(b) - e.getX(a), e.getY(b) - e.getY(a)); }
    private static float angle(MotionEvent e, int a, int b) { return (float) Math.toDegrees(Math.atan2(e.getY(b) - e.getY(a), e.getX(b) - e.getX(a))); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!enabled || released) return true;
        int action = event.getActionMasked();
        if (ignoreUntilUp) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ignoreUntilUp = false;
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) { singlePointerId = event.getPointerId(0); settlePresentation(); directInput = true; }
        if (action == MotionEvent.ACTION_POINTER_UP && (objectResize || objectRotate || objectDelete)
                && event.getPointerId(event.getActionIndex()) == singlePointerId) {
            finishGesture(true); ignoreUntilUp = true; return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            if (twoPointers || objectResize || objectRotate || objectDelete) return true;
            handler.removeCallbacks(compareHold); comparing = false;
            if (drawing != null || cropStart != null) {
                document.cancel(); drawing = null; cropStart = null; selected = null;
                liveMaskGesture = false; invalidatePreview();
            }
            twoPointers = true; pinchPointerId0 = event.getPointerId(0); pinchPointerId1 = event.getPointerId(1);
            pinchDistance = Math.max(1, distance(event, 0, 1)); pinchAngle = angle(event, 0, 1); pinchZoom = zoom;
            objectGesture = selected != null && !cropMode && drawingKind == null;
            if (objectGesture) {
                document.begin(); objectStartScale = selected.scale; objectStartAngle = selected.angle;
            } else {
                float[] center = {(event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2};
                frameToView.invert(pinchViewToFrame); pinchViewToFrame.mapPoints(center);
                focusX = center[0]; focusY = center[1];
                if (cropMode) {
                    document.begin(); cropStart = new RectF(document.state().crop);
                    pinchOffsetX = offsetX; pinchOffsetY = offsetY;
                    pinchCropView.set(EditorGeometry.cropPixels(document.state(), source.getWidth(), source.getHeight()));
                    frameToView.mapRect(pinchCropView);
                }
            }
            refresh(); listener.onChange(); return true;
        }
        if (twoPointers) {
            if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() >= 2) {
                int a = event.findPointerIndex(pinchPointerId0), b = event.findPointerIndex(pinchPointerId1);
                if (a < 0 || b < 0) return true;
                float ratio = distance(event, a, b) / pinchDistance;
                if (objectGesture && selected != null) {
                    annotationsDirty = true;
                    selected.scale = Math.max(.15f, Math.min(8f, objectStartScale * ratio));
                    float next = angle(event, a, b);
                    float delta = (next - pinchAngle + 540) % 360 - 180; pinchAngle = next;
                    objectStartAngle += (document.state().mirror ? -1 : 1) * delta; selected.angle = objectStartAngle;
                    refresh();
                } else if (cropMode && cropStart != null) {
                    float[] center = {(event.getX(a) + event.getX(b)) / 2, (event.getY(a) + event.getY(b)) / 2};
                    pinchViewToFrame.mapPoints(center);
                    float w = EditorGeometry.width(document.state(), source.getWidth(), source.getHeight());
                    float h = EditorGeometry.height(document.state(), source.getWidth(), source.getHeight());
                    float scale = Math.max(1, Math.min(6, pinchZoom * ratio)) / pinchZoom;
                    EditorGeometry.pinchCrop(document.state(), cropStart, scale, focusX / w, focusY / h,
                            (center[0] - focusX) / w, (center[1] - focusY) / h);
                    // Keep the selection stationary on screen; the actual crop is the export model.
                    zoom = pinchZoom * cropStart.width() / document.state().crop.width(); updateMatrices();
                    RectF cropView = EditorGeometry.cropPixels(document.state(), source.getWidth(), source.getHeight());
                    frameToView.mapRect(cropView);
                    offsetX += pinchCropView.centerX() - cropView.centerX();
                    offsetY += pinchCropView.centerY() - cropView.centerY(); updateMatrices();
                } else {
                    zoom = Math.max(1, Math.min(6, pinchZoom * ratio)); updateMatrices();
                    float[] focus = {focusX, focusY}; frameToView.mapPoints(focus);
                    offsetX += (event.getX(a) + event.getX(b)) / 2 - focus[0];
                    offsetY += (event.getY(a) + event.getY(b)) / 2 - focus[1]; updateMatrices();
                }
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishGesture(action == MotionEvent.ACTION_UP);
            }
            // Never turn the remaining finger into a brush/crop stroke after a pinch.
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX(); downY = lastY = event.getY(); dragging = false; objectResize = objectRotate = objectDelete = false;
                RectF touchFrame = new RectF(frame);
                if (cropMode) touchFrame.inset(-dp(26), -dp(26));
                if (selected != null) {
                    RectF bounds = selectionBounds(); bounds.inset(-dp(26), -dp(26)); touchFrame.union(bounds);
                }
                int control = !cropMode && drawingKind == null ? hitControl(downX, downY) : -1;
                downAccepted = control >= 0 || touchFrame.contains(downX, downY); limitReported = false;
                if (!downAccepted) return true;
                float[] point = sourcePoint(downX, downY); sourceDownX = point[0]; sourceDownY = point[1];
                if (cropMode) { beginCrop(downX, downY); return true; }
                if (drawingKind != null) {
                    if (document.state().items.size() >= EditorDocument.ITEM_LIMIT
                            || document.state().pathFloatCount() + 2 > EditorDocument.TOTAL_PATH_FLOAT_LIMIT) {
                        downAccepted = false; listener.onLimit(); return true;
                    }
                    liveMaskGesture = isMask(drawingKind) || (drawingKind == EditorDocument.Kind.ERASER && drawOnComposition); if (liveMaskGesture) invalidatePreview();
                    document.begin(); drawing = new EditorDocument.Item(); drawing.kind = drawingKind;
                    drawing.color = color; drawing.size = brushSize;
                    drawing.affectAnnotations = isMask(drawingKind) && affectAnnotations;
                    drawing.drawOnComposition = EditorRenderer.isDrawing(drawingKind) && drawOnComposition;
                    drawing.x = drawing.endX = point[0]; drawing.y = drawing.endY = point[1];
                    drawing.points.add(point[0]); drawing.points.add(point[1]);
                    document.state().items.add(drawing); refresh(); return true;
                }
                objectDelete = control == 0; objectRotate = control == 1; objectResize = control == 2;
                if (!objectDelete && !objectRotate && !objectResize) selected = hitItem(downX, downY);
                if (selected != null && !objectDelete) {
                    document.begin(); objectStartX = selected.x; objectStartY = selected.y;
                    objectStartScale = selected.scale; rotateRaw = selected.angle; rotateLast = pointerAngle(downX, downY);
                } else if (selected == null) handler.postDelayed(compareHold, 450);
                invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                if (!downAccepted) return true;
                float x = event.getX(), y = event.getY();
                if (Math.hypot(x - downX, y - downY) > dp(4)) {
                    dragging = true; comparing = false; handler.removeCallbacks(compareHold);
                }
                if (cropMode) moveCrop(x, y);
                else if (drawing != null) {
                    float[] p = sourcePoint(x, y); drawing.endX = p[0]; drawing.endY = p[1];
                    if (Math.hypot(x - lastX, y - lastY) >= dp(1.5f)) {
                        if (drawing.points.size() + 2 <= EditorDocument.PATH_FLOAT_LIMIT
                                && document.state().pathFloatCount() + 2 <= EditorDocument.TOTAL_PATH_FLOAT_LIMIT) {
                            drawing.points.add(p[0]); drawing.points.add(p[1]);
                        } else if (!limitReported) { limitReported = true; listener.onLimit(); }
                    }
                    refresh();
                } else if (selected != null && document.isInTransaction()) {
                    annotationsDirty = true;
                    float[] p = sourcePoint(x, y);
                    if (objectRotate) {
                        float next = pointerAngle(x, y), delta = (next - rotateLast + 540) % 360 - 180; rotateLast = next;
                        rotateRaw += (document.state().mirror ? -1 : 1) * delta; selected.angle = rotateRaw;
                        float angle = visibleObjectAngle(), snapped = Math.round(angle / 90) * 90;
                        if (Math.abs(angle - snapped) <= 3) selected.angle += (document.state().mirror ? -1 : 1) * (snapped - angle);
                    } else if (objectResize) {
                        float[] center = {selected.x * source.getWidth(), selected.y * source.getHeight()}; sourceToView.mapPoints(center);
                        float initial = Math.max(dp(10), (float) Math.hypot(downX - center[0], downY - center[1]));
                        selected.scale = Math.max(.15f, Math.min(8, objectStartScale
                                * (float) Math.hypot(x - center[0], y - center[1]) / initial));
                    } else {
                        selected.x = Math.max(0, Math.min(1, objectStartX + p[0] - sourceDownX));
                        selected.y = Math.max(0, Math.min(1, objectStartY + p[1] - sourceDownY));
                    }
                    refresh();
                } else if (dragging && !objectDelete) { offsetX += x - lastX; offsetY += y - lastY; updateMatrices(); invalidate(); }
                lastX = x; lastY = y; return true;
            case MotionEvent.ACTION_UP:
                performClick();
                if (downAccepted && objectDelete && !dragging && hitControl(event.getX(), event.getY()) == 0) deleteSelection();
                finishGesture(true); return true;
            case MotionEvent.ACTION_CANCEL: finishGesture(false); return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    public void finishGesture(boolean commit) {
        hideBrushPreview();
        boolean wasLive = liveMaskGesture || directInput;
        handler.removeCallbacks(compareHold); comparing = false;
        if (!commit && cropMode && twoPointers && cropStart != null) {
            zoom = pinchZoom; offsetX = pinchOffsetX; offsetY = pinchOffsetY;
        }
        if (document.isInTransaction()) {
            if (commit) document.commit();
            else {
                int selectedIndex = document.state().items.indexOf(selected);
                document.cancel();
                // cancel() restores copied items; keep selection bound to the restored object.
                selected = selectedIndex >= 0 && selectedIndex < document.state().items.size()
                        ? document.state().items.get(selectedIndex) : null;
            }
        }
        ignoreUntilUp = false; drawing = null; cropStart = null; cropEdges = 0; twoPointers = false; objectGesture = false; downAccepted = false;
        directInput = false; objectResize = objectRotate = objectDelete = false;
        liveMaskGesture = false;
        if (wasLive) previewScheduler.change(commit);
        refresh(); listener.onChange();
    }

    private EditorDocument.Item hitItem(float x, float y) {
        for (int i = document.state().items.size() - 1; i >= 0; i--) {
            EditorDocument.Item item = document.state().items.get(i);
            if (item.kind != EditorDocument.Kind.TEXT && item.kind != EditorDocument.Kind.IMAGE && item.kind != EditorDocument.Kind.STICKER) continue;
            Matrix matrix = EditorRenderer.itemMatrix(item, source.getWidth(), source.getHeight());
            matrix.postConcat(sourceToView); Matrix inverse = new Matrix(); matrix.invert(inverse);
            float[] p = {x, y}; inverse.mapPoints(p);
            RectF bounds = EditorRenderer.localBounds(item, source.getWidth(), source.getHeight(), assets);
            bounds.inset(-dp(4), -dp(4));
            if (bounds.contains(p[0], p[1])) return item;
        }
        return null;
    }

    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (selected != null && !released && enabled && drawingKind == null && !cropMode) {
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS, getContext().getString(R.string.pe_delete_selected)));
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, getContext().getString(R.string.pe_rotate_clockwise)));
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, getContext().getString(R.string.pe_rotate_counterclockwise)));
        }
    }
    @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
        if (selected != null && !released && enabled && drawingKind == null && !cropMode) {
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS) { finishGesture(true); deleteSelection(); return true; }
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    || action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                finishGesture(true); document.begin();
                selected.angle += (document.state().mirror ? -1 : 1)
                        * (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 15 : -15);
                document.commit(); refresh(); listener.onChange(); return true;
            }
        }
        return super.performAccessibilityAction(action, args);
    }

    private void beginCrop(float x, float y) {
        RectF rect = EditorGeometry.cropPixels(document.state(), source.getWidth(), source.getHeight()); frameToView.mapRect(rect);
        float tolerance = dp(26);
        cropEdges = 0;
        if (Math.abs(x - rect.left) <= tolerance && y >= rect.top - tolerance && y <= rect.bottom + tolerance) cropEdges |= 1;
        if (Math.abs(x - rect.right) <= tolerance && y >= rect.top - tolerance && y <= rect.bottom + tolerance) cropEdges |= 2;
        if (Math.abs(y - rect.top) <= tolerance && x >= rect.left - tolerance && x <= rect.right + tolerance) cropEdges |= 4;
        if (Math.abs(y - rect.bottom) <= tolerance && x >= rect.left - tolerance && x <= rect.right + tolerance) cropEdges |= 8;
        // A very small crop can have overlapping handle hit areas. Pick the nearest edge.
        if ((cropEdges & 3) == 3) cropEdges &= Math.abs(x - rect.left) <= Math.abs(x - rect.right) ? ~2 : ~1;
        if ((cropEdges & 12) == 12) cropEdges &= Math.abs(y - rect.top) <= Math.abs(y - rect.bottom) ? ~8 : ~4;
        if (cropEdges == 0 && rect.contains(x, y)) cropEdges = 16;
        if (cropEdges == 0) return;
        document.begin(); cropStart = new RectF(document.state().crop); listener.onChange();
    }

    private void moveCrop(float x, float y) {
        if (cropStart == null) return;
        Matrix inverse = new Matrix(); frameToView.invert(inverse);
        float[] p = {downX, downY, x, y}; inverse.mapPoints(p);
        float w = EditorGeometry.width(document.state(), source.getWidth(), source.getHeight());
        float h = EditorGeometry.height(document.state(), source.getWidth(), source.getHeight());
        float dx = (p[2] - p[0]) / w, dy = (p[3] - p[1]) / h;
        EditorDocument.State state = document.state();
        if (cropEdges == 16) {
            EditorGeometry.setCenteredCrop(state, cropStart.centerX() + dx, cropStart.centerY() + dy, cropStart.width(), cropStart.height());
        } else EditorGeometry.resizeCrop(state, cropStart, cropEdges, dx, dy, source.getWidth(), source.getHeight());
        // The preview framing is kept stable while a crop handle is dragged.
        updateMatrices(); invalidate();
    }

    /** Source and asset lifetime is owned by the Activity's single worker, not by this View. */
    public void release() {
        released = true; previewScheduler.change(false); handler.removeCallbacks(compareHold); handler.removeCallbacks(renderRequest);
        settlePresentation();
        if (processed != null) { processed.recycle(); processed = null; }
        annotations = null;
    }
}
