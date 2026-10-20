package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.InterfaceMotion;
import org.junit.Assume;
import org.junit.Test;

/** Prepared lifecycle/geometry cases; not a claim of testing a real IME or device motion. */
public class PostingImeMotionTest {
	private static final class Fixture {
		final FrameLayout root, form, primary, utilitySlot;
		final EditText editor;
		final CheckBox utility;
		final Button button;
		final PostingImeMotion motion;
		Fixture(Context context) {
			root = new FrameLayout(context); form = new FrameLayout(context);
			root.addView(form, new FrameLayout.LayoutParams(-1, -1));
			editor = new EditText(context); editor.setText("retained draft"); editor.setSelection(3);
			FrameLayout.LayoutParams input = new FrameLayout.LayoutParams(-1, 220); input.topMargin = 120;
			form.addView(editor, input);
			primary = new FrameLayout(context); utilitySlot = new FrameLayout(context);
			utility = new CheckBox(context); utility.setChecked(true);
			utilitySlot.addView(utility); primary.addView(utilitySlot);
			button = new Button(context); button.setEnabled(true);
			FrameLayout.LayoutParams action = new FrameLayout.LayoutParams(-1, 120); action.topMargin = 360;
			form.addView(primary, action);
			FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(-1, 56); buttonParams.topMargin = 64;
			primary.addView(button, buttonParams);
			motion = new PostingImeMotion(root, form, editor, primary, utilitySlot, utility);
		}
		void layout() {
			root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
			root.layout(0, 0, 480, 800);
		}
		void move() {
			FrameLayout.LayoutParams input = (FrameLayout.LayoutParams) editor.getLayoutParams();
			input.topMargin = 20; editor.setLayoutParams(input);
			FrameLayout.LayoutParams action = (FrameLayout.LayoutParams) primary.getLayoutParams();
			action.topMargin = 240; primary.setLayoutParams(action); utilitySlot.setVisibility(View.GONE);
			layout();
		}
		void assertSettled() {
			assertEquals(0f, form.getTranslationY(), 0f); assertEquals(0f, primary.getTranslationY(), 0f);
			assertEquals(1f, primary.getAlpha(), 0f); assertEquals(1f, utility.getAlpha(), 0f);
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
}
