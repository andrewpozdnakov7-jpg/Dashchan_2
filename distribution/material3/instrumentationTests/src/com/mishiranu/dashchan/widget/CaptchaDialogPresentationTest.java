package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared native fixtures: not an executed device/network check. */
@RunWith(AndroidJUnit4.class)
public class CaptchaDialogPresentationTest {
	@Test public void fourSelectedImagesUseMeasuredWidthWithoutMovingTheTaskOrShrinkingTheSources() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			float density = context.getResources().getDisplayMetrics().density;
			Bitmap source = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888);
			source.eraseColor(Color.WHITE);
			try {
				for (int innerDp : new int[]{224, 264, 304}) {
					LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
					CaptchaDialogAppearance appearance = new CaptchaDialogAppearance(context, content, "Select all symbols in any order");
					try {
						ImageView task = appearance.taskImage(source);
						HorizontalScrollView scroll = (HorizontalScrollView) content.getChildAt(1);
						LinearLayout row = (LinearLayout) scroll.getChildAt(0);
						int width = Math.round(innerDp * density), top = -1, height = -1;
						for (int count = 0; count <= 4; count++) {
							Bitmap[] accepted = new Bitmap[count]; java.util.Arrays.fill(accepted, source);
							appearance.bindSelection(accepted, false);
							content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
									View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
							content.layout(0, 0, width, content.getMeasuredHeight());
							if (count == 0) { top = task.getTop(); height = content.getHeight(); }
							assertEquals(top, task.getTop()); assertEquals(height, content.getHeight());
							assertEquals(4, row.getChildCount());
							assertEquals(Math.round(64 * density), scroll.getHeight());
							int sum = 0, min = Integer.MAX_VALUE, max = 0;
							for (int i = 0; i < 4; i++) {
								FrameLayout slot = (FrameLayout) row.getChildAt(i);
								ImageView symbol = (ImageView) slot.getChildAt(0);
								int slotWidth = slot.getWidth(), side = Math.min(Math.round(64 * density), slotWidth);
								sum += slotWidth; min = Math.min(min, slotWidth); max = Math.max(max, slotWidth);
								assertEquals(side, symbol.getWidth()); assertEquals(side, symbol.getHeight());
								assertEquals(ImageView.ScaleType.FIT_CENTER, symbol.getScaleType());
								assertEquals(0, symbol.getPaddingLeft() + symbol.getPaddingRight());
								assertFalse(slot.getClipToOutline()); assertEquals(1f, symbol.getAlpha(), 0f);
								if (i < count) assertSame(source, ((android.graphics.drawable.BitmapDrawable) symbol.getDrawable()).getBitmap());
								else assertNull(symbol.getDrawable());
							}
							assertTrue(max - min <= 1); assertEquals(width, sum + 3 * Math.round(8 * density));
						}
						assertEquals(context.getString(R.string.captcha_dialog_selected), row.getContentDescription());
						assertEquals(128, source.getWidth()); assertEquals(96, source.getHeight());
					} finally { appearance.close(); }
				}
			} finally { source.recycle(); }
		});
	}

	@Test public void selectionLoadingUsesTheHeaderAndRefreshNeverOverlaysAcceptedImages() {
		CaptchaDialogAppearance[] owner = new CaptchaDialogAppearance[1];
		LinearLayout[] container = new LinearLayout[1];
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			container[0] = new LinearLayout(context);
			owner[0] = new CaptchaDialogAppearance(context, container[0], "Select symbols");
			owner[0].waiting(true, false, false);
			LinearLayout header = (LinearLayout) container[0].getChildAt(0);
			assertEquals(View.INVISIBLE, ((FrameLayout) header.getChildAt(1)).getChildAt(0).getVisibility());
		});
		android.os.SystemClock.sleep(350);
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try {
				LinearLayout header = (LinearLayout) container[0].getChildAt(0);
				View indicator = ((FrameLayout) header.getChildAt(1)).getChildAt(0);
				LinearLayout row = (LinearLayout) ((HorizontalScrollView) container[0].getChildAt(1)).getChildAt(0);
				assertEquals(View.VISIBLE, indicator.getVisibility()); assertEquals(4, row.getChildCount());
				owner[0].waiting(true, true, false);
				assertEquals(View.INVISIBLE, indicator.getVisibility());
				FrameLayout refresh = (FrameLayout) owner[0].button(DialogInterface.BUTTON_NEUTRAL).getParent();
				assertEquals(1f, refresh.getChildAt(2).getAlpha(), 0f);
				assertEquals(4, row.getChildCount());
				owner[0].waiting(false, false, true);
				assertEquals(View.INVISIBLE, indicator.getVisibility());
			} finally { owner[0].close(); }
		});
	}

	@Test public void symbolPickerHeightIgnoresEditorImeButKeepsPhysicalSafeAreas() {
		android.view.WindowInsets hidden = new android.view.WindowInsets.Builder()
				.setInsets(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(0, 24, 0, 32))
				.setVisible(android.view.WindowInsets.Type.systemBars(), true)
				.setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.NONE).build();
		android.view.WindowInsets shown = new android.view.WindowInsets.Builder(hidden)
				.setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, 400))
				.setVisible(android.view.WindowInsets.Type.ime(), true).build();
		assertEquals(896, CaptchaDialogAppearance.contentHeightLimit(1000, hidden, 48));
		assertEquals(896, CaptchaDialogAppearance.contentHeightLimit(1000, shown, 48));
		assertEquals(296, CaptchaDialogAppearance.contentHeightLimit(400, shown, 48));
	}
	@Test public void symbolPickerIsFocusableButNotAKeyboardTargetBeforeShowing() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			LinearLayout content = new LinearLayout(context);
			CaptchaDialogAppearance appearance = new CaptchaDialogAppearance(context, content, "Select symbols");
			android.widget.ScrollView scroll = new android.widget.ScrollView(context); scroll.addView(content);
			try {
				android.app.AlertDialog dialog = appearance.create(scroll, (window, action) -> {}, () -> {});
				assertNotNull(dialog.getWindow());
				int flags = dialog.getWindow().getAttributes().flags;
				assertNotEquals(0, flags & android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
				assertEquals(0, flags & android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
				assertFalse(android.view.WindowManager.LayoutParams.mayUseInputMethod(flags));
			} finally { appearance.close(); }
		});
	}
	@Test public void acceptedSymbolsReserveHeightAndDoNotRecolorTheOriginalTask() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
			CaptchaDialogAppearance appearance = new CaptchaDialogAppearance(context, content, "Select symbols");
			Bitmap source = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888); source.eraseColor(Color.WHITE);
			try {
				appearance.taskImage(source);
				int width = Math.round(304 * context.getResources().getDisplayMetrics().density);
				appearance.bindSelection(new Bitmap[0], false);
				content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				int empty = content.getMeasuredHeight();
				appearance.bindSelection(new Bitmap[]{source, source, source, source, source, source, source, source, source, source}, false);
				content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				assertEquals(empty, content.getMeasuredHeight());
				LinearLayout row = (LinearLayout) ((HorizontalScrollView) content.getChildAt(1)).getChildAt(0);
				assertEquals(10, row.getChildCount()); // Other challenges are never truncated to four.
				assertEquals(40, source.getWidth()); assertEquals(20, source.getHeight());
				assertEquals(Color.WHITE, source.getPixel(0, 0));
			} finally { appearance.close(); source.recycle(); }
		});
	}
	@Test public void foregroundDialogMakesLoadingCtaStaticWithoutEnablingOrSubmitting() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			Button button = new Button(context); button.setEnabled(false); button.setBackgroundTintList(null);
			FrameLayout slot = new FrameLayout(context); slot.addView(button, new FrameLayout.LayoutParams(-1, -1));
			PostingPrimaryMotion motion = new PostingPrimaryMotion(button, slot);
			try {
				motion.colors(0xff335577, Color.WHITE, 0xffdbe3ec, 0xff444444);
				motion.bind(new PostingPrimaryState.Change(PostingPrimaryState.Mode.LOADING, true, false, false, false));
				assertEquals(View.VISIBLE, slot.findViewById(R.id.posting_captcha_loading).getVisibility());
				motion.setCaptchaDialogVisible(true);
				assertEquals(View.GONE, slot.findViewById(R.id.posting_captcha_loading).getVisibility());
				assertEquals(context.getString(R.string.posting_solve_captcha), button.getContentDescription());
				assertFalse(button.isEnabled()); assertFalse(motion.canActivate());
				motion.bind(new PostingPrimaryState.Change(PostingPrimaryState.Mode.SEND, true, true, false, false));
				assertFalse(motion.canActivate()); assertFalse(button.isEnabled());
				motion.setCaptchaDialogVisible(false);
				assertEquals(context.getString(R.string.send), button.getContentDescription());
				assertFalse(button.isEnabled()); // Detached/no motion: genuine availability remains external.
			} finally { motion.close(); }
		});
	}
	@Test public void refreshAndConfirmAreDistinctNativeActions() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			CaptchaDialogAppearance appearance = new CaptchaDialogAppearance(context, new LinearLayout(context), "Select symbols");
			try {
				assertNotSame(appearance.button(DialogInterface.BUTTON_POSITIVE), appearance.button(DialogInterface.BUTTON_NEUTRAL));
				assertEquals(context.getString(R.string.captcha_dialog_done), appearance.button(DialogInterface.BUTTON_POSITIVE).getText());
				assertEquals(context.getString(R.string.refresh_captcha), appearance.button(DialogInterface.BUTTON_NEUTRAL).getContentDescription());
				appearance.waiting(true, true, false);
				assertEquals(context.getString(R.string.captcha_dialog_refreshing), appearance.button(DialogInterface.BUTTON_NEUTRAL).getStateDescription());
				appearance.waiting(false, false, true);
				assertEquals(context.getString(R.string.refresh_captcha), appearance.button(DialogInterface.BUTTON_NEUTRAL).getStateDescription());
			} finally { appearance.close(); }
		});
	}
}
