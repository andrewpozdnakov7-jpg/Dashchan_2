package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.InterfaceMotion;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

/** Prepared native fixtures, not an executed device check. */
@RunWith(AndroidJUnit4.class)
public class PostingPrimaryPresentationTest {
	@Test public void activeSuccessFeedbackDoesNotBlockReadyClickOrEnableInvalidForm() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Context context = PostingTestContext.create(activity);
				Button button = new Button(context); button.setEnabled(false); button.setBackgroundTintList(null);
				FrameLayout slot = new FrameLayout(context); slot.addView(button, new FrameLayout.LayoutParams(-1, -1));
				PostingPrimaryMotion motion = new PostingPrimaryMotion(button, slot);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					activity.setContentView(slot);
					motion.colors(0xff335577, Color.WHITE, 0xffdbe3ec, 0xff444444);
					PostingPrimaryState state = new PostingPrimaryState();
					motion.bind(state.bind(PostingPrimaryState.Mode.SOLVE, false, false));
					float density = context.getResources().getDisplayMetrics().density;
					int width = Math.round(304 * density), height = Math.round(56 * density);
					slot.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
							View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
					slot.layout(0, 0, width, height);
					Assume.assumeTrue(slot.isAttachedToWindow() && slot.isShown() && InterfaceMotion.duration(1) > 0);
					int[] clicks = {0}, availabilityCallbacks = {0}; boolean[] realReady = {false};
					motion.setAvailabilityChanged(() -> availabilityCallbacks[0]++);
					button.setOnClickListener(view -> {
						// Simulate the existing form validation and synchronous send-button lock; no real post/service.
						if (!button.isEnabled() || !realReady[0] || !motion.canActivate()) return;
						motion.finishContentTransition(); clicks[0]++; realReady[0] = false;
						button.setEnabled(false); motion.enabledChanged();
					});
					motion.bind(state.bind(PostingPrimaryState.Mode.SEND, true, false));
					assertEquals(context.getString(R.string.posting_captcha_done),
							((TextView) slot.findViewById(R.id.posting_captcha_status)).getText().toString());
					assertTrue(motion.canActivate()); assertFalse(button.isEnabled());
					button.performClick(); assertEquals(0, clicks[0]);
					realReady[0] = true; button.setEnabled(true); motion.enabledChanged();
					assertTrue(motion.canActivate());
					button.performClick(); assertEquals(1, clicks[0]); assertFalse(button.isEnabled());
					button.performClick(); assertEquals(1, clicks[0]);
					motion.finish(); assertEquals(1, clicks[0]); assertEquals(0, availabilityCallbacks[0]);
				} finally {
					motion.close();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit()
							.put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}

	@Test public void translucentIntermediateFromCrashIsNormalizedBeforeContrastCalculation() {
		int opaqueResult = PostingFormAppearance.primaryBackground(0xff5679a1);
		for (int alpha : new int[] {0, 127, 254, 255}) {
			int result = PostingFormAppearance.primaryBackground((alpha << 24) | 0x005679a1);
			assertEquals(opaqueResult, result);
			assertEquals(255, Color.alpha(result));
			assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, result) >= 4.5);
		}
		assertEquals(0xff224466, PostingFormAppearance.primaryBackground(0xfe224466));
	}

	@Test public void animationBlendKeepsRgbButNeverTruncatesOpaqueAlpha() {
		int from = 0xff234567, to = 0xff345678;
		for (int i = 0; i <= 10000; i++) {
			float fraction = i / 10000f; // Includes0.0008f: float alpha can become254.99998.
			int raw = androidx.core.graphics.ColorUtils.blendARGB(from, to, fraction);
			int result = PostingFormAppearance.blendPrimaryBackground(from, to, fraction);
			assertEquals(raw & 0x00ffffff, result & 0x00ffffff);
			assertEquals(255, Color.alpha(result));
			assertEquals(result, PostingFormAppearance.primaryBackground(result));
		}
		assertEquals(from, PostingFormAppearance.blendPrimaryBackground(from, to, 0f));
		assertEquals(to, PostingFormAppearance.blendPrimaryBackground(from, to, 1f));
	}

	@Test public void primaryBackgroundPreservesLightForegroundWithoutChangingAnAlreadyReadableAccent() {
		for (int accent : new int[] {0xff517fae, 0xffffff00, 0xffe8c7ff, 0xff224466, 0xff000000}) {
			int fill = PostingFormAppearance.primaryBackground(accent);
			assertEquals(255, Color.alpha(fill));
			assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, fill) >= 4.5);
			if (androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, accent) >= 4.5) assertEquals(accent, fill);
		}
	}

	@Test public void softenedStageFillsRemainThemeBasedAndReadableWithWhite() {
		for (int accent : new int[] {0xff517fae, 0xffaa3355, 0xff228844, 0xffffff00, 0xff000000}) {
			int primary = PostingFormAppearance.primaryBackground(accent);
			for (float saturation : new float[] {.65f, .85f}) {
				int stage = PostingPrimaryMotion.stageFill(primary, saturation);
				assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, stage) >= 4.5);
				float[] before = new float[3], after = new float[3];
				androidx.core.graphics.ColorUtils.colorToHSL(primary, before);
				androidx.core.graphics.ColorUtils.colorToHSL(stage, after);
				if (before[1] > .05f && after[1] > .05f) {
					float difference = Math.abs(before[0] - after[0]);
					assertTrue(Math.min(difference, 360f - difference) < 3f);
				}
			}
		}
	}

	@Test public void disabledAnimationsPreserveTheNativePressedStateMaskAndCancelResetsBothShapes() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			Button button = new Button(context); button.setBackgroundTintList(null); button.setEnabled(true);
			FrameLayout slot = new FrameLayout(context); slot.addView(button, new FrameLayout.LayoutParams(-1, -1));
			PostingPrimaryMotion motion = new PostingPrimaryMotion(button, slot); // Detached: no motion, final states only.
			try {
				motion.colors(0xff335577, Color.WHITE, 0xffdbe3ec, 0xff444444);
				button.getBackground().mutate();
				button.setPressed(true);
				float density = context.getResources().getDisplayMetrics().density;
				android.graphics.drawable.GradientDrawable fill = (android.graphics.drawable.GradientDrawable) slot.getBackground();
				android.graphics.drawable.GradientDrawable mask = (android.graphics.drawable.GradientDrawable)
						((android.graphics.drawable.RippleDrawable) button.getBackground()).findDrawableByLayerId(android.R.id.mask);
				assertEquals(Math.round(16 * density), fill.getCornerRadius(), .01f);
				assertEquals(fill.getCornerRadius(), mask.getCornerRadius(), .01f);
				button.setEnabled(false);
				assertEquals(Math.round(28 * density), fill.getCornerRadius(), .01f);
				motion.finish(); assertFalse(button.isPressed()); assertTrue(motion.canActivate());
				assertEquals(fill.getCornerRadius(), mask.getCornerRadius(), .01f);
			} finally { motion.close(); }
		});
	}

	@Test public void mutatedButtonBackgroundDoesNotDetachTheFillAndLoadingHasNoVisibleCaption() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			Button button = new Button(context); button.setBackgroundTintList(null); button.setEnabled(false);
			FrameLayout slot = new FrameLayout(context); slot.addView(button, new FrameLayout.LayoutParams(-1, -1));
			PostingPrimaryMotion motion = new PostingPrimaryMotion(button, slot);
			try {
				int accent = PostingFormAppearance.primaryBackground(0xff517fae);
				motion.colors(accent, Color.WHITE, 0xffdbe3ec, 0xff444444);
				PostingPrimaryState state = new PostingPrimaryState();
				motion.bind(state.bind(PostingPrimaryState.Mode.LOADING, false, false));
				button.getBackground().mutate(); motion.enabledChanged();
				assertEquals(View.GONE, slot.findViewById(R.id.posting_captcha_status).getVisibility());
				View indicator = slot.findViewById(R.id.posting_captcha_loading);
				assertEquals(View.VISIBLE, indicator.getVisibility()); assertFalse(button.isEnabled());
				assertEquals(context.getString(R.string.posting_primary_loading), button.getContentDescription());
				float density = context.getResources().getDisplayMetrics().density;
				int width = Math.round(368 * density), height = Math.round(56 * density);
				slot.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
				slot.layout(0, 0, width, height);
				assertTrue(Math.abs(indicator.getLeft() + ((View) indicator.getParent()).getLeft()
						+ indicator.getWidth() / 2f - width / 2f) <= 1f);
				Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
				try {
					slot.getBackground().setBounds(0, 0, width, height);
					slot.getBackground().draw(new Canvas(bitmap));
					assertEquals(PostingPrimaryMotion.stageFill(accent, .85f), bitmap.getPixel(width / 2, height / 2));
				} finally { bitmap.recycle(); }
				motion.bind(state.bind(PostingPrimaryState.Mode.SEND, true, true));
				assertEquals(View.VISIBLE, slot.findViewById(R.id.posting_captcha_status).getVisibility());
				assertEquals(View.GONE, indicator.getVisibility()); assertFalse(button.isEnabled());
			} finally { motion.close(); }
		});
	}
}
