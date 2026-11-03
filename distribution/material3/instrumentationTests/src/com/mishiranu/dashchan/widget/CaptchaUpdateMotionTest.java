package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Regression fixtures for actual pixels, interrupted transitions and real button availability. NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class CaptchaUpdateMotionTest {
	private static Object field(Object owner, String name) {
		try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static Map<?, ?> changes(CaptchaImageMotion owner) { return (Map<?, ?>) field(owner, "changes"); }
	private static Bitmap image(int mark) {
		Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(Color.WHITE); bitmap.setPixel(1, 1, mark); return bitmap;
	}
	private static ImageView attachedImage(PostingGestureTestActivity activity, Bitmap bitmap) {
		FrameLayout root = new FrameLayout(activity); ImageView view = new ImageView(activity);
		view.setScaleType(ImageView.ScaleType.FIT_XY); view.setImageBitmap(bitmap);
		root.addView(view, new FrameLayout.LayoutParams(64, 64)); activity.setContentView(root);
		root.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 100, 100);
		return view;
	}
	@Test public void unchangedDecodedPixelsKeepTheirDrawableAndCreateNoTransition() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			Bitmap first = image(Color.BLACK), decodedAgain = image(Color.BLACK);
			CaptchaImageMotion motion = new CaptchaImageMotion(); ImageView view = new ImageView(context);
			try {
				view.setImageBitmap(first); Drawable original = view.getDrawable();
				motion.bind(view, decodedAgain, true);
				assertSame(original, view.getDrawable()); assertTrue(changes(motion).isEmpty());
			} finally { motion.close(); view.setImageDrawable(null); first.recycle(); decodedAgain.recycle(); }
		});
	}
	@Test public void replacementNeverDimsUnchangedWhitePixelsInTheSameImage() {
		Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Bitmap old = image(Color.BLACK), next = image(Color.RED);
				CaptchaImageMotion motion = new CaptchaImageMotion(); ImageView view = attachedImage(activity, old);
				try {
					assertTrue(view.isAttachedToWindow()); motion.bind(view, next, true);
					Object change = changes(motion).get(view); assertNotNull(change);
					ValueAnimator animator = (ValueAnimator) field(change, "animator");
					Bitmap output = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
					try {
						for (float progress : new float[] {0f, .25f, .5f, .75f, 1f}) {
							animator.setCurrentFraction(progress); Canvas canvas = new Canvas(output); canvas.drawColor(Color.BLACK); view.draw(canvas);
							assertEquals(Color.WHITE, output.getPixel(48, 48)); assertEquals(1f, view.getAlpha(), 0f);
						}
					} finally { output.recycle(); }
				} finally { motion.close(); view.setImageDrawable(null); old.recycle(); next.recycle(); }
			});
		}
	}
	@Test public void detachAndCloseDiscardOldOverlaysWithoutRestoringOldContent() {
		Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Bitmap old = image(Color.BLACK), next = image(Color.RED);
				CaptchaImageMotion motion = new CaptchaImageMotion(); ImageView view = attachedImage(activity, old);
				try {
					motion.bind(view, next, true); Object change = changes(motion).get(view); assertNotNull(change);
					ValueAnimator stale = (ValueAnimator) field(change, "animator"); stale.setCurrentFraction(.4f);
					((FrameLayout) view.getParent()).removeView(view);
					assertTrue(changes(motion).isEmpty()); assertEquals(0L, field(motion, "snapshotPixels"));
					stale.setCurrentFraction(.8f); motion.close(); motion.close();
					assertSame(next, ((BitmapDrawable) view.getDrawable()).getBitmap()); assertEquals(1f, view.getAlpha(), 0f);
				} finally { motion.close(); view.setImageDrawable(null); old.recycle(); next.recycle(); }
			});
		}
	}
	@Test public void reversingDoneColorsNeverChangesRealAvailabilityOrReplaysTheOldAnimator() {
		Assume.assumeTrue(ValueAnimator.areAnimatorsEnabled());
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Context context = PostingTestContext.create(activity); LinearLayout content = new LinearLayout(context);
				content.setOrientation(LinearLayout.VERTICAL);
				CaptchaDialogAppearance appearance = new CaptchaDialogAppearance(context, content, "Select symbols");
				ScrollView scroll = new ScrollView(context); scroll.addView(content);
				AlertDialog dialog = appearance.create(scroll, (window, action) -> {}, () -> {});
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					dialog.show(); appearance.start(dialog); appearance.waiting(false, false, false);
					Button done = appearance.button(DialogInterface.BUTTON_POSITIVE);
					int initial = (Integer) field(appearance, "doneColor");
					done.setEnabled(false); appearance.waiting(true, false, false);
					ValueAnimator old = (ValueAnimator) field(appearance, "doneFade"); assertNotNull(old);
					old.setCurrentFraction(.4f); assertFalse(done.isEnabled());
					int interrupted = (Integer) field(appearance, "doneColor"); assertNotEquals(initial, interrupted);
					done.setEnabled(true); appearance.waiting(false, false, false);
					ValueAnimator current = (ValueAnimator) field(appearance, "doneFade"); assertNotNull(current);
					assertNotSame(old, current); assertEquals(interrupted, field(appearance, "doneColor"));
					old.setCurrentFraction(.9f); assertEquals(interrupted, field(appearance, "doneColor"));
					current.end(); assertTrue(done.isEnabled()); assertEquals(initial, field(appearance, "doneColor"));
					done.setEnabled(false); appearance.waiting(true, false, false); appearance.close();
					assertNull(field(appearance, "doneFade")); assertFalse(done.isEnabled());
				} finally {
					appearance.close(); dialog.dismiss();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}
}
