package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.posting.PostingAttachmentState;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared rendering fixtures, not a device/IME verification claim. */
@RunWith(AndroidJUnit4.class)
public class PostingAttachmentBadgeTest {
	private PostingAttachmentButton button(boolean rtl, float fontScale) {
		return button(rtl, fontScale, 0);
	}

	private PostingAttachmentButton button(boolean rtl, float fontScale, int densityDpi) {
		Context base = PostingTestContext.create();
		Configuration configuration = new Configuration(base.getResources().getConfiguration());
		configuration.fontScale = fontScale;
		if (densityDpi > 0) configuration.densityDpi = densityDpi;
		PostingAttachmentButton button = new PostingAttachmentButton(base.createConfigurationContext(configuration));
		button.setImageResource(R.drawable.ic_attach_file_edited);
		button.setBackground(null);
		button.setColors(0xff335577, 0xff335577, Color.WHITE);
		button.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
		int size = Math.round(48 * button.getResources().getDisplayMetrics().density);
		button.layout(0, 0, size, size);
		return button;
	}

	private Bitmap render(PostingAttachmentButton button, float fraction, boolean live) {
		Bitmap bitmap = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		if (live) button.draw(canvas);
		else button.drawContents(canvas, button.getWidth(), button.getHeight(), fraction);
		return bitmap;
	}

