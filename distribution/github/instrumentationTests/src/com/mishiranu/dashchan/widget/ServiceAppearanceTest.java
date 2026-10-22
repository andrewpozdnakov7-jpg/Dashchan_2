package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.FontManager;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared only. Detached fixture state; no preference, network or user-data writes. */
@RunWith(AndroidJUnit4.class)
public class ServiceAppearanceTest {
	private Context context(float scale) {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Configuration configuration = new Configuration(base.getResources().getConfiguration());
		configuration.fontScale = scale;
		return PostingTestContext.create(base.createConfigurationContext(configuration));
	}
	private void resolve(View view, boolean enabled) {
		Object state = view.getTag(R.id.interface_appearance_state);
		if (state instanceof InterfaceAppearance.State) InterfaceAppearance.renderResolved(view, (InterfaceAppearance.State) state, enabled);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) resolve(group.getChildAt(i), enabled);
		}
		StandardAppearance.resolveLayout(view, enabled);
		Object service = view.getTag(R.id.standard_service_appearance);
		if (service instanceof ServiceAppearance.State) ((ServiceAppearance.State) service).applyResolved(enabled);
	}
	private void measure(View view, int width) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
	@Test public void recycledPreviewResetsUnreadWeightAlphaAndRestoresLatestLegacyState() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1);
			ViewFactory.TwoLinesViewHolder h = ViewFactory.makeTwoLinesListItem(new FrameLayout(context), ViewFactory.FEATURE_TEXT2_END, true);
			Typeface original = h.text1.getTypeface();
			h.text1.setMaxLines(5); h.text1.setText("Preview"); h.text2.setText("Location"); h.text2End.setText("22.10.2026");
			InterfaceAppearance.servicePreview(h.view, h.text1, h.text2, h.text2End, false, true, 1f); resolve(h.view, true);
			assertEquals(FontManager.resolveTypeface(context, true), h.text1.getTypeface());
			h.text1.setTypeface(original); h.text1.setAlpha(.56f); h.text2.setAlpha(.56f); h.text2End.setAlpha(.56f);
			InterfaceAppearance.servicePreview(h.view, h.text1, h.text2, h.text2End, true, false, .56f); resolve(h.view, true);
			assertEquals(FontManager.resolveTypeface(context, false), h.text1.getTypeface());
			assertEquals(1f, h.text1.getAlpha(), 0); assertEquals(1f, h.text2.getAlpha(), 0); assertEquals(1f, h.text2End.getAlpha(), 0);
			assertTrue(h.view.isEnabled()); assertEquals(5, h.text1.getMaxLines());
			resolve(h.view, false); assertEquals(.56f, h.text1.getAlpha(), 0); assertEquals(original, h.text1.getTypeface());
			resolve(h.view, true); assertEquals(1f, h.text1.getAlpha(), 0);
		});
	}
	@Test public void themeRowRecyclesSelectionWithoutTintingPreviewOrEmittingClicks() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(2);
			Preference.Runtime<?> preference = new Preference.Runtime<>(context, "", null, "Title", p -> null);
			Preference.Runtime.IconViewHolder h = preference.createIconViewHolder(new FrameLayout(context));
			h.summary.setVisibility(View.GONE);
			ImageView check = new ImageView(context); h.widgetFrame.addView(check, StandardAppearance.dp(h.view, 24), StandardAppearance.dp(h.view, 24));
			Drawable preview = ThemeEngine.getTheme(context).createThemeChoiceDrawable(); h.icon.setImageDrawable(preview);
			int originalMargin = ((ViewGroup.MarginLayoutParams) h.icon.getLayoutParams()).getMarginEnd();
			int[] clicks = {0}; h.view.setOnClickListener(v -> clicks[0]++);
			h.title.setText("A long theme title with a trailing selection mark");
			InterfaceAppearance.themeChoice(h.view, h.title, h.icon, check, true, true); resolve(h.view, true);
			assertTrue(h.view.isSelected()); assertEquals(View.VISIBLE, check.getVisibility()); assertNull(h.icon.getImageTintList());
			assertSame(preview, h.icon.getDrawable()); assertEquals(0, clicks[0]);
			InterfaceAppearance.themeChoice(h.view, h.title, h.icon, check, false, true); resolve(h.view, true);
			assertFalse(h.view.isSelected()); assertEquals(View.INVISIBLE, check.getVisibility());
			measure(h.view, StandardAppearance.dp(h.view, 280)); assertTrue(h.view.getHeight() >= h.title.getHeight());
			resolve(h.view, false); assertEquals(View.GONE, h.widgetFrame.getVisibility());
			assertEquals(originalMargin, ((ViewGroup.MarginLayoutParams) h.icon.getLayoutParams()).getMarginEnd());
			assertSame(preview, h.icon.getDrawable()); assertEquals(0, clicks[0]);
		});
	}
	@Test public void themeChoiceDiagnosticsOmitsLabelsAndIncludesCheckGeometry() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1);
			Preference.Runtime<?> preference = new Preference.Runtime<>(context, "", null, "PRIVATE_THEME_LABEL", p -> null);
			Preference.Runtime.IconViewHolder h = preference.createIconViewHolder(new FrameLayout(context));
			ThemeChoiceRow row = new ThemeChoiceRow(h, false);
			row.bind(ThemeEngine.getThemes().get(0), true); h.title.setText("PRIVATE_THEME_LABEL");
			String state = row.diagnosticState();
			assertTrue(state.contains("currentAtBind=")); assertTrue(state.contains("currentNow="));
			assertTrue(state.contains("check{vis=")); assertTrue(state.contains("measuredPx="));
			assertFalse(state.contains("PRIVATE_THEME_LABEL"));
		});
	}
	@Test public void tabsGrowAtLargeFontAndRestoreNativeHeightWithoutDuplicateClicks() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(2); LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
			TextView text = (TextView) ViewFactory.makeSingleLineListItem(new FrameLayout(context));
			int legacyHeight = StandardAppearance.dp(root, 46); root.addView(text, new LinearLayout.LayoutParams(280, legacyHeight));
			View indicator = new View(context); root.addView(indicator, 280, StandardAppearance.dp(root, 2));
			text.setText("Replies with a long translated label"); int[] clicks = {0}; root.setOnClickListener(v -> clicks[0]++);
			InterfaceAppearance.serviceTab(root, text, indicator, true); resolve(root, true); measure(root, StandardAppearance.dp(root, 160));
			assertTrue(root.getHeight() > StandardAppearance.dp(root, 48)); assertTrue(root.isSelected()); assertEquals(0, clicks[0]);
			InterfaceAppearance.serviceTab(root, text, indicator, false); resolve(root, true); assertFalse(root.isSelected());
			resolve(root, false); assertEquals(legacyHeight, text.getLayoutParams().height);
		});
	}
	@Test public void documentTypographyPreservesSpansAndUnlimitedLines() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			TextView text = new TextView(context(1.3f));
			SpannableString content = new SpannableString("Document with bold text and multiple lines\nSecond line");
			StyleSpan bold = new StyleSpan(Typeface.BOLD); content.setSpan(bold, 14, 18, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			text.setText(content); InterfaceAppearance.serviceDocument(text); resolve(text, true);
			assertEquals(Integer.MAX_VALUE, text.getMaxLines());
			assertEquals(1, ((Spanned) text.getText()).getSpans(0, content.length(), StyleSpan.class).length);
			resolve(text, false); assertEquals(content.toString(), text.getText().toString());
			assertEquals(1, ((Spanned) text.getText()).getSpans(0, content.length(), StyleSpan.class).length);
		});
	}
	@Test public void commentDocumentCanApplyRefreshAndRestoreWithoutChangingContentOrNativeLimits() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (float scale : new float[]{1f, 1.3f, 2f}) {
				CommentTextView text = new CommentTextView(context(scale), null, android.R.attr.textAppearance);
				SpannableString content = new SpannableString("Document with bold text\nSecond line\nThird line");
				StyleSpan bold = new StyleSpan(Typeface.BOLD); content.setSpan(bold, 14, 18, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				text.setText(content); int min = text.getMinLines(), max = text.getMaxLines();
				Typeface original = text.getTypeface(); float size = text.getTextSize();
				InterfaceAppearance.serviceDocument(text);
				for (boolean enabled : new boolean[]{false, true, true, false, true, false}) {
					resolve(text, enabled); measure(text, StandardAppearance.dp(text, 280));
					assertEquals(content.toString(), text.getText().toString());
					assertEquals(min, text.getMinLines()); assertEquals(max, text.getMaxLines());
					assertSame(bold, ((Spanned) text.getText()).getSpans(0, content.length(), StyleSpan.class)[0]);
					assertTrue(text.isTextSelectable()); assertTrue(text.getLineCount() >= 3);
					if (!enabled) { assertEquals(original, text.getTypeface()); assertEquals(size, text.getTextSize(), .01f); }
				}
			}
		});
	}
	@Test public void commentDocumentRetainsItsOwnFoldingAcrossAppearanceChanges() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			CommentTextView text = new CommentTextView(context(1), null, android.R.attr.textAppearance);
			text.setText("First line\nSecond line\nThird line\nFourth line");
			boolean[] limited = {false}; text.setLimitListener(value -> limited[0] = value);
			text.setLinesLimit(1, 0); InterfaceAppearance.serviceDocument(text);
			for (boolean enabled : new boolean[]{true, false, true}) {
				resolve(text, enabled); measure(text, StandardAppearance.dp(text, 280));
				assertTrue(limited[0]); int foldedHeight = text.getHeight();
				text.setLinesLimit(0, 0); measure(text, StandardAppearance.dp(text, 280));
				assertFalse(limited[0]); assertTrue(text.getHeight() > foldedHeight);
				text.setLinesLimit(1, 0);
			}
		});
	}
	@Test public void independentlyDisabledMetadataRemainsDisabledAndCanBeRecycled() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1);
			ViewFactory.TwoLinesViewHolder h = ViewFactory.makeTwoLinesListItem(new FrameLayout(context), 0);
			h.text1.setText("Fixture cookie title"); h.text2.setText("Fixture value");
			h.text1.setEnabled(false); h.text2.setEnabled(false);
			InterfaceAppearance.serviceRow(h.view, h.text1, h.text2, null); resolve(h.view, true);
			assertTrue(h.view.isEnabled()); assertFalse(h.text1.isEnabled()); assertFalse(h.text1.isDuplicateParentStateEnabled());
			int disabled = h.text1.getCurrentTextColor();
			h.text1.setEnabled(true); h.text2.setEnabled(true);
			InterfaceAppearance.serviceRow(h.view, h.text1, h.text2, null); resolve(h.view, true);
			assertTrue(h.text1.isEnabled()); assertTrue(h.text1.isDuplicateParentStateEnabled());
			assertNotEquals(disabled, h.text1.getCurrentTextColor());
		});
	}
	@Test public void standaloneServiceHeaderDoesNotOptLegacyFactoryConsumersIn() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			FrameLayout parent = new FrameLayout(context(1));
			TextView legacy = ViewFactory.makeListTextHeader(parent);
			TextView opted = ViewFactory.makeListTextHeader(parent, true);
			int legacyMin = legacy.getMinHeight(), legacyTop = legacy.getPaddingTop();
			InterfaceAppearance.refreshTree(legacy); resolve(opted, true);
			assertNull(legacy.getTag(R.id.interface_appearance_state)); assertNull(legacy.getTag(R.id.standard_service_appearance));
			assertEquals(legacyMin, legacy.getMinHeight()); assertEquals(legacyTop, legacy.getPaddingTop());
			assertEquals(0, opted.getMinHeight()); assertEquals(StandardAppearance.dp(opted, 16), opted.getPaddingStart());
			resolve(opted, false); assertEquals(legacyMin, opted.getMinHeight()); assertEquals(legacyTop, opted.getPaddingTop());
		});
	}
}
