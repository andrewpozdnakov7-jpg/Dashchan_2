package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.InterfaceMotion;
import org.junit.Assume;
import org.junit.Test;

/** Prepared lifecycle/geometry cases; not a claim of testing a real IME or device motion. */
public class PostingImeMotionTest {
	private static final class InsetsRoot extends FrameLayout {
		WindowInsets suppliedInsets;
		InsetsRoot(Context context) { super(context); }
		@Override public WindowInsets getRootWindowInsets() {
			return suppliedInsets != null ? suppliedInsets : super.getRootWindowInsets();
		}
	}
	private static WindowInsets imeInsets(int bottom) {
		return new WindowInsets.Builder().setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
				.setVisible(WindowInsets.Type.ime(), bottom > 0)
				.setInsets(WindowInsets.Type.navigationBars(), Insets.NONE).build();
	}
	private static final class Fixture {
		final InsetsRoot root;
		final FrameLayout form, primary, utilitySlot;
		final EditText editor;
		final View editorContainer, tools;
		final CheckBox utility;
		final Button button;
		final PostingImeMotion motion;
		LinearLayout row;
		FrameLayout slot;
		ImageButton attachment;
		Fixture(Context context) { this(context, false); }
		Fixture(Context context, boolean editorStack) {
			root = new InsetsRoot(context); form = new FrameLayout(context);
			root.addView(form, new FrameLayout.LayoutParams(-1, -1));
			editor = new EditText(context); editor.setText("retained draft"); editor.setSelection(3);
			if (editorStack) {
				LinearLayout stack = new LinearLayout(context); stack.setOrientation(LinearLayout.VERTICAL);
				stack.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1f));
				tools = new View(context); stack.addView(tools, new LinearLayout.LayoutParams(-1, 60));
				editorContainer = stack;
			} else { editorContainer = editor; tools = null; }
			FrameLayout.LayoutParams input = new FrameLayout.LayoutParams(-1, 220); input.topMargin = 120;
			form.addView(editorContainer, input);
			primary = new FrameLayout(context); utilitySlot = new FrameLayout(context);
			utility = new CheckBox(context); utility.setChecked(true);
			utilitySlot.addView(utility); primary.addView(utilitySlot);
			button = new Button(context); button.setEnabled(true);
			FrameLayout.LayoutParams action = new FrameLayout.LayoutParams(-1, 120); action.topMargin = 360;
			form.addView(primary, action);
			FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(-1, 56); buttonParams.topMargin = 64;
			primary.addView(button, buttonParams);
			motion = new PostingImeMotion(root, form, editorContainer, primary, utilitySlot, utility);
		}
		void layout() {
			root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
			root.layout(0, 0, 480, 800);
		}
		void bindActions(Context context) {
			primary.removeView(button); row = new LinearLayout(context); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
			attachment = new ImageButton(context);
			LinearLayout.LayoutParams attach = new LinearLayout.LayoutParams(48, 48); attach.setMarginEnd(12);
			row.addView(attachment, attach); slot = new FrameLayout(context);
			slot.addView(button, new FrameLayout.LayoutParams(-1, -1));
			row.addView(slot, new LinearLayout.LayoutParams(0, 56, 1));
			FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, 56); params.topMargin = 64;
			primary.addView(row, params); motion.bindActions(row, slot, attachment, button);
		}
		void move() { move(true); }
		void move(boolean opening) {
			FrameLayout.LayoutParams input = (FrameLayout.LayoutParams) editorContainer.getLayoutParams();
			input.topMargin = opening ? 20 : 120; editorContainer.setLayoutParams(input);
			FrameLayout.LayoutParams action = (FrameLayout.LayoutParams) primary.getLayoutParams();
			action.topMargin = opening ? 240 : 360; primary.setLayoutParams(action);
			utilitySlot.setVisibility(opening ? View.GONE : View.VISIBLE);
			layout();
		}
		void assertSettled() {
			assertEquals(0f, form.getTranslationY(), 0f); assertEquals(0f, primary.getTranslationY(), 0f);
			assertEquals(1f, primary.getAlpha(), 0f); assertEquals(1f, utility.getAlpha(), 0f);
			assertEquals(1f, editorContainer.getAlpha(), 0f);
			assertEquals(0f, utility.getTranslationY(), 0f);
			assertEquals("retained draft", editor.getText().toString()); assertEquals(3, editor.getSelectionEnd());
			assertTrue(utility.isChecked()); assertTrue(button.isEnabled());
		}
	}

	@Test public void detachedFormCommitsGeometryWithoutMotionOrChangingControls() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(PostingTestContext.create());
			try {
				f.layout(); f.motion.onPreDraw(); f.motion.begin(); f.move(); f.motion.onPreDraw();
				f.assertSettled(); assertEquals(20, f.editor.getTop()); assertEquals(View.GONE, f.utilitySlot.getVisibility());
			} finally { f.motion.close(); }
		});
	}
	@Test public void geometryReadinessUsesMeasuredBoundsNotTheRootRequestFlag() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(PostingTestContext.create());
			try {
				f.layout(); f.root.requestLayout(); assertTrue(f.root.isLayoutRequested());
				assertTrue(PostingImeMotion.hasMeasuredBounds(f.root, f.form, f.editor, f.primary));
				FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) f.editor.getLayoutParams();
				params.height = 260; f.editor.setLayoutParams(params);
				f.root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
				assertFalse(PostingImeMotion.hasMeasuredBounds(f.root, f.form, f.editor, f.primary));
				f.root.layout(0, 0, 480, 800);
				assertTrue(PostingImeMotion.hasMeasuredBounds(f.root, f.form, f.editor, f.primary));
				f.assertSettled();
			} finally { f.motion.close(); }
		});
	}

	@Test public void interruptionAndReversalRestoreLivePoseWithoutChangingDraftOrCheckbox() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Fixture f = new Fixture(activity);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					activity.setContentView(f.root); f.layout();
					Assume.assumeTrue(f.root.isAttachedToWindow() && f.root.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
					f.motion.onPreDraw(); f.motion.begin(); f.move();
					f.root.requestLayout(); assertTrue(f.root.isLayoutRequested());
					f.motion.onPreDraw(); // Settled local bounds remain valid with a pending root request.
					assertEquals(100f, f.form.getTranslationY(), 0f); assertTrue(f.button.isEnabled());
					f.motion.finish(); f.assertSettled();
					f.motion.begin(); f.utilitySlot.setVisibility(View.VISIBLE); f.layout(); f.motion.onPreDraw();
					f.motion.begin(); f.utilitySlot.setVisibility(View.GONE); f.layout(); f.motion.onPreDraw();
					f.motion.finish(); f.assertSettled();
					f.motion.close(); f.motion.onPreDraw(); f.assertSettled();
				} finally {
					f.motion.close();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit()
							.put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}

	@Test public void systemImeFramesOwnMotionAndEndSettlesWithoutAFollowupAnimator() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Fixture f = new Fixture(activity); f.bindActions(activity);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					activity.setContentView(f.root); f.layout();
					Assume.assumeTrue(f.root.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
					f.motion.onPreDraw();
					WindowInsetsAnimation animation = new WindowInsetsAnimation(WindowInsets.Type.ime(), new LinearInterpolator(), 250);
					f.root.dispatchWindowInsetsAnimationPrepare(animation); f.move();
					animation.setFraction(.5f);
					f.root.dispatchWindowInsetsAnimationProgress(new WindowInsets.Builder().build(), java.util.Collections.singletonList(animation));
					f.motion.onPreDraw();
					assertEquals(50f, f.form.getTranslationY(), 1f);
					assertEquals(0f, f.attachment.getAlpha(), 0f);
					f.root.dispatchWindowInsetsAnimationEnd(animation); f.assertSettled();
					assertEquals(1f, f.attachment.getAlpha(), 0f);
					assertEquals(f.row.getWidth() - 60, f.slot.getWidth());
					f.motion.onPreDraw(); f.assertSettled();
				} finally {
					f.motion.close();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}

	@Test public void lateFirstPresentationUsesFinalLayoutEdgeAndDoesNotJumpAtImeEnd() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					for (boolean opening : new boolean[]{true, false}) {
						Fixture f = new Fixture(activity); f.bindActions(activity);
						try {
							activity.setContentView(f.root); f.layout();
							Assume.assumeTrue(f.root.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
							int startInset = opening ? 0 : 300, endInset = opening ? 300 : 0;
							f.root.suppliedInsets = imeInsets(startInset); f.move(!opening); f.motion.onPreDraw();
							int[] old = new int[2], target = new int[2], shown = new int[2];
							f.slot.getLocationInWindow(old);
							WindowInsetsAnimation animation = new WindowInsetsAnimation(WindowInsets.Type.ime(), new LinearInterpolator(), 250);
							f.root.dispatchWindowInsetsAnimationPrepare(animation);
							f.root.suppliedInsets = imeInsets(endInset); f.move(opening); f.slot.getLocationInWindow(target);
							f.root.dispatchWindowInsetsAnimationStart(animation, new WindowInsetsAnimation.Bounds(Insets.NONE, Insets.of(0, 0, 0, 300)));
							// Resize/alignment may delay the first usable pre-draw until progress has begun.
							animation.setFraction(.25f);
							f.root.dispatchWindowInsetsAnimationProgress(imeInsets(opening ? 75 : 225), java.util.Collections.singletonList(animation));
							f.motion.onPreDraw(); f.slot.getLocationInWindow(shown);
							assertEquals(old[1] + (target[1] - old[1]) * .25f, shown[1], 1f);
							animation.setFraction(1f);
							f.root.dispatchWindowInsetsAnimationProgress(imeInsets(endInset), java.util.Collections.singletonList(animation));
							f.slot.getLocationInWindow(shown); assertEquals(target[1], shown[1]);
							f.root.dispatchWindowInsetsAnimationEnd(animation);
							int[] settled = new int[2]; f.slot.getLocationInWindow(settled);
							assertEquals(shown[1], settled[1]); f.assertSettled();
						} finally { f.motion.close(); }
					}
				} finally {
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}

	@Test public void preparedSystemTimelineHoldsTheVisiblePoseBeforeItsFirstProgress() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Fixture f = new Fixture(activity); f.bindActions(activity);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					activity.setContentView(f.root); f.layout();
					Assume.assumeTrue(f.root.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
					int[] old = new int[2]; f.slot.getLocationInWindow(old);
					boolean clip = f.form.getClipChildren();
					f.motion.onPreDraw();
					WindowInsetsAnimation animation = new WindowInsetsAnimation(WindowInsets.Type.ime(), new LinearInterpolator(), 250);
					f.root.dispatchWindowInsetsAnimationPrepare(animation); f.move();
					// This ordering used to clear the system owner and launch a 260ms fallback.
					f.motion.onPreDraw();
					int[] shown = new int[2]; f.slot.getLocationInWindow(shown);
					assertEquals(old[1], shown[1]); assertEquals(1f, f.primary.getAlpha(), 0f);
					assertEquals(0f, f.row.getAlpha(), 0f); // The live slot is presented once in the window overlay.
					animation.setFraction(.5f);
					f.root.dispatchWindowInsetsAnimationProgress(new WindowInsets.Builder().build(), java.util.Collections.singletonList(animation));
					f.slot.getLocationInWindow(shown); assertEquals(old[1] - 60, shown[1]);
					f.root.dispatchWindowInsetsAnimationEnd(animation);
					f.assertSettled(); assertEquals(1f, f.row.getAlpha(), 0f);
					assertEquals(clip, f.form.getClipChildren()); assertFalse(f.motion.isTransitionRunning());
				} finally {
					f.motion.close();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}

	@Test public void editorAndFormattingFollowTheSameFramesAndRestoreTheirNaturalBounds() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Fixture f = new Fixture(activity, true); f.bindActions(activity);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					activity.setContentView(f.root); f.layout();
					Assume.assumeTrue(f.root.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
					f.motion.onPreDraw();
					WindowInsetsAnimation animation = new WindowInsetsAnimation(WindowInsets.Type.ime(), new LinearInterpolator(), 250);
					f.root.dispatchWindowInsetsAnimationPrepare(animation);
					f.editorContainer.getLayoutParams().height = 120; f.move();
					f.motion.onPreDraw();
					assertEquals(220, f.editorContainer.getHeight()); assertEquals(160, f.tools.getTop());
					animation.setFraction(.5f);
					f.root.dispatchWindowInsetsAnimationProgress(new WindowInsets.Builder().build(), java.util.Collections.singletonList(animation));
					assertEquals(170, f.editorContainer.getHeight()); assertEquals(110, f.tools.getTop());
					f.motion.prepareLayout(); // Existing sizing/scroll code must read the final native model.
					assertEquals(120, f.editorContainer.getHeight()); assertEquals(60, f.tools.getTop());
					assertEquals(120, f.editorContainer.getLayoutParams().height);
					f.motion.onPreDraw(); f.motion.finish();
					assertEquals(120, f.editorContainer.getHeight()); assertEquals(60, f.tools.getTop()); f.assertSettled();
				} finally {
					f.motion.close();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}

	@Test public void interruptedMovingAttachmentTapCannotActivateThePrimaryAction() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Fixture f = new Fixture(activity); f.bindActions(activity);
				int[] picks = {0}, sends = {0}; f.attachment.setOnClickListener(v -> picks[0]++); f.button.setOnClickListener(v -> sends[0]++);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					activity.setContentView(f.root); f.layout();
					Assume.assumeTrue(f.root.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
					f.motion.onPreDraw(); f.motion.begin(); f.move(); f.motion.onPreDraw();
					int[] location = new int[2]; f.attachment.getLocationOnScreen(location);
					MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, location[0] + 24, location[1] + 24, 0);
					MotionEvent up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, location[0] + 24, location[1] + 24, 0);
					try { assertTrue(f.motion.onTouchEvent(down)); assertTrue(f.motion.onTouchEvent(up)); }
					finally { down.recycle(); up.recycle(); }
					assertEquals(1, picks[0]); assertEquals(0, sends[0]); f.assertSettled();
					assertEquals(1f, f.attachment.getAlpha(), 0f); assertEquals(f.row.getWidth() - 60, f.slot.getWidth());
				} finally {
					f.motion.close();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}

	private static Bitmap captureRootWithDecorOverlays(View root) {
		View decor = root.getRootView(); int[] at = new int[2], origin = new int[2];
		root.getLocationInWindow(at); decor.getLocationInWindow(origin);
		Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap); canvas.translate(origin[0] - at[0], origin[1] - at[1]);
		SnapshotDrawing.draw(decor, canvas); return bitmap;
	}

	@Test public void nativeRemeasureCannotClipAwayTheFormattingRowDuringOpeningOrClosing() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					for (boolean opening : new boolean[]{false, true}) {
						Fixture f = new Fixture(activity, true); f.tools.setBackgroundColor(Color.MAGENTA);
						try {
							f.editorContainer.getLayoutParams().height = opening ? 500 : 220;
							activity.setContentView(f.root); f.layout();
							Assume.assumeTrue(f.root.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
							f.motion.onPreDraw();
							WindowInsetsAnimation animation = new WindowInsetsAnimation(WindowInsets.Type.ime(), new LinearInterpolator(), 250);
							f.root.dispatchWindowInsetsAnimationPrepare(animation);
							f.editorContainer.getLayoutParams().height = opening ? 220 : 420; f.move(opening); f.motion.onPreDraw();
							// A later native layout moves the editor below a resized subject/toolbar
							// and refines its height while the same IME timeline is still active.
							f.motion.prepareLayout();
							FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) f.editorContainer.getLayoutParams();
							params.topMargin += 60; params.height += 40; f.editorContainer.setLayoutParams(params); f.layout();
							animation.setFraction(.5f);
							f.root.dispatchWindowInsetsAnimationProgress(new WindowInsets.Builder().build(), java.util.Collections.singletonList(animation));
							int[] at = new int[2], origin = new int[2];
							f.editorContainer.getLocationInWindow(at); f.root.getLocationInWindow(origin);
							int x = at[0] - origin[0] + f.editorContainer.getWidth() / 2;
							int y = at[1] - origin[1] + f.tools.getTop() + f.tools.getHeight() / 2;
							Bitmap bitmap = captureRootWithDecorOverlays(f.root);
							try { assertEquals("The formatting row must remain painted after native remeasurement", Color.MAGENTA, bitmap.getPixel(x, y)); }
							finally { bitmap.recycle(); }
							animation.setFraction(1f);
							f.root.dispatchWindowInsetsAnimationProgress(new WindowInsets.Builder().build(), java.util.Collections.singletonList(animation));
							assertEquals(params.height, f.editorContainer.getHeight());
							assertEquals(params.height - f.tools.getHeight(), f.tools.getTop());
							f.root.dispatchWindowInsetsAnimationEnd(animation); f.assertSettled();
						} finally { f.motion.close(); }
					}
				} finally {
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}

	@Test public void editorOverlayStillRespectsTheRealScrollViewport() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Fixture f = new Fixture(activity, true); f.tools.setBackgroundColor(Color.MAGENTA);
				android.widget.ScrollView viewport = new android.widget.ScrollView(activity);
				f.root.removeView(f.form); viewport.addView(f.form, new android.widget.ScrollView.LayoutParams(-1, 800));
				f.root.addView(viewport, new FrameLayout.LayoutParams(-1, 400));
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					f.editorContainer.getLayoutParams().height = 500; activity.setContentView(f.root); f.layout();
					Assume.assumeTrue(f.root.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
					f.motion.onPreDraw();
					WindowInsetsAnimation animation = new WindowInsetsAnimation(WindowInsets.Type.ime(), new LinearInterpolator(), 250);
					f.root.dispatchWindowInsetsAnimationPrepare(animation);
					f.editorContainer.getLayoutParams().height = 600; f.move(false); f.motion.onPreDraw();
					animation.setFraction(.5f);
					f.root.dispatchWindowInsetsAnimationProgress(new WindowInsets.Builder().build(), java.util.Collections.singletonList(animation));
					int[] at = new int[2], origin = new int[2];
					f.editorContainer.getLocationInWindow(at); f.root.getLocationInWindow(origin);
					int x = at[0] - origin[0] + f.editorContainer.getWidth() / 2;
					int y = at[1] - origin[1] + f.tools.getTop() + f.tools.getHeight() / 2;
					assertTrue(y > viewport.getBottom());
					Bitmap bitmap = captureRootWithDecorOverlays(f.root);
					try { assertNotEquals("The overlay must not paint formatting outside the scroll viewport", Color.MAGENTA, bitmap.getPixel(x, y)); }
					finally { bitmap.recycle(); }
					f.motion.finish(); f.assertSettled();
				} finally {
					f.motion.close();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}
}
