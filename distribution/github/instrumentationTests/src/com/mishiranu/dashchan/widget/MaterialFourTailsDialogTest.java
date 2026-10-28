package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Prepared behavioral fixtures for M01/M02. Not executed in the source-only handoff. */
public class MaterialFourTailsDialogTest {
	@Test public void optionalTitleAndActionCombinationsRemainOptional() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create();
			for (int mask = 0; mask < 4; mask++) {
				MessageDialog.Builder builder = new MessageDialog.Builder(context).setMessage("Fixture");
				if ((mask & 1) != 0) builder.setPositiveButton(android.R.string.ok, null);
				if ((mask & 2) != 0) builder.setNegativeButton(android.R.string.cancel, null);
				MessageDialog dialog = builder.create();
				InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				assertEquals((mask & 1) != 0, dialog.findViewById(android.R.id.button1) != null);
				assertEquals((mask & 2) != 0, dialog.findViewById(android.R.id.button2) != null);
				assertEquals(View.GONE, dialog.findViewById(com.mishiranu.dashchan.R.id.standard_dialog_title).getVisibility());
				dialog.dismiss();
			}
		});
	}
	@Test public void formattedMessageKeepsSpansScrollableBodyAndFixedActionsAtBothFontScales() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			for (boolean enabled : new boolean[]{false, true}) StandardCustomContentTest.profile(enabled, () -> {
				for (float scale : new float[]{1, 2}) {
					Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
					Configuration config = new Configuration(base.getResources().getConfiguration()); config.fontScale = scale;
					Context context = PostingTestContext.create(base.createConfigurationContext(config));
					SpannableString message = new SpannableString(new String(new char[80]).replace("\0", "Long fixture text / Длинный текст\n"));
					StyleSpan span = new StyleSpan(android.graphics.Typeface.BOLD);
					message.setSpan(span, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
					for (boolean hasTitle : new boolean[]{false, true}) {
						MessageDialog dialog = new MessageDialog.Builder(context).setTitle(hasTitle ? "Fixture" : null)
								.setMessage(message).setPositiveButton(android.R.string.ok, null)
								.setNegativeButton(android.R.string.cancel, null).create();
						try {
							TextView body = dialog.findViewById(android.R.id.message);
							MessageDialog.Content content = (MessageDialog.Content) body.getParent().getParent();
							InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
							InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
							int width = StandardAppearance.dp(body, 320), height = StandardAppearance.dp(body, 280);
							content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
									View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
							content.layout(0, 0, width, content.getMeasuredHeight());
							assertEquals(message.toString(), body.getText().toString());
							assertEquals(1, ((Spanned) body.getText()).getSpans(0, 4, StyleSpan.class).length);
							assertEquals(0, ((Spanned) body.getText()).getSpanStart(span));
							assertTrue(body.getHeight() > content.scroll.getHeight());
							assertTrue(content.scroll.getHeight() > 0);
							View action = dialog.findViewById(android.R.id.button1);
							assertSame(content.actions, action.getParent()); assertTrue(action.getHeight() > 0);
							assertTrue(((View) content.actions).getBottom() <= content.getHeight());
						} finally { dialog.dismiss(); }
					}
				}
			});
		});
	}
	@Test public void positiveAndCancelKeepSingleDispatchAndRefreshDoesNotReplaceTheMessage() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				AtomicInteger clicks = new AtomicInteger(), cancels = new AtomicInteger();
				MessageDialog dialog = new MessageDialog.Builder(PostingTestContext.create(activity)).setMessage("Fixture")
						.setPositiveButton(android.R.string.ok, (d, which) -> {
							assertEquals(AlertDialog.BUTTON_POSITIVE, which); assertTrue(((MessageDialog) d).isShowing()); clicks.incrementAndGet();
						}).create();
				dialog.setOnCancelListener(d -> cancels.incrementAndGet()); dialog.show();
				CharSequence text = ((TextView) dialog.findViewById(android.R.id.message)).getText();
				InterfaceAppearance.refreshTree(dialog.getWindow().getDecorView());
				assertSame(text, ((TextView) dialog.findViewById(android.R.id.message)).getText());
				dialog.findViewById(android.R.id.button1).performClick();
				assertEquals(1, clicks.get()); assertEquals(0, cancels.get()); assertFalse(dialog.isShowing());
				dialog.show(); dialog.cancel(); assertEquals(1, clicks.get()); assertEquals(1, cancels.get());
			});
		}
	}
	private static final class ImageChoices extends BaseAdapter implements SpinnerAdapter {
		final Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
		@Override public int getCount() { return 3; }
		@Override public Object getItem(int p) { return "fixture-" + p; }
		@Override public long getItemId(int p) { return 900 + p; }
		@Override public boolean hasStableIds() { return true; }
		@Override public boolean areAllItemsEnabled() { return false; }
		@Override public boolean isEnabled(int p) { return p != 1; }
		@Override public View getView(int p, View recycled, ViewGroup parent) { return getDropDownView(p, recycled, parent); }
		@Override public View getDropDownView(int p, View recycled, ViewGroup parent) {
			LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(parent.getContext());
			if (row.getChildCount() == 0) {
				ImageView image = new ImageView(parent.getContext()); row.addView(image, 32, 32);
				TextView text = new TextView(parent.getContext()); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
			}
			((ImageView) row.getChildAt(0)).setImageBitmap(bitmap);
			((TextView) row.getChildAt(1)).setText("Long fixture label " + p + " that must retain its associated image");
			return row;
		}
	}
	@Test public void spinnerRepeatClickAdapterSwapDetachAndRestoreNeverKeepAnOldPopup() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				SecureSpinner spinner = new SecureSpinner(PostingTestContext.create(activity));
				android.widget.ArrayAdapter<String> source = new android.widget.ArrayAdapter<>(spinner.getContext(),
						android.R.layout.simple_spinner_item, new String[]{"Fixture A", "Fixture B"});
				source.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
				spinner.setAdapter(source); activity.setContentView(spinner);
				assertTrue(spinner.performClick());
				AlertDialog first = (AlertDialog) StandardCustomContentTest.member(spinner, "selectionDialog");
				assertTrue(first.isShowing()); assertTrue(spinner.performClick());
				assertSame(first, StandardCustomContentTest.member(spinner, "selectionDialog"));
				spinner.setAdapter(source); assertFalse(first.isShowing());
				assertTrue(spinner.performClick());
				AlertDialog second = (AlertDialog) StandardCustomContentTest.member(spinner, "selectionDialog");
				android.os.Parcelable closed = spinner.onSaveInstanceState();
				activity.setContentView(new android.widget.FrameLayout(activity)); assertFalse(second.isShowing());
				SecureSpinner restored = new SecureSpinner(spinner.getContext()); restored.setAdapter(source);
				restored.onRestoreInstanceState(closed); assertNull(StandardCustomContentTest.member(restored, "selectionDialog"));
			});
		}
	}
	@Test public void spinnerRetainsComplexRowsDisabledIndicesIdsAndAdapterSwapProtection() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> StandardCustomContentTest.profile(true, () -> {
				SecureSpinner spinner = new SecureSpinner(PostingTestContext.create(activity)); ImageChoices source = new ImageChoices();
				spinner.setAdapter(source); spinner.setSelection(0);
				AlertDialog dialog = spinner.createSelectionDialog(); dialog.show();
				try {
					ListView list = dialog.getListView(); assertEquals(0, list.getHeaderViewsCount());
					assertEquals(3, list.getCount()); assertTrue(list.getAdapter().hasStableIds());
					assertEquals(902, list.getAdapter().getItemId(2)); assertTrue(list.isItemChecked(0));
					View first = list.getAdapter().getView(0, null, list);
					View disabled = list.getAdapter().getView(1, first, list); assertFalse(disabled.isEnabled());
					View reused = list.getAdapter().getView(2, disabled, list); assertTrue(reused.isEnabled());
					ImageView image = StandardCustomContentTest.find(reused, ImageView.class);
					assertSame(source.bitmap, ((BitmapDrawable) image.getDrawable()).getBitmap()); assertNull(image.getImageTintList());
					list.performItemClick(disabled, 1, 901); assertEquals(0, spinner.getSelectedItemPosition());
				} finally { dialog.dismiss(); }
				AlertDialog stale = spinner.createSelectionDialog(); stale.show();
				spinner.setAdapter(new ImageChoices());
				stale.getListView().performItemClick(null, 2, 902); assertEquals(0, spinner.getSelectedItemPosition()); stale.dismiss();
				spinner.setEnabled(false); assertNull(spinner.createSelectionDialog());
				spinner.setEnabled(true); spinner.setAdapter(new android.widget.ArrayAdapter<String>(spinner.getContext(), android.R.layout.simple_spinner_item));
				assertNull(spinner.createSelectionDialog());
				source.bitmap.recycle();
			}));
		}
	}
}
