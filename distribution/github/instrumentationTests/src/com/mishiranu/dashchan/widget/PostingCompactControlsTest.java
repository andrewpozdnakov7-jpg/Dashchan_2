package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.ChanMarkup;
import chan.content.ChanConfiguration;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import com.mishiranu.dashchan.ui.CaptchaForm;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared native view fixtures; no network, attachment picker, credentials or post submission. */
@RunWith(AndroidJUnit4.class)
public class PostingCompactControlsTest {
	private static View inflateForm(Context context) {
		View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
		LayoutInflater.from(context).inflate(R.layout.activity_posting_footer_common,
				(FrameLayout) root.findViewById(R.id.footer_container));
		return root;
	}

	private static Button command(PostingFormAppearance appearance, LinearLayout row, int tag, Runnable action) {
		Button button = new Button(row.getContext());
		button.setTag(tag); button.setText("command"); button.setOnClickListener(v -> action.run());
		appearance.styleMarkup(button, tag);
		row.addView(button);
		return button;
	}

	@Test public void inlineAttachmentAndOverflowRetainOriginalActionsAndTags() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			View root = inflateForm(context);
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			EditText comment = root.findViewById(R.id.comment);
			LinearLayout row = new LinearLayout(context);
			row.setOrientation(LinearLayout.HORIZONTAL);
			try {
				appearance.setupEditor(root.findViewById(R.id.comment_parent), comment, row);
				int[] calls = {0};
				Button bold = command(appearance, row, ChanMarkup.TAG_BOLD, () -> calls[0]++);
				Button italic = command(appearance, row, ChanMarkup.TAG_ITALIC, () -> {});
				Button spoiler = command(appearance, row, ChanMarkup.TAG_SPOILER, () -> {});
				Button quote = command(appearance, row, ChanMarkup.TAG_QUOTE, () -> {});
				Button underline = command(appearance, row, ChanMarkup.TAG_SUPERSCRIPT, () -> calls[0]++);
				appearance.formattingVisibility(true);
				for (View button : new View[] {bold, italic, spoiler, quote}) assertEquals(View.VISIBLE, button.getVisibility());
				assertEquals(View.GONE, underline.getVisibility());
				assertEquals(ChanMarkup.TAG_SUPERSCRIPT, underline.getTag());
				assertSame(row, underline.getParent());
				assertEquals(View.VISIBLE, root.findViewById(R.id.posting_format_more).getVisibility());
				assertTrue(appearance.performExtraCommand(underline));
				assertEquals(1, calls[0]);
				assertFalse(appearance.performExtraCommand(bold));
				underline.setEnabled(false);
				assertFalse(appearance.performExtraCommand(underline));
				appearance.resetCommands();
				underline.setEnabled(true);
				assertFalse(appearance.performExtraCommand(underline));

				int[] attached = {0};
				appearance.setAttachmentAction(() -> attached[0]++);
				appearance.setAttachmentAvailable(true);
				View attach = root.findViewById(R.id.menu_attach);
				assertSame(row.getParent().getParent(), attach.getParent());
				attach.performClick(); assertEquals(1, attached[0]);
				appearance.setAttachmentAvailable(false);
				assertEquals(View.GONE, attach.getVisibility());
				attach.performClick(); assertEquals(1, attached[0]);
				appearance.close();
				attach.performClick(); assertEquals(1, attached[0]);
			} finally { appearance.close(); }
		});
	}

	@Test public void compactCaptchaDelegatesAndExpandsWithoutReplacingFieldsOrCheckboxes() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			View root = inflateForm(context);
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				EditText comment = root.findViewById(R.id.comment);
				appearance.setupEditor(root.findViewById(R.id.comment_parent), comment, new LinearLayout(context));
				View captcha = root.findViewById(R.id.captcha_input_root);
				View target = root.findViewById(R.id.captcha_block_parent);
				View block = root.findViewById(R.id.captcha_block);
				View skip = root.findViewById(R.id.captcha_skip_block);
				View cancel = root.findViewById(R.id.captcha_cancel);
				((android.widget.TextView) root.findViewById(R.id.captcha_skip_text)).setText(R.string.load_captcha);
				block.setVisibility(View.INVISIBLE); skip.setVisibility(View.VISIBLE); cancel.setVisibility(View.GONE);
				int[] clicks = {0};
				target.setOnClickListener(v -> clicks[0]++);
				target.setOnLongClickListener(v -> { clicks[0] += 10; return true; });
				CheckBox sage = root.findViewById(R.id.sage_checkbox);
				CheckBox spoiler = root.findViewById(R.id.spoiler_checkbox);
				CheckBox op = root.findViewById(R.id.original_poster_checkbox);
				sage.setChecked(true); spoiler.setVisibility(View.GONE);
				CarryLayout row = root.findViewById(R.id.checkbox_parent);
				Button send = new Button(context);
				((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
				appearance.finishSetup(send, sage, spoiler, op);
				View entry = root.findViewById(R.id.posting_compact_captcha);
				View slot = (View) entry.getParent();
				View material = root.findViewById(R.id.posting_captcha_loading);
				View loading = root.findViewById(R.id.captcha_loading);
				assertEquals(View.VISIBLE, entry.getVisibility());
				assertEquals(View.GONE, captcha.getVisibility());
				View panel = (View) slot.getParent();
				assertSame(panel, row.getParent()); assertSame(row, sage.getParent().getParent());
				assertTrue(sage.isChecked()); assertEquals(View.GONE, spoiler.getVisibility());
				assertEquals(context.getString(R.string.dont_bring_up), sage.getText().toString());
				assertEquals(context.getString(R.string.original_poster), op.getText().toString());
				assertEquals(context.getString(R.string.dont_bring_up), sage.getContentDescription().toString());
				assertEquals(context.getString(R.string.original_poster), op.getContentDescription().toString());
				assertEquals(0, row.getHorizontalSpacing());
				entry.performClick(); entry.performLongClick(); assertEquals(11, clicks[0]);

				block.setVisibility(View.VISIBLE); skip.setVisibility(View.GONE);
				// First open: XML still marks the cancel child visible inside the hidden skip block.
				cancel.setVisibility(View.VISIBLE);
				loading.setVisibility(View.VISIBLE);
				PostingFormAppearance.captchaStateChanged(target);
				assertEquals(View.VISIBLE, slot.getVisibility()); assertEquals(View.GONE, captcha.getVisibility());
				assertEquals(View.VISIBLE, material.getVisibility());
				assertEquals(context.getString(R.string.posting_captcha_label), ((Button) entry).getText().toString());
				cancel.setVisibility(View.GONE);
				loading.setVisibility(View.GONE);
				PostingFormAppearance.captchaStateChanged(target);
				assertEquals(View.GONE, slot.getVisibility()); assertEquals(View.VISIBLE, captcha.getVisibility());
				assertEquals(View.GONE, material.getVisibility());
				assertSame(target, root.findViewById(R.id.captcha_block_parent));
				assertSame(sage, root.findViewById(R.id.sage_checkbox)); assertTrue(sage.isChecked());

				block.setVisibility(View.INVISIBLE); skip.setVisibility(View.VISIBLE); cancel.setVisibility(View.VISIBLE);
				PostingFormAppearance.captchaStateChanged(target);
				assertEquals(View.GONE, slot.getVisibility()); assertEquals(View.VISIBLE, captcha.getVisibility());
				cancel.setVisibility(View.GONE);
				PostingFormAppearance.captchaStateChanged(target);
				assertEquals(View.VISIBLE, slot.getVisibility()); assertEquals(View.GONE, captcha.getVisibility());

				int densityWidth = Math.round(360 * context.getResources().getDisplayMetrics().density);
				panel.measure(View.MeasureSpec.makeMeasureSpec(densityWidth, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				int idleWidth = slot.getMeasuredWidth(), idleHeight = panel.getMeasuredHeight();
				block.setVisibility(View.VISIBLE); skip.setVisibility(View.GONE); loading.setVisibility(View.VISIBLE);
				PostingFormAppearance.captchaStateChanged(target);
				panel.measure(View.MeasureSpec.makeMeasureSpec(densityWidth, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				assertEquals(idleWidth, slot.getMeasuredWidth()); assertEquals(idleHeight, panel.getMeasuredHeight());

				int width = Math.round(160 * context.getResources().getDisplayMetrics().density);
				panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				panel.layout(0, 0, width, panel.getMeasuredHeight());
				assertTrue(row.getTop() >= slot.getBottom());
				for (int i = 0; i < row.getChildCount(); i++) {
					View child = row.getChildAt(i);
					if (child.getVisibility() != View.GONE) assertTrue(child.getLeft() >= 0 && child.getRight() <= width);
				}
			} finally { appearance.close(); }
		});
	}

	@Test public void overflowUsesOriginalGlyphsAndStaysAboveThePanel() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			Button original = new Button(context);
			original.setTextSize(18);
			original.setTag(ChanMarkup.TAG_SUPERSCRIPT); original.setContentDescription("Superscript");
			View glyph = PostingFormattingMenu.createGlyph(original, ChanMarkup.TAG_SUPERSCRIPT, 0xff111111, 0xff3366cc);
			assertEquals("SUP", ((android.widget.TextView) glyph).getText().toString());
			assertEquals(original.getTextSize(), ((android.widget.TextView) glyph).getTextSize(), .001f);
			assertEquals(original.getTypeface(), ((android.widget.TextView) glyph).getTypeface());
			assertEquals(original.getTag(), glyph.getTag());
			assertEquals(original.getContentDescription(), glyph.getContentDescription());
			assertNull(original.getParent());
			for (int viewportWidth : new int[] {180, 360, 800}) {
				Rect viewport = new Rect(0, 24, viewportWidth, 640);
				Rect panel = new Rect(16, 480, viewportWidth - 16, 528);
				Rect bounds = PostingFormattingMenu.calculateBounds(viewport, panel,
						new Rect(viewportWidth - 64, 480, viewportWidth - 16, 528), 1f, 9, false);
				assertNotNull(bounds); assertTrue(viewport.contains(bounds)); assertTrue(bounds.bottom < panel.top);
			}
			// Absolute screen coordinates, a visible viewport already shortened by the keyboard,
			// and fractional density must use exactly the same rounded padding as the grid.
			for (float density : new float[] {1f, 1.25f, 2.75f}) {
				Rect viewport = new Rect(44, 76, 744, 740);
				Rect panel = new Rect(60, 520, 720, 580);
				Rect bounds = PostingFormattingMenu.calculateBounds(viewport, panel,
						new Rect(640, 520, 700, 580), density, 9, false);
				assertNotNull(bounds); assertTrue(viewport.contains(bounds)); assertTrue(bounds.bottom < panel.top);
				int cell = Math.round(44 * density), padding = Math.round(8 * density) * 2;
				assertEquals(0, (bounds.width() - padding) % cell);
			}
		});
	}

	@Test public void readyCaptchaKeepsCompactWidthAndOriginalForceRefreshWithoutHidingRealChallenges() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			View root = inflateForm(context);
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				appearance.setupEditor(root.findViewById(R.id.comment_parent), root.findViewById(R.id.comment), new LinearLayout(context));
				int[] refresh = {0, 0};
				CaptchaForm captcha = new CaptchaForm(new CaptchaForm.Callback() {
					@Override public void onRefreshCaptcha(boolean force) { refresh[force ? 1 : 0]++; }
					@Override public void onConfirmCaptcha() {}
				}, true, true, root.findViewById(R.id.captcha_input_root), root.findViewById(R.id.captcha_input_parent),
						root.findViewById(R.id.captcha_input), new ChanConfiguration.Captcha());
				captcha.showLoading();
				Button send = new Button(context);
				send.setEnabled(false);
				((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
				appearance.finishSetup(send, (CheckBox) root.findViewById(R.id.sage_checkbox),
						(CheckBox) root.findViewById(R.id.spoiler_checkbox), (CheckBox) root.findViewById(R.id.original_poster_checkbox));
				View entry = root.findViewById(R.id.posting_compact_captcha);
				View slot = (View) entry.getParent();
				CarryLayout row = root.findViewById(R.id.checkbox_parent);
				View panel = (View) slot.getParent();
				int width = Math.round(360 * context.getResources().getDisplayMetrics().density);
				panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				int compactWidth = slot.getMeasuredWidth();
				assertTrue(compactWidth > Math.round(64 * context.getResources().getDisplayMetrics().density));
				for (int i = 0; i < 2; i++) {
					captcha.showCaptcha(ReadCaptchaTask.CaptchaState.SKIP, null, null, false, false, true);
					assertFalse(send.isEnabled()); // Animation must never enable posting.
					assertEquals(View.VISIBLE, slot.getVisibility());
					assertEquals(View.VISIBLE, root.findViewById(R.id.posting_captcha_ready).getVisibility());
					assertEquals(View.GONE, root.findViewById(R.id.posting_captcha_loading).getVisibility());
					assertEquals(View.GONE, root.findViewById(R.id.captcha_input_root).getVisibility());
					assertTrue(entry.isEnabled()); entry.performClick();
					panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
							View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
					assertEquals(compactWidth, slot.getMeasuredWidth());
					panel.layout(0, 0, width, panel.getMeasuredHeight());
					View check = root.findViewById(R.id.posting_captcha_ready);
					View group = (View) check.getParent();
					assertTrue(Math.abs(group.getLeft() * 2 + group.getWidth() - slot.getWidth()) <= 1);
					assertEquals(context.getString(R.string.posting_captcha_done),
							((android.widget.TextView) root.findViewById(R.id.posting_captcha_status)).getText().toString());
					assertEquals(slot.getWidth(), entry.getWidth());
				}
				assertEquals(2, refresh[1]); assertEquals(0, refresh[0]);
				for (ReadCaptchaTask.CaptchaState state : new ReadCaptchaTask.CaptchaState[] {
						ReadCaptchaTask.CaptchaState.SKIP, ReadCaptchaTask.CaptchaState.PASS}) {
					captcha.showCaptcha(state, null, null, false, false);
					assertEquals(View.GONE, slot.getVisibility()); // Not required/authorization is not a solved answer.
					assertEquals(View.GONE, root.findViewById(R.id.posting_captcha_ready).getVisibility());
					assertEquals(View.VISIBLE, root.findViewById(R.id.captcha_input_root).getVisibility());
				}
				captcha.showError();
				assertEquals(View.VISIBLE, slot.getVisibility());
				assertEquals(context.getString(R.string.posting_captcha_retry),
						((android.widget.TextView) root.findViewById(R.id.posting_captcha_status)).getText().toString());
				captcha.showCaptcha(ReadCaptchaTask.CaptchaState.NEED_LOAD, null, null, false, false);
				assertEquals(View.GONE, root.findViewById(R.id.posting_captcha_ready).getVisibility());
				entry.performClick(); assertEquals(1, refresh[0]);
				captcha.showCaptcha(ReadCaptchaTask.CaptchaState.CAPTCHA, ChanConfiguration.Captcha.Input.ALL, null, false, false);
				assertEquals(View.VISIBLE, slot.getVisibility()); // Ready caption stays stable while entering an image answer.
				assertEquals(View.VISIBLE, root.findViewById(R.id.captcha_input_root).getVisibility());
				assertEquals(View.VISIBLE, root.findViewById(R.id.captcha_input).getVisibility());
				assertEquals(View.GONE, root.findViewById(R.id.posting_captcha_ready).getVisibility());
			} finally { appearance.close(); }
		});
	}

	@Test public void fixedFooterDoesNotAddAnotherBottomPaddingToTheSixteenDpEditorGap() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
			View root = inflateForm(context);
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				appearance.setupEditor(root.findViewById(R.id.comment_parent), root.findViewById(R.id.comment), new LinearLayout(context));
				Button send = new Button(context);
				((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
				appearance.finishSetup(send);
				float density = context.getResources().getDisplayMetrics().density;
				int width = Math.round(360 * density), height = Math.round(800 * density);
				root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
				root.layout(0, 0, width, height); root.getViewTreeObserver().dispatchOnGlobalLayout();
				View editor = root.findViewById(R.id.comment_parent);
				View form = root.findViewById(R.id.posting_layout);
				assertEquals(Math.round(16 * density), ((ViewGroup.MarginLayoutParams) editor.getLayoutParams()).bottomMargin);
				assertEquals(Math.round(16 * density), ((ViewGroup.MarginLayoutParams) send.getLayoutParams()).topMargin);
				assertEquals(0, form.getPaddingBottom());
			} finally { appearance.close(); }
		});
	}
}
