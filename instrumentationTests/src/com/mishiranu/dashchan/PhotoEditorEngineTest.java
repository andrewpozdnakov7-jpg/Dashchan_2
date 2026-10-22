package com.mishiranu.dashchan;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.posting.ImageEditorActivity;
import com.mishiranu.dashchan.ui.posting.ExperimentalImageEditorActivity;
import com.mishiranu.dashchan.ui.posting.photo.EditorDocument;
import com.mishiranu.dashchan.ui.posting.photo.EditorCanvasView;
import com.mishiranu.dashchan.ui.posting.photo.EditorGeometry;
import com.mishiranu.dashchan.ui.posting.photo.EditorRenderer;
import com.mishiranu.dashchan.ui.posting.photo.EditorRulerView;
import com.mishiranu.dashchan.ui.posting.photo.EditorPose;
import com.mishiranu.dashchan.ui.posting.photo.EditorPoseTransition;
import com.mishiranu.dashchan.ui.posting.photo.EditorPreviewBlend;
import com.mishiranu.dashchan.ui.posting.photo.EditorPanelMotion;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses real Android Matrix/Bitmap/JSON. Prepared for external device execution; not run in source-only mode. */
@RunWith(AndroidJUnit4.class)
public class PhotoEditorEngineTest {
    @Test public void explicitDialogThemeOverridesLightAndDarkHostThemes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (int hostTheme : new int[] {android.R.style.Theme_Material_Light, android.R.style.Theme_Material}) {
                Context host = new android.view.ContextThemeWrapper(base, hostTheme);
                Context dialog = new android.app.AlertDialog.Builder(host, R.style.PhotoEditorDialogTheme).getContext();
                android.content.res.TypedArray colors = dialog.obtainStyledAttributes(new int[] {
                        android.R.attr.colorBackground, android.R.attr.colorBackgroundFloating,
                        android.R.attr.textColorPrimary, android.R.attr.colorAccent});
                try {
                    assertEquals(0xff1b1b1f, colors.getColor(0, 0)); assertEquals(0xff1b1b1f, colors.getColor(1, 0));
                    assertEquals(0xffe3e3e3, colors.getColor(2, 0)); assertEquals(0xffa8c7fa, colors.getColor(3, 0));
                } finally { colors.recycle(); }
            }
        });
    }

    @Test public void editorPaletteSurvivesThemeEngineWidgetPassAndNormalDialogsStillFollowTheme() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (int hostTheme : new int[] {android.R.style.Theme_Material_Light, android.R.style.Theme_Material}) {
                Context host = new android.view.ContextThemeWrapper(base, hostTheme);
                Context attached = com.mishiranu.dashchan.widget.ThemeEngine.attach(host);
                com.mishiranu.dashchan.widget.ThemeEngine.applyTheme(attached);
                int appAccent = com.mishiranu.dashchan.widget.ThemeEngine.getTheme(attached).accent;
                int ownAccent = appAccent ^ 0x00ffffff;
                Context editor = new android.app.AlertDialog.Builder(attached, R.style.PhotoEditorDialogTheme).getContext();
                android.widget.CheckBox editorCheck = new android.widget.CheckBox(editor);
                editorCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(ownAccent));
                com.mishiranu.dashchan.widget.ThemeEngine.applyStyle(editorCheck);
                assertEquals(ownAccent, editorCheck.getButtonTintList().getColorForState(
                        new int[] {android.R.attr.state_enabled, android.R.attr.state_checked}, 0));
                Context normal = new android.app.AlertDialog.Builder(attached,
                        android.R.style.Theme_Material_Dialog_Alert).getContext();
                android.widget.CheckBox normalCheck = new android.widget.CheckBox(normal);
                normalCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(ownAccent));
                com.mishiranu.dashchan.widget.ThemeEngine.applyStyle(normalCheck);
                assertEquals(appAccent, normalCheck.getButtonTintList().getColorForState(
                        new int[] {android.R.attr.state_enabled, android.R.attr.state_checked}, 0));
            }
        });
    }

    private static android.widget.TextView colorControl(View root, String label) {
        if (root instanceof android.widget.TextView && label.contentEquals(((android.widget.TextView) root).getText()))
            return (android.widget.TextView) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = colorControl(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void pickerCancelAndModeChangesDoNotAcceptOrChangeRecentColors() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            android.content.SharedPreferences prefs = context.getSharedPreferences("photo-editor-ui", Context.MODE_PRIVATE);
            String before = prefs.getString("recent-colors", null);
            AtomicInteger accepted = new AtomicInteger(), cancelled = new AtomicInteger();
            com.mishiranu.dashchan.ui.posting.photo.EditorColorPickerView picker =
                    new com.mishiranu.dashchan.ui.posting.photo.EditorColorPickerView(context, 0xff123456,
                            color -> accepted.incrementAndGet(), cancelled::incrementAndGet);
            colorControl(picker, context.getString(R.string.pe_color_circle)).performClick();
            colorControl(picker, context.getString(R.string.pe_color_grid)).performClick();
            assertNotNull(colorControl(picker, "#123456"));
            colorControl(picker, context.getString(android.R.string.cancel)).performClick();
            colorControl(picker, context.getString(R.string.pe_color_done)).performClick();
            assertEquals(0, accepted.get()); assertEquals(1, cancelled.get());
            assertEquals(before, prefs.getString("recent-colors", null));
        });
    }

    @Test public void pickerDoneAcceptsExactRgbOnceAndRejectsInvalidHex() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            android.content.SharedPreferences prefs = context.getSharedPreferences("photo-editor-ui", Context.MODE_PRIVATE);
            String before = prefs.getString("recent-colors", null);
            try {
                AtomicInteger accepted = new AtomicInteger(), rgb = new AtomicInteger();
                com.mishiranu.dashchan.ui.posting.photo.EditorColorPickerView picker =
                        new com.mishiranu.dashchan.ui.posting.photo.EditorColorPickerView(context, 0xff123456,
                                color -> { rgb.set(color); accepted.incrementAndGet(); }, () -> fail("Unexpected cancel"));
                colorControl(picker, context.getString(R.string.pe_color_circle)).performClick();
                colorControl(picker, context.getString(R.string.pe_color_grid)).performClick();
                colorControl(picker, context.getString(R.string.pe_color_hex)).performClick();
                android.widget.EditText input = hexInput(picker);
                assertNotNull(input); input.setText("#BAD");
                colorControl(picker, context.getString(R.string.pe_color_done)).performClick();
                assertEquals(0, accepted.get()); assertNotNull(input.getError());
                input.setText("#12aBcD");
                colorControl(picker, context.getString(R.string.pe_color_done)).performClick();
                colorControl(picker, context.getString(R.string.pe_color_done)).performClick();
                assertEquals(1, accepted.get()); assertEquals(0xff12abcd, rgb.get());
                assertTrue(prefs.getString("recent-colors", "").startsWith("#12ABCD"));
            } finally {
                if (before == null) prefs.edit().remove("recent-colors").apply();
                else prefs.edit().putString("recent-colors", before).apply();
            }
        });
    }

    private static android.widget.EditText hexInput(View root) {
        if (root instanceof android.widget.EditText) return (android.widget.EditText) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = hexInput(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static EditorPose pose(EditorDocument.State state) {
        int width = 640, height = 480;
        Matrix image = EditorGeometry.sourceToFrame(state, width, height);
        image.postScale(.6f, .6f); image.postTranslate(45, 70);
        RectF frame = new RectF(45, 70, 45 + EditorGeometry.width(state, width, height) * .6f,
                70 + EditorGeometry.height(state, width, height) * .6f);
        RectF crop = EditorGeometry.cropPixels(state, width, height);
        Matrix view = new Matrix(); view.setScale(.6f, .6f); view.postTranslate(45, 70); view.mapRect(crop);
        return EditorPose.capture(image, frame, crop, new RectF(0, 0, 1000, 1000), true,
                state.quarterTurns, state.mirror, width, height);
    }

    @Test public void finalPresentationMatchesExportGeometryForEveryOrientation() {
        for (boolean mirror : new boolean[] {false, true}) for (int quarter = 0; quarter < 4; quarter++) {
            EditorDocument.State state = new EditorDocument.State();
            state.mirror = mirror; state.quarterTurns = quarter; state.straighten = 17.5f;
            EditorPose pose = pose(state); Matrix actual = new Matrix(); pose.imageMatrix(actual, 640, 480);
            Matrix expected = EditorGeometry.sourceToFrame(state, 640, 480);
            expected.postScale(.6f, .6f); expected.postTranslate(45, 70);
            float[] points = {0, 0, 640, 0, 640, 480, 0, 480, 320, 240};
            float[] wanted = points.clone(); expected.mapPoints(wanted); actual.mapPoints(points);
            assertArrayEquals(wanted, points, .5f);
            Matrix frame = new Matrix(); pose.frameMatrix(frame);
            RectF mapped = new RectF(pose.clip); frame.mapRect(mapped);
            assertEquals(45, mapped.left, .5f); assertEquals(70, mapped.top, .5f);
        }
    }

    @Test public void fourRapidTurnsRetargetClockwiseWithoutTouchingDocumentHistory() throws Exception {
        for (boolean mirror : new boolean[] {false, true}) {
            EditorDocument document = new EditorDocument(); document.state().mirror = mirror;
            EditorPoseTransition transition = new EditorPoseTransition(); transition.direct(pose(document.state()));
            long oldToken = -1;
            for (int i = 0; i < 4; i++) {
                EditorPose visible = transition.current().copy();
                document.begin(); EditorGeometry.rotateQuarter(document.state()); document.commit();
                String committed = document.toJson().toString();
                long token = transition.begin(visible, pose(document.state()), 90);
                assertEquals(90 * (i + 1), transition.targetAngle(), .001f);
                assertTrue(transition.sample(token, .15f));
                assertTrue(transition.current().frameAngle > visible.frameAngle);
                if (oldToken >= 0) { assertFalse(transition.finish(oldToken)); assertFalse(transition.sample(oldToken, 1)); }
                assertEquals(committed, document.toJson().toString());
                oldToken = token;
            }
            assertTrue(transition.finish(oldToken)); assertEquals(360, transition.current().frameAngle, .001f);
            assertEquals(0, document.state().quarterTurns);
            for (int i = 0; i < 4; i++) { assertTrue(document.canUndo()); document.undo(); }
            assertFalse(document.canUndo());
        }
    }

    @Test public void thirdToFourthQuarterKeepsDirectionAndFitsEveryIntermediateFrame() {
        EditorDocument.State state = new EditorDocument.State(); state.quarterTurns = 3;
        EditorPoseTransition transition = new EditorPoseTransition(); transition.direct(pose(state));
        EditorGeometry.rotateQuarter(state);
        long token = transition.begin(transition.current().copy(), pose(state), 90);
        assertEquals(360, transition.targetAngle(), .001f);
        float previous = 270;
        for (int i = 0; i <= 100; i++) {
            assertTrue(transition.sample(token, i / 100f)); EditorPose current = transition.current();
            assertTrue(current.frameAngle >= previous); previous = current.frameAngle;
            assertTrue(current.scale > 0); assertTrue(current.crop.width() > 0 && current.crop.height() > 0);
            Matrix frame = new Matrix(); current.frameMatrix(frame);
            RectF bounds = new RectF(current.clip); frame.mapRect(bounds);
            assertTrue(bounds.left >= current.available.left - .5f && bounds.top >= current.available.top - .5f);
            assertTrue(bounds.right <= current.available.right + .5f && bounds.bottom <= current.available.bottom + .5f);
        }
    }

    @Test public void reflectionCrossfadeDoesNotSpinTheFrameOrCollapseScale() {
        for (int quarter = 0; quarter < 4; quarter++) {
            EditorDocument.State state = new EditorDocument.State(); state.quarterTurns = quarter; state.straighten = 15;
            EditorPoseTransition transition = new EditorPoseTransition(); transition.direct(pose(state));
            EditorGeometry.mirror(state);
            long token = transition.begin(transition.current().copy(), pose(state), 0);
            float angle = transition.current().frameAngle;
            for (int i = 0; i <= 20; i++) {
                transition.sample(token, i / 20f);
                assertEquals(angle, transition.current().frameAngle, .001f);
                assertTrue(transition.current().scale > 0);
            }
            assertTrue(transition.finish(token)); assertTrue(transition.current().mirror);
            assertEquals(-15, state.straighten, .001f);
        }
    }

    @Test public void transparentPreviewBlendPreservesAlphaAndOwnsNeitherBitmap() {
        Bitmap from = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Bitmap to = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Bitmap output = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        from.eraseColor(0x80ff0000); to.eraseColor(0x800000ff);
        Canvas canvas = new Canvas(output); Paint paint = new Paint();
        EditorPreviewBlend blend = new EditorPreviewBlend(); Matrix identity = new Matrix();
        try {
            blend.draw(canvas, from, identity, .5f, 8, 8, () -> canvas.drawBitmap(to, 0, 0, paint));
            int middle = output.getPixel(4, 4);
            assertEquals(128, Color.alpha(middle), 2);
            assertEquals(128, Color.red(middle), 3); assertEquals(128, Color.blue(middle), 3);
            assertEquals(0, Color.green(middle)); assertFalse(from.isRecycled()); assertFalse(to.isRecycled());
            output.eraseColor(Color.TRANSPARENT);
            blend.draw(canvas, from, identity, 1, 8, 8, () -> canvas.drawBitmap(to, 0, 0, paint));
            assertEquals(to.getPixel(4, 4), output.getPixel(4, 4));
        } finally { from.recycle(); to.recycle(); output.recycle(); }
    }

    @Test public void reflectedSnapshotStartsAtExactlyTheOldScreenPoints() {
        for (boolean mirror : new boolean[] {false, true}) for (int quarter = 0; quarter < 4; quarter++) {
            EditorDocument.State state = new EditorDocument.State();
            state.quarterTurns = quarter; state.mirror = mirror; state.straighten = 15;
            EditorPose old = pose(state); EditorPoseTransition transition = new EditorPoseTransition(); transition.direct(old);
            EditorGeometry.mirror(state); transition.begin(old, pose(state), 0);
            EditorPose visible = transition.current(); Matrix remap = new Matrix(), expected = new Matrix(), actual = new Matrix();
            EditorPreviewBlend.sourceOrientation(remap, old.angle, old.mirror, visible.angle, visible.mirror, 640, 480);
            old.imageMatrix(expected, 640, 480); visible.imageMatrix(actual, 640, 480);
            remap.postConcat(actual);
            float[] points = {0, 0, 640, 0, 640, 480, 0, 480, 320, 240}, wanted = points.clone();
            expected.mapPoints(wanted); remap.mapPoints(points); assertArrayEquals(wanted, points, .5f);
        }
    }

    @Test public void reflectionRetargetsAnInFlightFrameAndLaterTurnsStayClockwise() {
        EditorDocument.State state = new EditorDocument.State(); state.straighten = 15;
        EditorPoseTransition transition = new EditorPoseTransition(); transition.direct(pose(state));
        EditorGeometry.rotateQuarter(state);
        long rotation = transition.begin(transition.current().copy(), pose(state), 90);
        transition.sample(rotation, .4f); EditorPose visible = transition.current().copy();
        EditorGeometry.mirror(state);
        long reflection = transition.begin(visible, pose(state), 0);
        assertEquals(visible.frameAngle, transition.current().frameAngle, .001f);
        assertEquals(visible.crop, transition.current().crop);
        assertFalse(transition.finish(rotation)); assertTrue(transition.finish(reflection));
        float previous = transition.current().frameAngle;
        for (int i = 0; i < 4; i++) {
            EditorGeometry.rotateQuarter(state);
            long token = transition.begin(transition.current().copy(), pose(state), 90);
            assertEquals(previous + 90, transition.targetAngle(), .001f);
            transition.finish(token); previous += 90;
            Matrix actual = new Matrix(), expected = new Matrix();
            transition.current().imageMatrix(actual, 640, 480); pose(state).imageMatrix(expected, 640, 480);
            float[] points = {0, 0, 640, 480, 320, 240}, wanted = points.clone();
            expected.mapPoints(wanted); actual.mapPoints(points); assertArrayEquals(wanted, points, .5f);
            transition.direct(pose(state)); assertEquals(previous, transition.current().frameAngle, .001f);
        }
    }

    @Test public void interruptedPanelTransitionRestoresInputAndCompletesOnlyOnce() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            FrameLayout root = new FrameLayout(context); LinearLayout panel = new LinearLayout(context);
            Button active = new Button(context), disabled = new Button(context); disabled.setEnabled(false);
            panel.addView(active); panel.addView(disabled);
            root.addView(panel, new FrameLayout.LayoutParams(600, 100));
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 600, 800);
            EditorPanelMotion motion = new EditorPanelMotion(); AtomicInteger completions = new AtomicInteger();
            try {
                motion.capture(root, Collections.singletonList(panel));
                motion.enter(Collections.singletonList(panel), () -> completions.incrementAndGet());
                // Retarget before the first layout callback, then cancel after its animator starts.
                motion.capture(root, Collections.singletonList(panel));
                motion.enter(Collections.singletonList(panel), () -> completions.incrementAndGet());
                root.getViewTreeObserver().dispatchOnPreDraw(); motion.finish();
                root.getViewTreeObserver().dispatchOnPreDraw(); motion.finish();
                assertEquals(2, completions.get()); assertFalse(motion.isTransitioning());
                assertTrue(active.isEnabled()); assertFalse(disabled.isEnabled());
                assertEquals(1, panel.getAlpha(), .001f); assertEquals(0, panel.getTranslationY(), .001f);
            } finally { motion.release(); }
        });
    }

    @Test public void experimentalSwitchDefaultsToNewEditorAndPreservesAttachmentExtras() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_PHOTO_EDITOR);
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            try {
                Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_PHOTO_EDITOR).close();
                assertTrue(Preferences.isNewPhotoEditorEnabled());
                assertEditorIntent(context, ExperimentalImageEditorActivity.class);
                Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_PHOTO_EDITOR, true).close();
                assertEditorIntent(context, ExperimentalImageEditorActivity.class);
                Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_PHOTO_EDITOR, false).close();
                assertEditorIntent(context, ImageEditorActivity.class);
            } finally {
                if (previous instanceof Boolean) {
                    Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_PHOTO_EDITOR, (Boolean) previous).close();
                } else {
                    Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_PHOTO_EDITOR).close();
                }
            }
        });
    }

    private static void assertEditorIntent(Context context, Class<?> activity) {
        Intent intent = ImageEditorActivity.createIntent(context, "source-hash", "photo.png", 3);
        assertNotNull(intent.getComponent());
        assertEquals(activity.getName(), intent.getComponent().getClassName());
        assertEquals("source-hash", intent.getStringExtra("sourceHash"));
        assertEquals("photo.png", intent.getStringExtra("sourceName"));
        assertEquals(3, intent.getIntExtra("attachmentIndex", -1));
    }

    @Test public void obsoletePreviewCanCancelWithoutRecyclingSource() {
        Bitmap source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        AtomicInteger checks = new AtomicInteger();
        try {
            try {
                EditorRenderer.preparePhoto(source, new EditorDocument.State(), () -> checks.incrementAndGet() >= 3);
                fail("Expected cooperative cancellation");
            } catch (CancellationException expected) {
                assertFalse(source.isRecycled());
            }
        } finally { source.recycle(); }
    }

    @Test public void sessionSnapshotOwnsStateAndHistory() throws Exception {
        EditorDocument document = new EditorDocument();
        document.begin(); document.state().adjustments[0] = 17; document.commit();
        EditorDocument snapshot = document.copy();
        document.begin(); document.state().adjustments[0] = 80; document.commit();
        assertEquals(17, snapshot.state().adjustments[0]);
        assertTrue(snapshot.canUndo());
        snapshot.undo();
        assertEquals(0, snapshot.state().adjustments[0]);
        assertEquals(80, document.state().adjustments[0]);
        EditorDocument restored = EditorDocument.fromJson(snapshot.toJson());
        assertTrue(restored.canRedo()); restored.redo();
        assertEquals(17, restored.state().adjustments[0]);
    }
    @Test public void clockwiseRotationRemainsClockwiseAfterMirrorAndStraighten() {
        for (boolean mirror : new boolean[] {false, true}) for (int quarter = 0; quarter < 4; quarter++) {
            EditorDocument.State state = new EditorDocument.State();
            state.mirror = mirror; state.quarterTurns = quarter; state.straighten = 17.3f;
            state.crop.set(.12f, .23f, .78f, .89f); state.aspect = 4f / 3;
            float oldHeight = EditorGeometry.height(state, 640, 320);
            float[] before = {30, 40, 340, 100, 610, 280};
            EditorGeometry.sourceToFrame(state, 640, 320).mapPoints(before);
            EditorGeometry.rotateQuarter(state);
            float[] after = {30, 40, 340, 100, 610, 280};
            EditorGeometry.sourceToFrame(state, 640, 320).mapPoints(after);
            for (int i = 0; i < before.length; i += 2) {
                assertEquals(oldHeight - before[i + 1], after[i], .002f);
                assertEquals(before[i], after[i + 1], .002f);
            }
            assertEquals(.75f, state.aspect, .0001f);
        }
    }

    @Test public void straighteningCoversEveryOutputCorner() {
        for (int[] size : new int[][] {{640, 480}, {300, 900}, {2000, 50}}) {
            for (int quarter = 0; quarter < 4; quarter++) for (int fine : new int[] {-45, -15, 0, 12, 45}) {
                EditorDocument.State state = new EditorDocument.State(); state.quarterTurns = quarter;
                state.straighten = fine; state.mirror = true;
                float w = EditorGeometry.width(state, size[0], size[1]), h = EditorGeometry.height(state, size[0], size[1]);
                float[] corners = {0, 0, w, 0, 0, h, w, h}; Matrix inverse = new Matrix();
                assertTrue(EditorGeometry.sourceToFrame(state, size[0], size[1]).invert(inverse)); inverse.mapPoints(corners);
                for (int i = 0; i < corners.length; i += 2) {
                    assertTrue(corners[i] >= -.01f && corners[i] <= size[0] + .01f);
                    assertTrue(corners[i + 1] >= -.01f && corners[i + 1] <= size[1] + .01f);
                }
            }
        }
    }

    @Test public void fourQuarterTurnsRestoreCropAndSourceCoordinates() {
        EditorDocument.State state = new EditorDocument.State(); state.mirror = true; state.straighten = -11;
        state.crop.set(.1f, .2f, .7f, .9f); RectF crop = new RectF(state.crop);
        float[] before = {17, 23, 400, 150}; EditorGeometry.sourceToFrame(state, 600, 300).mapPoints(before);
        for (int i = 0; i < 4; i++) EditorGeometry.rotateQuarter(state);
        float[] after = {17, 23, 400, 150}; EditorGeometry.sourceToFrame(state, 600, 300).mapPoints(after);
        assertArrayEquals(before, after, .001f);
        assertEquals(crop.left, state.crop.left, .0001f); assertEquals(crop.bottom, state.crop.bottom, .0001f);
    }

    @Test public void mirrorReflectsTheCurrentOutputIncludingFineRotation() {
        EditorDocument.State state = new EditorDocument.State(); state.quarterTurns = 1; state.straighten = 21;
        state.crop.set(.1f, .2f, .7f, .9f);
        float w = EditorGeometry.width(state, 600, 300);
        float[] before = {17, 23, 400, 150}; EditorGeometry.sourceToFrame(state, 600, 300).mapPoints(before);
        EditorGeometry.mirror(state);
        float[] after = {17, 23, 400, 150}; EditorGeometry.sourceToFrame(state, 600, 300).mapPoints(after);
        for (int i = 0; i < before.length; i += 2) {
            assertEquals(w - before[i], after[i], .002f); assertEquals(before[i + 1], after[i + 1], .002f);
        }
        assertEquals(.3f, state.crop.left, .0001f); EditorGeometry.mirror(state);
        assertEquals(.1f, state.crop.left, .0001f); assertEquals(21f, state.straighten, .0001f);
    }

    @Test public void aspectPresetRemainsExactOnAVeryWideImage() {
        EditorDocument.State state = new EditorDocument.State();
        EditorGeometry.setAspect(state, .8f, 2000, 20);
        RectF crop = EditorGeometry.cropPixels(state, 2000, 20);
        assertEquals(.8f, crop.width() / crop.height(), .0001f);
        assertTrue(state.crop.left >= 0 && state.crop.right <= 1);
    }

    @Test public void everyFixedAspectCornerKeepsItsOppositeAnchor() {
        for (int[] size : new int[][] {{800, 600}, {600, 800}, {2000, 50}}) {
            for (int quarter = 0; quarter < 4; quarter++) for (float aspect : new float[] {1, 16f / 9, 9f / 16}) {
                for (int edges : new int[] {5, 6, 9, 10}) for (float delta : new float[] {-.9f, -.1f, .1f, .9f}) {
                    EditorDocument.State state = new EditorDocument.State(); state.quarterTurns = quarter;
                    state.mirror = true; state.straighten = 15; state.crop.set(.15f, .15f, .85f, .85f);
                    EditorGeometry.setAspect(state, aspect, size[0], size[1]);
                    RectF start = new RectF(state.crop);
                    EditorGeometry.resizeCrop(state, start, edges, delta, -delta / 2, size[0], size[1]);
                    assertEquals((edges & 1) != 0 ? start.right : start.left,
                            (edges & 1) != 0 ? state.crop.right : state.crop.left, .00001f);
                    assertEquals((edges & 4) != 0 ? start.bottom : start.top,
                            (edges & 4) != 0 ? state.crop.bottom : state.crop.top, .00001f);
                    assertCrop(state, size[0], size[1], aspect);
                }
            }
        }
    }

    @Test public void fixedAspectEdgesKeepOppositeEdgeAndItsMidpoint() {
        for (int edge : new int[] {1, 2, 4, 8}) for (float delta : new float[] {-2, -.1f, .1f, 2}) {
            EditorDocument.State state = new EditorDocument.State(); state.crop.set(.2f, .1f, .8f, .9f);
            EditorGeometry.setAspect(state, 16f / 9, 800, 600); RectF start = new RectF(state.crop);
            EditorGeometry.resizeCrop(state, start, edge, delta, delta, 800, 600);
            if (edge == 1 || edge == 2) {
                assertEquals(edge == 1 ? start.right : start.left, edge == 1 ? state.crop.right : state.crop.left, .00001f);
                assertEquals(start.centerY(), state.crop.centerY(), .00001f);
            } else {
                assertEquals(edge == 4 ? start.bottom : start.top, edge == 4 ? state.crop.bottom : state.crop.top, .00001f);
                assertEquals(start.centerX(), state.crop.centerX(), .00001f);
            }
            assertCrop(state, 800, 600, 16f / 9);
        }
    }

    @Test public void freeCropDoesNotAcquireAnAspectConstraint() {
        EditorDocument.State state = new EditorDocument.State(); RectF start = new RectF(.1f, .2f, .9f, .8f);
        EditorGeometry.resizeCrop(state, start, 5, .2f, .1f, 800, 600);
        assertEquals(.3f, state.crop.left, .00001f); assertEquals(.3f, state.crop.top, .00001f);
        assertEquals(.9f, state.crop.right, .00001f); assertEquals(.8f, state.crop.bottom, .00001f);
        assertEquals(0f, state.aspect, 0f);
    }

    @Test public void pinchAndPanStayInsideImageAndPreserveAspect() {
        EditorDocument.State state = new EditorDocument.State();
        EditorGeometry.setAspect(state, 16f / 9, 800, 600); RectF start = new RectF(state.crop);
        for (float scale : new float[] {.1f, 1, 2, 6}) for (float pan : new float[] {-2, 0, 2}) {
            EditorGeometry.pinchCrop(state, start, scale, .2f, .7f, pan, -pan);
            assertCrop(state, 800, 600, 16f / 9);
        }
    }

    @Test public void cropPinchIsExportedUndoableAndCancellable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); source.eraseColor(Color.BLUE);
            ExecutorService worker = Executors.newSingleThreadExecutor(); EditorDocument document = new EditorDocument();
            EditorCanvasView canvas = canvas(source, document, worker); Bitmap output = null;
            try {
                canvas.setCropMode(true); canvas.layout(0, 0, 600, 600);
                pinch(canvas, false);
                assertEquals(.5f, document.state().crop.width(), .0001f);
                assertEquals(.5f, document.state().crop.height(), .0001f);
                assertFalse(document.isInTransaction()); assertTrue(document.canUndo());
                output = EditorRenderer.export(source, document.state(), 4096, false, Collections.emptyMap());
                assertEquals(16, output.getWidth()); assertEquals(16, output.getHeight());
                document.undo(); canvas.refresh(); assertEquals(1f, document.state().crop.width(), .0001f);
                document.redo(); canvas.refresh(); assertEquals(.5f, document.state().crop.width(), .0001f);
                canvas.resetViewport(); RectF before = new RectF(document.state().crop);
                pinch(canvas, true);
                assertEquals(before.left, document.state().crop.left, .0001f);
                assertEquals(before.width(), document.state().crop.width(), .0001f);
                assertFalse(document.isInTransaction());
            } finally { canvas.release(); worker.shutdown(); if (output != null) output.recycle(); source.recycle(); }
        });
    }

    @Test public void ordinaryPinchRemainsViewOnly() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            ExecutorService worker = Executors.newSingleThreadExecutor(); EditorDocument document = new EditorDocument();
            EditorCanvasView canvas = canvas(source, document, worker);
            try {
                canvas.layout(0, 0, 600, 600); pinch(canvas, false);
                assertEquals(1f, document.state().crop.width(), 0f); assertFalse(document.canUndo());
            } finally { canvas.release(); worker.shutdown(); source.recycle(); }
        });
    }

    @Test public void mirroredRulerSyncIsSilentAndNextStepStartsAtNewAngle() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditorDocument document = new EditorDocument(); document.state().straighten = 15;
            AtomicInteger callbacks = new AtomicInteger();
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            EditorRulerView ruler = new EditorRulerView(context, -45, 45, .1f, 15, new EditorRulerView.Listener() {
                @Override public void onBegin() { document.begin(); }
                @Override public void onValue(float value) { callbacks.incrementAndGet(); document.state().straighten = value; }
                @Override public void onEnd(boolean commit) { if (commit) document.commit(); else document.cancel(); }
            });
            document.begin(); EditorGeometry.mirror(document.state()); document.commit();
            ruler.syncValue(document.state().straighten);
            assertEquals(-15f, ruler.value(), .0001f); assertEquals(0, callbacks.get());
            assertRulerRange(ruler, -15);
            ruler.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals(-14.9f, document.state().straighten, .0001f); assertEquals(1, callbacks.get());
            assertRulerRange(ruler, -14.9f);
            document.undo(); ruler.syncValue(document.state().straighten); assertEquals(-15f, ruler.value(), .0001f);
            document.undo(); ruler.syncValue(document.state().straighten); assertEquals(15f, ruler.value(), .0001f);
        });
    }

    private static void assertRulerRange(EditorRulerView ruler, float value) {
        AccessibilityNodeInfo info = ruler.createAccessibilityNodeInfo();
        assertNotNull(info);
        AccessibilityNodeInfo.RangeInfo range = info.getRangeInfo();
        assertNotNull(range); assertEquals(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, range.getType());
        assertEquals(-45f, range.getMin(), 0f); assertEquals(45f, range.getMax(), 0f);
        assertEquals(value, range.getCurrent(), .0001f);
    }

    private static EditorCanvasView canvas(Bitmap source, EditorDocument document, ExecutorService worker) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new EditorCanvasView(context, source, document, Collections.emptyMap(), worker, new EditorCanvasView.Listener() {
            @Override public void onChange() {}
            @Override public void onRenderFailure() { fail("Unexpected preview failure"); }
            @Override public void onLimit() { fail("Unexpected editor limit"); }
        });
    }

    private static void pinch(EditorCanvasView canvas, boolean cancel) {
        touch(canvas, MotionEvent.ACTION_DOWN, 250, 300);
        touch(canvas, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 250, 300, 350, 300);
        touch(canvas, MotionEvent.ACTION_MOVE, 200, 300, 400, 300);
        if (cancel) touch(canvas, MotionEvent.ACTION_CANCEL, 200, 300, 400, 300);
        else {
            touch(canvas, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200, 300, 400, 300);
            touch(canvas, MotionEvent.ACTION_MOVE, 210, 310);
            touch(canvas, MotionEvent.ACTION_UP, 210, 310);
        }
    }

    private static void touch(EditorCanvasView canvas, int action, float... points) {
        int count = points.length / 2;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords(); coords[i].x = points[i * 2]; coords[i].y = points[i * 2 + 1]; coords[i].pressure = 1; coords[i].size = 1;
        }
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, count, properties, coords, 0, 0, 1, 1, 0, 0, 0, 0);
        try { assertTrue(canvas.onTouchEvent(event)); } finally { event.recycle(); }
    }

    private static void assertCrop(EditorDocument.State state, int width, int height, float aspect) {
        assertTrue(state.crop.left >= 0 && state.crop.top >= 0 && state.crop.right <= 1 && state.crop.bottom <= 1);
        assertTrue(state.crop.width() > 0 && state.crop.height() > 0);
        RectF crop = EditorGeometry.cropPixels(state, width, height);
        // Degenerate sub-pixel selections have unavoidable float-coordinate cancellation.
        assertEquals(aspect, crop.width() / crop.height(), .02f);
    }

    @Test public void historyAndUnfinishedToolSurviveJsonRestoration() throws Exception {
        EditorDocument document = new EditorDocument();
        document.begin(); document.state().adjustments[0] = 25; document.commit();
        document.begin(); document.state().crop.set(.1f, .2f, .8f, .9f); document.commit(); document.undo();
        EditorDocument restored = EditorDocument.fromJson(new JSONObject(document.toJson().toString()));
        assertTrue(restored.canUndo()); assertTrue(restored.canRedo()); assertEquals(25, restored.state().adjustments[0]);
        restored.redo(); assertEquals(.8f, restored.state().crop.right, .0001f);
        restored.begin(); restored.state().adjustments[0] = -50;
        EditorDocument pending = EditorDocument.fromJson(new JSONObject(restored.toJson().toString()));
        assertTrue(pending.isInTransaction()); pending.cancel(); assertEquals(25, pending.state().adjustments[0]);
        pending.undo(); assertEquals(1f, pending.state().crop.right, .0001f);
    }

    @Test public void redoIsDiscardedByANewEditAndHistoryIsBounded() {
        EditorDocument document = new EditorDocument();
        for (int i = 1; i <= 30; i++) { document.begin(); document.state().adjustments[0] = i; document.commit(); }
        int count = 0; while (document.canUndo()) { document.undo(); count++; }
        assertEquals(EditorDocument.HISTORY_LIMIT, count); assertEquals(6, document.state().adjustments[0]);
        assertTrue(document.canRedo()); document.begin(); document.state().adjustments[1] = 10; document.commit();
        assertFalse(document.canRedo());
    }

    @Test public void eraserClearsOnlyAddedMarksAndSourceIsUntouched() {
        Bitmap original = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888); original.eraseColor(Color.BLUE);
        EditorDocument.State state = new EditorDocument.State();
        EditorDocument.Item pen = path(EditorDocument.Kind.PEN, .1f, .5f, .9f, .5f); pen.color = Color.RED;
        state.items.add(pen); state.items.add(path(EditorDocument.Kind.ERASER, .5f, .1f, .5f, .9f));
        Bitmap result = null;
        try {
            result = EditorRenderer.export(original, state, 64, false, Collections.emptyMap());
            assertEquals(Color.BLUE, result.getPixel(32, 32)); assertEquals(Color.RED, result.getPixel(15, 32));
            assertEquals(Color.BLUE, original.getPixel(15, 32));
        } finally { if (result != null) result.recycle(); original.recycle(); }
    }

    @Test public void pngRetainsAlphaAndJpegFlattensToOpaqueWhite() {
        Bitmap original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); original.eraseColor(Color.TRANSPARENT);
        EditorDocument.State state = new EditorDocument.State(); state.filter = EditorDocument.Filter.WARM;
        Bitmap png = null, jpeg = null;
        try {
            png = EditorRenderer.export(original, state, 4096, false, Collections.emptyMap());
            jpeg = EditorRenderer.export(original, state, 4096, true, Collections.emptyMap());
            assertEquals(16, png.getWidth()); assertEquals(0, Color.alpha(png.getPixel(8, 8)));
            assertEquals(Color.WHITE, jpeg.getPixel(8, 8));
        } finally { if (png != null) png.recycle(); if (jpeg != null) jpeg.recycle(); original.recycle(); }
    }

    @Test public void blurSupportsImmutableInputAndDoesNotModifySource() {
        int[] pixels = new int[32 * 32];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) pixels[y * 32 + x] = ((x + y) & 1) == 0 ? Color.BLACK : Color.WHITE;
        Bitmap original = Bitmap.createBitmap(pixels, 32, 32, Bitmap.Config.ARGB_8888);
        EditorDocument.State state = new EditorDocument.State(); EditorDocument.Item blur = new EditorDocument.Item();
        blur.kind = EditorDocument.Kind.BLUR; blur.endX = blur.endY = 1; blur.size = .1f; state.items.add(blur);
        Bitmap result = null;
        try {
            result = EditorRenderer.preparePhoto(original, state);
            assertEquals(Color.BLACK, original.getPixel(16, 16));
            int luminance = Color.red(result.getPixel(16, 16)); assertTrue(luminance > 20 && luminance < 235);
        } finally { if (result != null) result.recycle(); original.recycle(); }
    }

    private static EditorDocument.Item path(EditorDocument.Kind kind, float x, float y, float endX, float endY) {
        EditorDocument.Item item = new EditorDocument.Item(); item.kind = kind; item.size = .18f;
        item.x = x; item.y = y; item.endX = endX; item.endY = endY;
        item.points.add(x); item.points.add(y); item.points.add(endX); item.points.add(endY); return item;
    }
}
