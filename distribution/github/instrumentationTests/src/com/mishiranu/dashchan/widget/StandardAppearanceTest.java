package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SearchView;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared detached fixtures. No preference writes, network, real files or Activity launch. */
@RunWith(AndroidJUnit4.class)
public class StandardAppearanceTest {
	private Context context(float scale) {
		Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = scale;
		return PostingTestContext.create(base.createConfigurationContext(config));
	}
	private void resolve(View view, boolean enabled) {
		Object state = view.getTag(R.id.interface_appearance_state);
		if (state instanceof InterfaceAppearance.State) InterfaceAppearance.renderResolved(view, (InterfaceAppearance.State) state, enabled);
		if (view instanceof android.view.ViewGroup) {
			android.view.ViewGroup group = (android.view.ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) resolve(group.getChildAt(i), enabled);
		}
		StandardAppearance.resolveLayout(view, enabled);
	}
	private void layout(View view, int width) {
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
	}
	private android.widget.EditText input(View view) {
		if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
		if (view instanceof android.view.ViewGroup) {
			android.view.ViewGroup group = (android.view.ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) {
				android.widget.EditText found = input(group.getChildAt(i)); if (found != null) return found;
			}
		}
		return null;
	}
	@Test public void settingsSummaryGrowsWithoutClippingAndHidingItReleasesSpace() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (float scale : new float[]{1f, 1.3f, 2f}) for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				Context context = context(scale); FrameLayout parent = new FrameLayout(context);
				ViewFactory.TwoLinesViewHolder h = ViewFactory.makeTwoLinesListItem(parent, ViewFactory.FEATURE_WIDGET);
				h.view.setLayoutDirection(direction); h.text1.setText("A long setting title without losing its trailing control");
				h.text2.setText("A long explanation that must remain readable and grow the row instead of being clipped to a fixed height.");
				h.widgetFrame.setVisibility(View.VISIBLE); h.widgetFrame.addView(new CheckBox(context));
				InterfaceAppearance.settingsRow(h.view, h.text1, h.text2, h.widgetFrame, false); resolve(h.view, true);
				layout(h.view, StandardAppearance.dp(h.view, 240)); int fullHeight = h.view.getHeight();
				assertTrue(fullHeight >= StandardAppearance.dp(h.view, 64));
				assertTrue(h.text2.getHeight() >= h.text2.getLayout().getHeight());
				assertEquals(StandardAppearance.dp(h.view, 12), ((LinearLayout.LayoutParams) h.widgetFrame.getLayoutParams()).getMarginStart());
				h.text2.setText(""); h.text2.setVisibility(View.GONE); h.text1.setText("Short");
				InterfaceAppearance.settingsRow(h.view, h.text1, h.text2, h.widgetFrame, false); resolve(h.view, true);
				layout(h.view, StandardAppearance.dp(h.view, 240));
				assertEquals(StandardAppearance.dp(h.view, 56), h.view.getMinimumHeight());
				assertTrue(h.view.getHeight() < fullHeight);
			}
		});
	}
	@Test public void settingsReapplyRestorePreservesSpansAndDoesNotAccumulateSpacing() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1); FrameLayout parent = new FrameLayout(context);
			ViewFactory.TwoLinesViewHolder h = ViewFactory.makeTwoLinesListItem(parent, 0);
			h.text1.setTextSize(19); h.text1.setTypeface(Typeface.MONOSPACE); h.text1.setLineSpacing(3, 1.1f);
			float original = h.text1.getTextSize(); int top = h.view.getPaddingTop();
			android.graphics.drawable.Drawable background = h.view.getBackground();
			SpannableString highlighted = new SpannableString("Found setting");
			BackgroundColorSpan span = new BackgroundColorSpan(0xffffff00); highlighted.setSpan(span, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			h.text1.setText(highlighted); h.text2.setText("Summary");
			InterfaceAppearance.settingsRow(h.view, h.text1, h.text2, null, false);
			resolve(h.view, true); int padded = h.view.getPaddingTop();
			for (int i = 0; i < 5; i++) { resolve(h.view, true); assertEquals(padded, h.view.getPaddingTop()); }
			assertEquals(1, ((Spanned) h.text1.getText()).getSpans(0, 5, BackgroundColorSpan.class).length);
			resolve(h.view, false); assertEquals(original, h.text1.getTextSize(), .01f);
			assertEquals(Typeface.MONOSPACE, h.text1.getTypeface()); assertEquals(3, h.text1.getLineSpacingExtra(), .01f);
			assertEquals(top, h.view.getPaddingTop());
			assertSame(background, h.view.getBackground());
		});
	}
	@Test public void stylingCheckPreferenceDoesNotWriteOrDoubleToggleAndKeepsDisabledDependency() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1); CheckPreference preference = new CheckPreference(context, null, false, "Toggle", "Explanation");
			CheckPreference.CheckViewHolder h = preference.createViewHolder(new FrameLayout(context));
			int[] clicks = {0}; preference.setOnClickListener(p -> { clicks[0]++; p.setValue(!p.getValue()); });
			h.view.setOnClickListener(v -> preference.performClick()); preference.bindViewHolder(h);
			InterfaceAppearance.settingsRow(h.view, h.title, h.summary, h.widgetFrame, false); resolve(h.view, true);
			assertEquals(0, clicks[0]); assertFalse(h.check.isClickable()); assertFalse(h.check.isFocusable());
			h.view.performClick(); assertEquals(1, clicks[0]); assertTrue(preference.getValue());
			preference.setEnabled(false); preference.bindViewHolder(h);
			InterfaceAppearance.settingsRow(h.view, h.title, h.summary, h.widgetFrame, false); resolve(h.view, true);
			assertFalse(h.view.isEnabled()); assertFalse(h.check.isEnabled()); assertEquals(1, clicks[0]);
		});
	}
	@Test public void nativeSearchStylingDoesNotEmitQueryChangeSubmitOrLoseSelection() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(1); FrameLayout root = new FrameLayout(context); SearchView search = new SearchView(context);
			root.addView(search); search.setIconified(false); search.setQuery("проверка test", false);
			android.widget.EditText input = input(search); assertNotNull(input); input.setSelection(2, 5);
			int[] calls = {0, 0}; search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
				@Override public boolean onQueryTextChange(String text) { calls[0]++; return true; }
				@Override public boolean onQueryTextSubmit(String text) { calls[1]++; return true; }
			});
			InterfaceAppearance.standardSearch(root, search);
			for (int i = 0; i < 3; i++) StandardAppearance.resolveSearch(root, true);
			assertEquals("проверка test", search.getQuery().toString()); assertArrayEquals(new int[]{0, 0}, calls);
			assertEquals(2, input.getSelectionStart()); assertEquals(5, input.getSelectionEnd());
			StandardAppearance.resolveSearch(root, false); assertEquals("проверка test", search.getQuery().toString());
			assertArrayEquals(new int[]{0, 0}, calls);
		});
	}
	@Test public void serviceRebindReleasesMetadataSpaceAndKeepsLatestSelection() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(2); ViewFactory.TwoLinesViewHolder h = ViewFactory.makeTwoLinesListItem(
					new FrameLayout(context), ViewFactory.FEATURE_TEXT2_END, true);
			h.text1.setText("Archive title"); h.text2.setText("Board, forum and a long external source name"); h.text2End.setText("21.10.2026");
			InterfaceAppearance.serviceRow(h.view, h.text1, h.text2, h.text2End); resolve(h.view, true); h.view.setActivated(true);
			layout(h.view, StandardAppearance.dp(h.view, 200));
			assertTrue(h.view.getHeight() >= StandardAppearance.dp(h.view, 56));
			h.view.setActivated(false); h.text1.setText("Next row"); h.text2.setText(""); h.text2.setVisibility(View.GONE); h.text2End.setText("");
			InterfaceAppearance.serviceRow(h.view, h.text1, h.text2, h.text2End); resolve(h.view, true);
			assertFalse(h.view.isActivated()); assertEquals(StandardAppearance.dp(h.view, 48), h.view.getMinimumHeight());
			assertEquals("Next row", h.text1.getText().toString());
		});
	}
	@Test public void standardPagePaletteMatchesItsRealBackgroundIncludingAmoled() {
		for (boolean dark : new boolean[]{false, true}) for (int window : new int[]{0xffffffff, 0xff000000, 0x80334455}) {
			AppearanceColors c = AppearanceColors.standardInterface(dark, window, 0x8055aa33, 0xff777777, 0xffcc3300);
			assertEquals(AppearanceColors.Profile.STANDARD_INTERFACE, c.profile); assertEquals(c.window, c.surface);
			assertTrue(ColorUtils.calculateContrast(c.onSurface, c.surface) >= 4.5);
			assertTrue(ColorUtils.calculateContrast(c.onSurfaceVariant, c.surface) >= 4.5);
			if (window == 0xff000000) assertEquals(0xff000000, c.surface);
		}
	}
	@Test public void popupLabelKeepsNativeHeightSingleLineAndAlignmentWithChoiceControl() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (float scale : new float[]{1f, 2f}) {
				Context context = new android.view.ContextThemeWrapper(context(scale), R.style.Theme_Standard_Popup);
				org.junit.Assume.assumeTrue(InterfaceAppearance.isEnabled(context));
				assertTrue(StandardAppearance.isPopup(context));
				LinearLayout row = new LinearLayout(context); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
				row.setMinimumHeight(StandardAppearance.dp(row, 48));
				TextView label = new TextView(context); label.setSingleLine(true); label.setMaxLines(1);
				label.setEllipsize(android.text.TextUtils.TruncateAt.END);
				label.setText("Thread title"); int originalMinimum = label.getMinimumHeight();
				row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
				CheckBox check = new CheckBox(context); check.setChecked(true); check.setClickable(false);
				row.addView(check, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
				int[] clicks = {0}; check.setOnCheckedChangeListener((button, checked) -> clicks[0]++);
				for (int i = 0; i < 3; i++) { StandardAppearance.popupView(label); StandardAppearance.popupView(check); }
				layout(row, StandardAppearance.dp(row, 260));
				assertEquals(originalMinimum, label.getMinimumHeight()); assertEquals(1, label.getMaxLines());
				assertEquals(android.text.TextUtils.TruncateAt.END, label.getEllipsize());
				assertEquals(label.getTop() + label.getHeight() / 2f, check.getTop() + check.getHeight() / 2f, 1f);
				assertTrue(check.isChecked()); assertFalse(check.isClickable()); assertEquals(0, clicks[0]);
				assertNotNull(check.getButtonTintList());
				AppearanceColors c = StandardAppearance.colors(context);
				assertTrue(ColorUtils.calculateContrast(label.getCurrentTextColor(), c.surfaceContainer) >= 4.5);
			}
		});
	}
	@Test public void serviceMetadataUsesASecondLineWhenDateCannotFitWithoutOverlap() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(2); org.junit.Assume.assumeTrue(InterfaceAppearance.isEnabled(context));
			for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				ViewFactory.TwoLinesViewHolder h = ViewFactory.makeTwoLinesListItem(new FrameLayout(context), ViewFactory.FEATURE_TEXT2_END, true);
				h.view.setLayoutDirection(direction); h.text1.setText("Title");
				h.text2.setText("A long metadata description for the board and archive"); h.text2End.setText("21.10.2026");
				InterfaceAppearance.serviceRow(h.view, h.text1, h.text2, h.text2End);
				layout(h.view, StandardAppearance.dp(h.view, 200));
				assertTrue(h.text2End.getTop() >= h.text2.getBottom());
				assertTrue(h.text2End.getLeft() >= 0);
				assertTrue(h.text2End.getRight() <= ((View) h.text2End.getParent()).getWidth());
			}
		});
	}
	@Test public void popupBackgroundKeepsPremeasurePaddingWhenThePaletteIsApplied() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = new android.view.ContextThemeWrapper(context(1), R.style.Theme_Standard_Popup);
			View probe = new View(context);
			android.graphics.Rect before = new android.graphics.Rect(), after = new android.graphics.Rect();
			context.getDrawable(R.drawable.standard_popup_background).getPadding(before);
			StandardAppearance.popupBackground(probe, 0xffdde3e9).getPadding(after);
			assertEquals(before, after);
			assertEquals(StandardAppearance.dp(probe, 8), after.left); assertEquals(after.left, after.right);
			assertEquals(StandardAppearance.dp(probe, 8) * 2, after.top); assertEquals(after.top, after.bottom);
		});
	}
}
