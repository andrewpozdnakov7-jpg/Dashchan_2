package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.content.ChanMarkup;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared behavioral layout fixtures, not screenshot-coordinate assertions. */
@RunWith(AndroidJUnit4.class)
public class PostingDesignLayoutTest {
	private static int dp(Context context, int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
	private static void layout(View view, int width) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, width, view.getMeasuredHeight());
	}

	@Test public void staticUtilityRowKeepsEveryControlInsideTheViewportAndRetainsCallbacks() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (float scale : new float[] {1f, 1.3f, 2f}) {
				Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
				Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = scale;
				config.setLocale(java.util.Locale.forLanguageTag("ru"));
				Context context = PostingTestContext.create(base.createConfigurationContext(config));
				View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
				LayoutInflater.from(context).inflate(R.layout.activity_posting_footer_common,
						(FrameLayout) root.findViewById(R.id.footer_container));
				PostingFormAppearance appearance = new PostingFormAppearance(root);
				try {
					appearance.setupEditor(root.findViewById(R.id.comment_parent), root.findViewById(R.id.comment), new LinearLayout(context));
					CheckBox sage = root.findViewById(R.id.sage_checkbox), op = root.findViewById(R.id.original_poster_checkbox);
					CheckBox spoiler = root.findViewById(R.id.spoiler_checkbox); spoiler.setVisibility(View.GONE);
					sage.setChecked(true); int[] changes = {0}; sage.setOnCheckedChangeListener((v, checked) -> changes[0]++);
					Button send = new Button(context); ((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
					appearance.finishSetup(send, sage, spoiler, op);
					appearance.setAttachmentAction(() -> {}); appearance.setAttachmentAvailable(true);
					LinearLayout content = (LinearLayout) sage.getParent();
					View slot = (View) content.getParent(), panel = (View) slot.getParent();
					View attach = root.findViewById(R.id.menu_attach);
					assertSame(content, op.getParent()); assertSame(content, attach.getParent());
					assertEquals(LinearLayout.HORIZONTAL, content.getOrientation());
					assertTrue(content instanceof PostingUtilityRow);
					assertFalse(content.getParent() instanceof android.widget.HorizontalScrollView);
					for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
						panel.setLayoutDirection(direction);
						float wideTextSize = -1f;
						for (int width : new int[] {368, 328, 288, 368}) {
							layout(panel, dp(context, width));
							if (width == 368) {
								if (wideTextSize < 0) wideTextSize = sage.getTextSize();
								else assertEquals(wideTextSize, sage.getTextSize(), .01f);
							}
							assertEquals(dp(context, width), content.getWidth());
							for (View control : new View[] {attach, sage, op}) {
								assertTrue(control.getLeft() >= 0 && control.getRight() <= content.getWidth());
							}
							assertTrue(slot.getHeight() >= dp(context, 64));
							assertEquals(sage.getTop() + sage.getHeight() / 2, op.getTop() + op.getHeight() / 2);
							assertTrue(op.getWidth() > dp(context, 28)); assertTrue(sage.getWidth() > dp(context, 28));
							assertNull(sage.getEllipsize()); assertNull(op.getEllipsize());
							assertEquals(1, sage.getMaxLines()); assertEquals(1, op.getMaxLines());
							assertEquals(1, sage.getLineCount()); assertEquals(1, op.getLineCount());
							assertTrue(android.text.Layout.getDesiredWidth(sage.getText(), sage.getPaint())
									<= sage.getWidth() - sage.getCompoundPaddingLeft() - sage.getCompoundPaddingRight());
							assertTrue(android.text.Layout.getDesiredWidth(op.getText(), op.getPaint())
									<= op.getWidth() - op.getCompoundPaddingLeft() - op.getCompoundPaddingRight());
							assertEquals(sage.getTextSize(), op.getTextSize(), .01f);
							assertEquals(sage.getText(), sage.getContentDescription());
							assertTrue(sage.isChecked()); assertEquals(0, changes[0]);
							assertEquals(0, content.getScrollX());
							assertTrue(((View) send.getParent()).getTop() >= slot.getBottom() + dp(context, 16));
							if (scale > 1f) assertTrue(sage.getHeight() >= dp(context, 48));
						}
					}
					sage.performClick(); assertFalse(sage.isChecked()); assertEquals(1, changes[0]);
					op.setVisibility(View.GONE); sage.setVisibility(View.GONE); appearance.setAttachmentAvailable(false);
					appearance.syncOptionsVisibility(); layout(panel, dp(context, 328));
					assertEquals(View.VISIBLE, slot.getVisibility());
					// Android rounds the44dp row and each10dp padding independently.
					assertEquals(Math.max(dp(context, 64), dp(context, 44) + 2 * dp(context, 10)), slot.getHeight());
					assertEquals(View.GONE, spoiler.getVisibility());
				} finally { appearance.close(); }
			}
		});
	}

	@Test public void v53OmitsMoreWhenAllToolsFitAndKeepsEveryOriginalAction() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				LinearLayout row = new LinearLayout(context);
				appearance.setupEditor(root.findViewById(R.id.comment_parent), root.findViewById(R.id.comment), row);
				int[] tags = {ChanMarkup.TAG_BOLD, ChanMarkup.TAG_ITALIC, ChanMarkup.TAG_UNDERLINE,
						ChanMarkup.TAG_OVERLINE, ChanMarkup.TAG_STRIKE, ChanMarkup.TAG_SPOILER, ChanMarkup.TAG_QUOTE,
						ChanMarkup.TAG_SUBSCRIPT, ChanMarkup.TAG_SUPERSCRIPT};
				Button[] buttons = new Button[tags.length]; int[] calls = {0};
				for (int i = 0; i < tags.length; i++) {
					Button button = new Button(context); button.setText(i == 5 ? "SP" : i == 6 ? ">" : "X");
					button.setOnClickListener(v -> calls[0]++); button.setTag(tags[i]);
					appearance.styleMarkup(button, tags[i]); row.addView(button); buttons[i] = button;
				}
				appearance.formattingVisibility(true);
				View viewport = (View) row.getParent(), more = root.findViewById(R.id.posting_format_more);
				layout(viewport, dp(context, 352));
				for (Button button : buttons) assertEquals(View.VISIBLE, button.getVisibility());
				assertEquals(View.GONE, more.getVisibility());
				layout(viewport, dp(context, 304));
				for (int i = 0; i < 7; i++) assertEquals(View.VISIBLE, buttons[i].getVisibility());
				assertEquals(row.getWidth() - dp(context, 4), more.getRight());
				assertTrue(appearance.performExtraCommand(buttons[7])); assertEquals(1, calls[0]);
				layout(viewport, dp(context, 264));
				assertEquals(View.GONE, buttons[3].getVisibility()); assertEquals(View.VISIBLE, buttons[4].getVisibility());
				assertTrue(appearance.performExtraCommand(buttons[3])); assertEquals(2, calls[0]);
				assertEquals(row.getWidth() - dp(context, 4), more.getRight());
				layout(viewport, dp(context, 224));
				assertEquals(View.GONE, buttons[4].getVisibility()); assertEquals(View.VISIBLE, buttons[2].getVisibility());
				assertTrue(appearance.performExtraCommand(buttons[4])); assertEquals(3, calls[0]);
				layout(viewport, dp(context, 304));
				assertEquals(View.VISIBLE, buttons[3].getVisibility()); assertFalse(appearance.performExtraCommand(buttons[3]));
				buttons[3].performClick(); assertEquals(4, calls[0]); assertSame(row, buttons[3].getParent());
			} finally { appearance.close(); }
		});
	}

	@Test public void narrowFormattingMovesToolsInPriorityOrderWithoutChangingActions() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			try {
				LinearLayout row = new LinearLayout(context);
				appearance.setupEditor(root.findViewById(R.id.comment_parent), root.findViewById(R.id.comment), row);
				int[] tags = {ChanMarkup.TAG_BOLD, ChanMarkup.TAG_ITALIC, ChanMarkup.TAG_SPOILER, ChanMarkup.TAG_QUOTE, ChanMarkup.TAG_UNDERLINE};
				Button[] buttons = new Button[tags.length]; int[] calls = {0};
				for (int i = 0; i < tags.length; i++) {
					Button button = new Button(context); button.setText(i == 0 ? "B" : i == 1 ? "I" : i == 2 ? "SP" : i == 3 ? ">" : "U");
					button.setTag(tags[i]); button.setOnClickListener(v -> calls[0]++);
					appearance.styleMarkup(button, tags[i]); row.addView(button); buttons[i] = button;
				}
				appearance.formattingVisibility(true);
				View viewport = (View) row.getParent();
				layout(viewport, dp(context, 296));
				assertEquals(View.VISIBLE, buttons[1].getVisibility()); assertFalse(appearance.performExtraCommand(buttons[1]));
				layout(viewport, dp(context, 152));
				assertEquals(View.GONE, buttons[1].getVisibility()); assertEquals(View.VISIBLE, buttons[0].getVisibility());
				assertTrue(appearance.performExtraCommand(buttons[1])); assertEquals(1, calls[0]);
				layout(viewport, dp(context, 115));
				assertEquals(View.GONE, buttons[0].getVisibility()); assertTrue(appearance.performExtraCommand(buttons[0]));
				assertEquals(2, calls[0]); assertEquals(ChanMarkup.TAG_BOLD, buttons[0].getTag());
				assertSame(row, buttons[0].getParent());
				layout(viewport, dp(context, 296)); assertEquals(View.VISIBLE, buttons[1].getVisibility());
			} finally { appearance.close(); }
		});
	}
}
