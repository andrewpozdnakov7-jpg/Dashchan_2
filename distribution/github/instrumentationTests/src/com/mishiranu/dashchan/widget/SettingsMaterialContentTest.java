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
	private static Object autohideView(String name, Class<?> parameter, Object argument) {
		try {
			java.lang.reflect.Method method = com.mishiranu.dashchan.ui.preference.AutohideFragment.class
					.getDeclaredMethod(name, parameter);
			method.setAccessible(true); return method.invoke(null, argument);
		} catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}
	private static ScopeCheckBox addScope(LinearLayout parent, int label) {
		try {
			java.lang.reflect.Method method = com.mishiranu.dashchan.ui.preference.AutohideFragment.class
					.getDeclaredMethod("addScopeCheckBox", LinearLayout.class, int.class);
			method.setAccessible(true); return (ScopeCheckBox) method.invoke(null, parent, label);
		} catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}
	private static float glyphCenter(ViewGroup screen, ScopeCheckBox check) {
		android.graphics.Rect bounds = new android.graphics.Rect(); check.getGlyphBounds(bounds);
		screen.offsetDescendantRectToMyCoords(check, bounds); return bounds.exactCenterX();
	}
	@Test public void actualAutohideHeaderAndRowGlyphsShareColumnsInBothDirections() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (float scale : new float[] {1, 2}) for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				LinearLayout screen = new LinearLayout(context(scale)); screen.setOrientation(LinearLayout.VERTICAL);
				screen.setLayoutDirection(direction);
				View header = (View) autohideView("makeHeaderView", ViewGroup.class, screen); screen.addView(header);
				ScopeCheckBox[][] checks = new ScopeCheckBox[3][2];
				int[] ids = {R.id.autohide_scope_original_post, R.id.autohide_scope_replies};
				for (int col = 0; col < 2; col++) checks[0][col] = (ScopeCheckBox)
						((ViewGroup) header.findViewById(ids[col])).getChildAt(0);
				for (int row = 1; row < 3; row++) {
					ViewFactory.TwoLinesViewHolder holder = ViewFactory.makeTwoLinesListItem(screen,
							ViewFactory.FEATURE_WIDGET | ViewFactory.FEATURE_MULTILINE_TITLE);
					holder.widgetFrame.setVisibility(View.VISIBLE); holder.widgetFrame.setOrientation(LinearLayout.HORIZONTAL);
					holder.text1.setText("Synthetic rule with a long expression"); holder.text2.setText("subject | comment");
					checks[row][0] = addScope(holder.widgetFrame, R.string.hide_in_original_post);
					checks[row][1] = addScope(holder.widgetFrame, R.string.hide_in_replies);
					screen.addView(holder.view); InterfaceAppearance.serviceRow(holder.view, holder.text1, holder.text2, null);
				}
				for (boolean modern : new boolean[] {true, true, false, true}) {
					resolve(screen, modern); measure(screen, 360, 1800);
					for (int col = 0; col < 2; col++) {
						float center = glyphCenter(screen, checks[0][col]);
						ViewGroup column = (ViewGroup) checks[0][col].getParent().getParent();
						View label = column.getChildAt(0);
						android.graphics.Rect labelBounds = new android.graphics.Rect(0, 0, label.getWidth(), label.getHeight());
						screen.offsetDescendantRectToMyCoords(label, labelBounds);
						assertEquals(labelBounds.exactCenterX(), center, 1f);
						for (int row = 0; row < 3; row++) {
							ScopeCheckBox check = checks[row][col];
							assertEquals(center, glyphCenter(screen, check), 1f);
							assertTrue(check.getWidth() >= StandardAppearance.dp(check, 48));
							assertTrue(check.getHeight() >= StandardAppearance.dp(check, 48));
						}
					}
				}
			}
		});
	}
	@Test public void scopeStatesDrawInsideOneGlyphAndBindingDoesNotClick() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ScopeCheckBox check = new ScopeCheckBox(context(1)); int[] clicks = {0};
			check.setContentDescription("Synthetic scope"); check.setOnClickListener(v -> clicks[0]++);
			measure(check, 48, 48);
			android.graphics.Rect glyph = new android.graphics.Rect(); check.getGlyphBounds(glyph);
			assertEquals(StandardAppearance.dp(check, 24), glyph.width());
			int[] center = new int[3];
			for (int state = 0; state < 3; state++) {
				check.setChecked(state == 1); check.setMixed(state == 2); check.refreshAppearance();
				android.graphics.Bitmap pixels = android.graphics.Bitmap.createBitmap(check.getWidth(), check.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
				try {
					check.draw(new android.graphics.Canvas(pixels));
					assertEquals(0, Color.alpha(pixels.getPixel(1, 1)));
					center[state] = pixels.getPixel(glyph.centerX(), glyph.centerY());
				} finally { pixels.recycle(); }
			}
			assertNotEquals(center[1], center[2]); assertEquals(0, clicks[0]);
			android.os.Parcelable saved = check.onSaveInstanceState();
			ScopeCheckBox restored = new ScopeCheckBox(check.getContext()); restored.onRestoreInstanceState(saved);
			assertTrue(restored.isMixed()); assertFalse(restored.isChecked());
			android.view.accessibility.AccessibilityNodeInfo node = restored.createAccessibilityNodeInfo();
			assertTrue(node.isCheckable());
			check.performClick(); assertEquals(1, clicks[0]);
		});
	}
	@Test public void downloadBodyWrapsLongFolderLabelsAndRestoresLegacySpacer() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View root = LayoutInflater.from(context(2)).inflate(R.layout.dialog_download_choice, null);
			ViewGroup body = (ViewGroup) ((ViewGroup) root).getChildAt(0);
			RadioButton common = root.findViewById(R.id.download_common);
			common.setText("Synthetic long common folder name with enough words to wrap on a compact screen");
			int[] clicks = {0}; common.setOnClickListener(v -> clicks[0]++);
			InterfaceAppearance.standardDownloadContent(body, true);
			for (int i = 0; i < 3; i++) { resolve(root, true); measure(root, 320, 1400); }
			assertEquals(View.GONE, root.findViewById(R.id.download_width_spacer).getVisibility());
			assertTrue(common.getLineCount() > 1); assertEquals(0, clicks[0]);
			resolve(root, false); assertEquals(View.VISIBLE, root.findViewById(R.id.download_width_spacer).getVisibility());
		});
	}
	@Test public void closedDropdownRebindingKeepsOneFieldBackgroundAndItsSelection() {
		Context context = context(2);
		org.junit.Assume.assumeTrue("Material profile required for this fixture", InterfaceAppearance.isEnabled(context));
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
				DropdownView root = new DropdownView(context); root.setLayoutDirection(direction);
				root.setItems(java.util.Arrays.asList("HTTP", "Synthetic long SOCKS selector value that must wrap")); root.setSelection(1);
				measure(root, 160, 900);
				android.widget.Spinner spinner = (android.widget.Spinner) root.getChildAt(0);
				assertTrue(spinner instanceof SecureSpinner); assertNull(spinner.getBackground());
				android.graphics.drawable.Drawable background = root.getBackground();
				for (int i = 0; i < 3; i++) {
					root.refreshAppearance(); measure(root, 160, 900);
					assertSame(background, root.getBackground()); assertEquals(1, root.getSelectedItemPosition());
					assertEquals(0, ((ViewGroup.MarginLayoutParams) spinner.getLayoutParams()).getMarginEnd());
					assertEquals(StandardAppearance.dp(root, 40), spinner.getPaddingEnd());
					assertTrue(((TextView) spinner.getSelectedView()).getLineCount() > 1);
				}
				root.setItems(java.util.Arrays.asList("HTTP", "SOCKS")); root.setSelection(1); measure(root, 160, 900);
				assertEquals(1, root.getSelectedItemPosition());
				root.setEnabled(false); assertFalse(spinner.isEnabled());
			}
		});
	}
}
