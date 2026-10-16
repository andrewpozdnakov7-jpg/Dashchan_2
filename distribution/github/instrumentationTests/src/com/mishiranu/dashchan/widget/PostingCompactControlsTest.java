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

	@Test public void primaryUsesRealStateWithoutSubmittingOrReplacingOriginalWidgets() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			View root = inflateForm(context); PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				appearance.setupEditor(root.findViewById(R.id.comment_parent), root.findViewById(R.id.comment), new LinearLayout(context));
				View captchaRoot = root.findViewById(R.id.captcha_input_root), target = root.findViewById(R.id.captcha_block_parent);
				int[] refresh = {0}, submissions = {0};
				CaptchaForm captcha = new CaptchaForm(new CaptchaForm.Callback() {
					@Override public void onRefreshCaptcha(boolean force) { refresh[0]++; }
					@Override public void onConfirmCaptcha() { appearance.confirmCaptchaDialog(); }
				}, true, true, captchaRoot, root.findViewById(R.id.captcha_input_parent),
						root.findViewById(R.id.captcha_input), new ChanConfiguration.Captcha());
				Button send = new Button(context); send.setEnabled(false); send.setOnClickListener(v -> submissions[0]++);
				((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
				appearance.finishSetup(send, (CheckBox) root.findViewById(R.id.sage_checkbox),
						(CheckBox) root.findViewById(R.id.spoiler_checkbox), (CheckBox) root.findViewById(R.id.original_poster_checkbox));
				captcha.showCaptcha(ReadCaptchaTask.CaptchaState.NEED_LOAD, null, null, false, false);
				assertTrue(appearance.needsCaptchaSolution());
				assertEquals(context.getString(R.string.posting_solve_captcha), send.getText().toString());
				assertNull(root.findViewById(R.id.posting_compact_captcha));
				assertEquals(View.GONE, ((View) captchaRoot.getParent()).getVisibility());
				appearance.requestCaptchaSolution(); appearance.requestCaptchaSolution(); assertEquals(1, refresh[0]);
				captcha.showLoading(); assertTrue(appearance.needsCaptchaSolution());
				assertEquals(context.getString(R.string.posting_primary_loading), send.getText().toString());
				captcha.showCaptcha(ReadCaptchaTask.CaptchaState.SKIP, null, null, false, false, true);
				assertFalse(appearance.needsCaptchaSolution()); assertFalse(send.isEnabled());
				assertEquals(context.getString(R.string.send), send.getText().toString());
				assertEquals(View.GONE, root.findViewById(R.id.posting_captcha_ready).getVisibility());
				assertSame(target, root.findViewById(R.id.captcha_block_parent)); assertEquals(0, submissions[0]);
				captcha.onEditorAction(root.findViewById(R.id.captcha_input), 0, null); assertEquals(0, submissions[0]);
				captcha.showError(); assertTrue(appearance.needsCaptchaSolution());
				appearance.requestCaptchaSolution(); assertEquals(2, refresh[0]);
				appearance.deactivateToolbar();
				captcha.showCaptcha(ReadCaptchaTask.CaptchaState.CAPTCHA, ChanConfiguration.Captcha.Input.ALL, null, false, false);
				assertTrue(appearance.needsCaptchaSolution());
				assertEquals(View.GONE, ((View) captchaRoot.getParent()).getVisibility());
				captcha.setText("fixture-answer"); assertFalse(appearance.needsCaptchaSolution()); assertEquals(0, submissions[0]);
			} finally { appearance.close(); }
		});
	}

	@Test public void movedAttachmentRetainsItsActionAndCapabilityVisibility() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			View root = inflateForm(context); PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				appearance.setupEditor(root.findViewById(R.id.comment_parent), root.findViewById(R.id.comment), new LinearLayout(context));
				Button send = new Button(context); ((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
				appearance.finishSetup(send, (CheckBox) root.findViewById(R.id.sage_checkbox),
						(CheckBox) root.findViewById(R.id.spoiler_checkbox), (CheckBox) root.findViewById(R.id.original_poster_checkbox));
				int[] clicks = {0}; appearance.setAttachmentAction(() -> clicks[0]++); appearance.setAttachmentAvailable(true);
				View attach = root.findViewById(R.id.menu_attach);
				assertSame(root.findViewById(R.id.sage_checkbox).getParent(), attach.getParent());
				attach.performClick(); assertEquals(1, clicks[0]);
				appearance.setAttachmentAvailable(false); assertEquals(View.GONE, attach.getVisibility());
				attach.performClick(); assertEquals(1, clicks[0]);
				appearance.close(); attach.performClick(); assertEquals(1, clicks[0]);
			} finally { appearance.close(); }
		});
	}

	@Test public void overflowUsesOriginalGlyphsAndStaysAboveThePanel() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
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

	@Test public void fixedFooterDoesNotAddAnotherBottomPaddingToTheSixteenDpEditorGap() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
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
				assertEquals(Math.round(16 * density), ((ViewGroup.MarginLayoutParams) ((View) send.getParent()).getLayoutParams()).topMargin);
				assertEquals(0, form.getPaddingBottom());
			} finally { appearance.close(); }
		});
	}
}
