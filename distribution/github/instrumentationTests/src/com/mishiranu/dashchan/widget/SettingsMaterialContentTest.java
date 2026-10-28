package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;

/** Prepared detached geometry fixtures. No preference writes, files, requests or real catalogs. */
public class SettingsMaterialContentTest {
	private Context context(float scale) {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = scale;
		return PostingTestContext.create(base.createConfigurationContext(config));
	}
	private void resolve(View view, boolean enabled) {
		Object tag = view.getTag(R.id.interface_appearance_state);
		if (tag instanceof InterfaceAppearance.State) InterfaceAppearance.renderResolved(view, (InterfaceAppearance.State) tag, enabled);
		if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) resolve(((ViewGroup) view).getChildAt(i), enabled);
		StandardAppearance.resolveLayout(view, enabled); SettingsContentAppearance.resolve(view, enabled);
	}
	private void measure(View view, int width, int height) {
		view.measure(View.MeasureSpec.makeMeasureSpec(StandardAppearance.dp(view, width), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(StandardAppearance.dp(view, height), View.MeasureSpec.AT_MOST));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
	@Test public void cardRebindingKeepsAllMetadataAndNeverTintsItsSemanticPreview() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(2); CardView card = new CardView(context); card.setBackgroundColor(Color.GRAY);
			android.graphics.drawable.Drawable original = card.getBackground();
			card.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -2));
			LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL); card.addView(content);
			android.widget.ImageView image = new android.widget.ImageView(context); image.setImageDrawable(new ColorDrawable(Color.GREEN));
			content.addView(image, new LinearLayout.LayoutParams(-1, StandardAppearance.dp(image, 180)));
			TextView title = new TextView(context), metadata = new TextView(context);
			title.setSingleLine(true); metadata.setMaxLines(2);
			title.setText("Long synthetic wallpaper title with wrapping"); metadata.setText("Author and a long license with attribution and file size");
			content.addView(title); content.addView(metadata);
			InterfaceAppearance.standardCatalogCard(card, title, metadata);
			for (int i = 0; i < 3; i++) {
				resolve(card, true); measure(card, 160, 1200);
				assertEquals(Integer.MAX_VALUE, title.getMaxLines()); assertEquals(Integer.MAX_VALUE, metadata.getMaxLines());
				assertTrue(title.getLineCount() > 1); assertTrue(metadata.getLineCount() > 2);
				assertEquals(StandardAppearance.dp(image, 180), image.getHeight()); assertNull(image.getImageTintList());
			}
			resolve(card, false); assertSame(original, card.getBackground()); assertEquals(1, title.getMaxLines()); assertEquals(2, metadata.getMaxLines());
		});
	}
	@Test public void radioAndDynamicChoicesKeepOneCallbackAndDoNotRecolorThePreview() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			LinearLayout root = new LinearLayout(context(2)); root.setOrientation(LinearLayout.VERTICAL);
			TextView preview = new TextView(root.getContext()); preview.setTextColor(Color.MAGENTA); preview.setBackgroundColor(Color.GREEN); root.addView(preview);
			RadioButton radio = new RadioButton(root.getContext()); radio.setText("A long synthetic mode label that must remain readable"); root.addView(radio);
			int[] changes = {0}; radio.setOnCheckedChangeListener((v, checked) -> changes[0]++);
			InterfaceAppearance.standardFormChoice(radio); resolve(root, true); measure(root, 160, 1000);
			assertEquals(0, changes[0]); assertEquals(Color.MAGENTA, preview.getCurrentTextColor());
			assertEquals(Color.GREEN, ((ColorDrawable) preview.getBackground()).getColor());
			assertTrue(radio.getHeight() >= StandardAppearance.dp(root, 48)); assertTrue(radio.getLineCount() > 1);
			radio.performClick(); assertTrue(radio.isChecked()); assertEquals(1, changes[0]);
			radio.setEnabled(false); resolve(root, true); assertFalse(radio.isEnabled()); assertTrue(radio.isChecked()); assertEquals(1, changes[0]);
			for (int i = 0; i < 3; i++) {
				CheckBox created = new CheckBox(root.getContext()); created.setChecked(true); created.setText("Filtered board " + i); root.addView(created);
				InterfaceAppearance.standardFormChoice(created); resolve(created, true); measure(created, 160, 300);
				assertTrue(created.isChecked()); assertTrue(created.getHeight() >= StandardAppearance.dp(created, 48)); root.removeView(created);
			}
		});
	}
	@Test public void fullAutohideContentKeepsErrorColorsSpansAndSelectionAcrossRefresh() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View root = LayoutInflater.from(context(2)).inflate(R.layout.dialog_autohide, null);
			android.widget.EditText value = root.findViewById(R.id.value); value.setText("fixture regex"); value.setSelection(2, 5);
			BackgroundColorSpan span = new BackgroundColorSpan(Color.RED); value.getText().setSpan(span, 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			TextView error = root.findViewById(R.id.error_text); error.setTextColor(Color.RED); error.setText("Synthetic regex error"); error.setVisibility(View.VISIBLE);
			InterfaceAppearance.standardAutohideContent(root, false);
			for (int i = 0; i < 3; i++) {
				resolve(root, true); measure(root, 240, 900);
				assertEquals(Color.RED, error.getCurrentTextColor()); assertEquals(1, value.getText().getSpans(0, value.length(), BackgroundColorSpan.class).length);
				assertEquals(2, value.getSelectionStart()); assertEquals(5, value.getSelectionEnd());
				View forum = root.findViewById(R.id.chan_name); assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, forum.getLayoutParams().height);
				assertTrue(forum.getHeight() >= StandardAppearance.dp(forum, 48));
			}
			resolve(root, false); assertEquals(StandardAppearance.dp(root, 40), root.findViewById(R.id.chan_name).getLayoutParams().height);
		});
	}
	@Test public void formViewportWrapsShortContentAndBoundsLongContentWithOneScrollOwner() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			DialogFormScrollView scroll = new DialogFormScrollView(context(1)); LinearLayout body = new LinearLayout(scroll.getContext());
			body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body);
			TextView one = new TextView(body.getContext()); one.setText("Short result"); body.addView(one);
			measure(scroll, 240, 200); int shortHeight = scroll.getHeight(); assertTrue(shortHeight < StandardAppearance.dp(scroll, 200));
			for (int i = 0; i < 50; i++) { TextView row = new TextView(body.getContext()); row.setText("Synthetic board " + i); body.addView(row); }
			measure(scroll, 240, 144); assertEquals(StandardAppearance.dp(scroll, 144), scroll.getHeight()); assertTrue(body.getHeight() > scroll.getHeight());
			assertEquals(1, scroll.getChildCount());
		});
	}
}