	@Test public void identicalCountGetsDifferentAccessibleActionsAndALowerPlusOnlyWhenThereIsRoom() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean rtl : new boolean[]{false, true}) {
				PostingAttachmentButton button = button(rtl, 1f);
				button.setPresentation(new PostingAttachmentState(1, 4, false), true);
				String add = button.getContentDescription().toString();
				Bitmap free = render(button, 1, false);
				button.setPresentation(new PostingAttachmentState(1, 1, false), true);
				String show = button.getContentDescription().toString();
				Bitmap full = render(button, 1, false);
				try {
					assertNotEquals(add, show);
					assertTrue(add.startsWith(button.getContext().getString(R.string.attach)));
					assertTrue(show.startsWith(button.getContext().getString(R.string.posting_show_attachments)));
					assertFalse(free.sameAs(full));
					float density = button.getResources().getDisplayMetrics().density;
					int top = Math.round(4 * density), bottom = Math.round(20 * density);
					int left = Math.round((rtl ? 4 : 28) * density), right = Math.round((rtl ? 20 : 44) * density);
					for (int y = top; y < bottom; y++) for (int x = left; x < right; x++)
						assertEquals("The same count must keep the same badge", free.getPixel(x, y), full.getPixel(x, y));
				} finally { free.recycle(); full.recycle(); }
			}
		});
	}

	@Test public void liveAndFlightSharePixelsAtTheFinalPoseInRtlAndLargeFonts() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean rtl : new boolean[]{false, true}) for (float scale : new float[]{1f, 2.5f}) {
				PostingAttachmentButton button = button(rtl, scale);
				button.setPresentation(new PostingAttachmentState(12, 20, false), true);
				Bitmap live = render(button, 1, true), flight = render(button, 1, false);
				try { assertTrue(live.sameAs(flight)); }
				finally { live.recycle(); flight.recycle(); }
			}
		});
	}

	@Test public void emptyFormUsesTheOriginalDrawableAtEveryBadgeAlphaAndKeyboardPosition() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean rtl : new boolean[]{false, true}) {
				PostingAttachmentButton button = button(rtl, 1f);
				button.setPresentation(new PostingAttachmentState(0, 4, false), false);
				Bitmap ordinary = render(button, 0, false);
				try {
					for (boolean keyboard : new boolean[]{false, true}) for (float alpha : new float[]{0f, .5f, 1f}) {
						button.setPresentation(new PostingAttachmentState(0, 4, false), keyboard);
						Bitmap at = render(button, alpha, false);
						try { assertTrue("Empty icon cannot depend on IME or badge alpha", ordinary.sameAs(at)); }
						finally { at.recycle(); }
					}
				} finally { ordinary.recycle(); }
			}
		});
	}

	@Test public void populatedAndFullIconsRemainIdenticalWhenTheBadgeFadesOrImportStarts() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean rtl : new boolean[]{false, true}) for (int limit : new int[]{2, 4}) {
				PostingAttachmentButton button = button(rtl, 1f);
				button.setPresentation(new PostingAttachmentState(2, limit, false), true);
				Bitmap expected = render(button, 0, false);
				try {
					for (boolean keyboard : new boolean[]{false, true}) for (boolean importing : new boolean[]{false, true}) {
						button.setPresentation(new PostingAttachmentState(2, limit, importing), keyboard);
						Bitmap at = render(button, 0, false);
						Bitmap badge = render(button, 1, false);
						try {
							assertTrue("Only count/limit select the icon", expected.sameAs(at));
							int top = (int) Math.ceil(21 * button.getResources().getDisplayMetrics().density);
							for (int y = top; y < at.getHeight(); y++) for (int x = 0; x < at.getWidth(); x++) {
								assertEquals("Fading the badge must not replace the icon", at.getPixel(x, y), badge.getPixel(x, y));
							}
						} finally { at.recycle(); badge.recycle(); }
					}
				} finally { expected.recycle(); }
			}
		});
	}

	@Test public void liveAndFlightMatchAtBothEndpointSizesAfterRemovingTheLastFile() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean rtl : new boolean[]{false, true}) for (boolean keyboard : new boolean[]{false, true}) {
				PostingAttachmentButton button = button(rtl, 2.5f);
				float density = button.getResources().getDisplayMetrics().density;
				button.layout(0, 0, Math.round((keyboard ? 48 : 40) * density), Math.round((keyboard ? 48 : 44) * density));
				for (int count : new int[]{2, 4, 0}) {
					button.setPresentation(new PostingAttachmentState(count, 4, false), keyboard);
					Bitmap live = render(button, button.getBadgeAlpha(), true);
					Bitmap flight = render(button, button.getBadgeAlpha(), false);
					try { assertTrue(live.sameAs(flight)); }
					finally { live.recycle(); flight.recycle(); }
				}
			}
		});
	}

	@Test public void existingGridRevealScrollsOnceWithoutEditingTheFocusedDraft() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			ScrollView scroll = new ScrollView(context);
			LinearLayout form = new LinearLayout(context); form.setOrientation(LinearLayout.VERTICAL);
			scroll.addView(form);
			EditText editor = new EditText(context); editor.setText("retained draft"); editor.setSelection(3);
			form.addView(editor, new LinearLayout.LayoutParams(-1, 900));
			LinearLayout container = new LinearLayout(context);
			FrameLayout grid = new FrameLayout(context); container.addView(grid, new LinearLayout.LayoutParams(-1, 500));
			grid.addView(new View(context), new FrameLayout.LayoutParams(-1, 200));
			form.addView(container, new LinearLayout.LayoutParams(-1, -2));
			scroll.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
			scroll.layout(0, 0, 400, 600);
			assertTrue(PostingFormAppearance.revealAttachmentGrid(scroll, container));
			int revealed = scroll.getScrollY(); assertTrue(revealed > 0);
			assertFalse(PostingFormAppearance.revealAttachmentGrid(scroll, container));
			assertEquals(revealed, scroll.getScrollY());
			assertEquals("retained draft", editor.getText().toString()); assertEquals(3, editor.getSelectionEnd());
		});
	}

	@Test public void separatedContoursKeepAtLeastFourDpOfClearPixelsAtAllFlightSizes() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (int dpi : new int[]{120, 140, 160, 200, 240, 320, 420, 640}) {
				for (boolean rtl : new boolean[]{false, true}) for (int count : new int[]{2}) {
					PostingAttachmentButton button = button(rtl, 1f, dpi);
					button.setPresentation(new PostingAttachmentState(count, 4, false), true);
					float density = button.getResources().getDisplayMetrics().density;
					for (float fraction : new float[]{.01f, .25f, .5f, .75f, 1f}) {
						int width = Math.round((40 + 8 * fraction) * density), height = Math.round((44 + 4 * fraction) * density);
						Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
						try {
							button.drawContents(new Canvas(bitmap), width, height, fraction);
							int leftEnd = -1, rightStart = width;
							// Below the badge, scan actual painted contours rather than drawable viewports.
							for (int y = (int) Math.ceil(21 * density); y < Math.min(height, Math.ceil(37 * density)); y++) {
								for (int x = 0; x < width; x++) if (Color.alpha(bitmap.getPixel(x, y)) != 0) {
									if (x < width / 2) leftEnd = Math.max(leftEnd, x);
									else rightStart = Math.min(rightStart, x);
								}
							}
							assertTrue(leftEnd >= 0); assertTrue(rightStart < width);
							int gap = rightStart - leftEnd - 1;
							assertTrue("Painted clip and plus must have a clear >=4dp gap", gap >= Math.ceil(4 * density));
						} finally { bitmap.recycle(); }
					}
				}
			}
		});
	}
}
