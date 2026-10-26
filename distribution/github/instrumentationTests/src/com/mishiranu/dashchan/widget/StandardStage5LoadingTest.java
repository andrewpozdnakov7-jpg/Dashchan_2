package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;

/** Prepared, not run. Native local fixtures only; never signs in, posts, deletes or loads a remote URL. */
public class StandardStage5LoadingTest {
	@Test public void reducedMotionKeepsVisibleWaitingGlyphButStopsScheduledMotionAndDetach() {
		ProgressBar[] waiting = new ProgressBar[1]; StandardLoadingDrawable[] drawable = new StandardLoadingDrawable[1];
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(a -> {
				FrameLayout host = new FrameLayout(PostingTestContext.create(a));
				waiting[0] = new ProgressBar(host.getContext()); waiting[0].setIndeterminate(true);
				drawable[0] = new StandardLoadingDrawable(waiting[0], 20, 14); waiting[0].setIndeterminateDrawable(drawable[0]);
				host.addView(waiting[0], new FrameLayout.LayoutParams(80, 80)); a.setContentView(host);
			});
			InstrumentationRegistry.getInstrumentation().waitForIdleSync();
			scenario.onActivity(a -> {
				assertTrue(waiting[0].hasWindowFocus()); drawable[0].start(); drawable[0].sync(false);
				assertFalse(drawable[0].isRunning()); assertEquals(1, StandardStage5DialogsTest.field(drawable[0], "mode"));
				assertTrue(((Drawable) StandardStage5DialogsTest.field(drawable[0], "child")).isVisible());
				assertTrue(((java.util.Set<?>) StandardStage5DialogsTest.field(drawable[0], "scheduled")).isEmpty());
				((ViewGroup) waiting[0].getParent()).removeView(waiting[0]); drawable[0].sync(false);
				assertEquals(0, StandardStage5DialogsTest.field(drawable[0], "mode"));
			});
		}
	}
	@Test public void compactWaitingKeepsToolbarBudgetOwnerAndOffRestoresOriginal() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> StandardCustomContentTest.profile(true, () -> {
			Context c = PostingTestContext.create(); FrameLayout row = new FrameLayout(c);
			ProgressBar view = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
			view.setIndeterminate(true); Drawable legacy = view.getIndeterminateDrawable();
			int side = StandardAppearance.dp(view, 20);
			row.addView(view, new FrameLayout.LayoutParams(side, side));
			InterfaceAppearance.standardCompactLoading(view);
			Drawable first = view.getIndeterminateDrawable(); InterfaceAppearance.standardCompactLoading(view);
			assertSame(first, view.getIndeterminateDrawable()); assertNotSame(legacy, first);
			row.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY));
			row.layout(0, 0, side, side); assertEquals(side, view.getWidth()); assertEquals(side, view.getHeight());
			StandardLoadingDrawable waiting = (StandardLoadingDrawable) first;
			waiting.setBounds(0, 0, side, side); assertTrue(waiting.indicatorBounds().width() <= side);
			waiting.start(); assertFalse(waiting.isRunning()); // detached hosts never animate
			Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, false).close(); InterfaceAppearance.refreshTree(row);
			assertSame(legacy, view.getIndeterminateDrawable()); assertEquals(side, view.getLayoutParams().height);
		}));
	}
	@Test public void popupLoadingDoesNotCoverCompletedContentAndDifferentHostsNeverShareDrawable() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> StandardCustomContentTest.profile(true, () -> {
			Context c = PostingTestContext.create(); FrameLayout popup = new FrameLayout(c);
			TextView posts = new TextView(c); posts.setText("Synthetic loaded posts"); popup.addView(posts);
			View layer = ViewFactory.createProgressLayout(popup, true, true);
			ProgressBar first = StandardCustomContentTest.find(layer, ProgressBar.class);
			ProgressBar second = new ProgressBar(c); InterfaceAppearance.standardDialogLoading(second);
			assertNotSame(first.getIndeterminateDrawable(), second.getIndeterminateDrawable());
			assertEquals(View.GONE, layer.getVisibility()); layer.setVisibility(View.VISIBLE);
			((StandardLoadingDrawable) first.getIndeterminateDrawable()).start();
			layer.setVisibility(View.GONE); ((StandardLoadingDrawable) first.getIndeterminateDrawable()).sync();
			assertFalse(((StandardLoadingDrawable) first.getIndeterminateDrawable()).isRunning());
			assertEquals(View.VISIBLE, posts.getVisibility()); assertEquals("Synthetic loaded posts", posts.getText().toString());
		}));
	}
	@Test public void linearProgressUsesActualValueAndRtlWithoutIndeterminateMotionOrDelayedCompletion() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> StandardCustomContentTest.profile(true, () -> {
			Context c = PostingTestContext.create(); LinearLayout root = new LinearLayout(c); ProgressBar view = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
			root.addView(view, new LinearLayout.LayoutParams(200, 3)); view.setMax(100); view.setProgress(37);
			Drawable legacy = view.getProgressDrawable(); InterfaceAppearance.standardLinearProgress(view);
			assertFalse(view.isIndeterminate()); assertEquals(37, view.getProgress());
			NativeContentAppearance.Line line = (NativeContentAppearance.Line) ((android.graphics.drawable.LayerDrawable)
					view.getProgressDrawable()).findDrawableByLayerId(android.R.id.progress);
			assertEquals(3700, line.getLevel());
			view.layout(0, 0, 200, 10); Bitmap b = Bitmap.createBitmap(200, 10, Bitmap.Config.ARGB_8888);
			view.draw(new Canvas(b)); assertEquals(line.fill, b.getPixel(20, 5)); assertEquals(line.track, b.getPixel(180, 5));
			view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); InterfaceAppearance.refreshTree(root); b.eraseColor(0); view.draw(new Canvas(b));
			assertEquals(line.track, b.getPixel(20, 5)); assertEquals(line.fill, b.getPixel(180, 5));
			view.setProgress(100); assertEquals(10000, line.getLevel()); view.setProgress(0); assertEquals(0, line.getLevel());
			assertEquals(StandardAppearance.dp(view, 6), view.getLayoutParams().height);
			Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, false).close(); InterfaceAppearance.refreshTree(root);
			assertSame(legacy, view.getProgressDrawable()); assertEquals(3, view.getLayoutParams().height); b.recycle();
		}));
	}
	@Test public void nativePageDoesNotStyleWebDocumentOrReplaceButtonsAndHasNoModalWidthCap() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(a -> StandardCustomContentTest.profile(true, () -> {
				Context c = PostingTestContext.create(a); LinearLayout root = new LinearLayout(c); root.setOrientation(LinearLayout.VERTICAL);
				TextView status = new TextView(c); status.setText("Synthetic domain and state"); root.addView(status);
				WebView web = new WebView(c); root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
				web.loadDataWithBaseURL(null, "<html><body><label>Local fixture<input></label></body></html>", "text/html", "UTF-8", null);
				LinearLayout actions = new LinearLayout(c); Button check = new Button(c); check.setText("Check fixture");
				int[] clicks = {0}; check.setOnClickListener(v -> clicks[0]++); actions.addView(check); root.addView(actions);
				InterfaceAppearance.standardNativePanel(root, null, status, actions);
				AlertDialog d = MotionDialogBuilder.standardPage(c).setView(root).setNegativeButton(android.R.string.cancel, null).create();
				InterfaceAppearance.standardCustomDialogContent(d, root);
				try {
					d.show(); InterfaceAppearance.refreshTree(root); InterfaceAppearance.refreshTree(root); check.performClick();
					assertEquals(1, clicks[0]); assertEquals("Synthetic domain and state", status.getText().toString());
					assertNull(web.getTag(R.id.interface_appearance_state)); assertFalse(web.getSettings().getJavaScriptEnabled());
					assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, d.getWindow().getAttributes().width);
					assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, d.getWindow().getAttributes().height);
					assertTrue(check.isEnabled()); assertNotNull(d.getButton(AlertDialog.BUTTON_NEGATIVE));
				} finally { d.dismiss(); root.removeView(web); web.stopLoading(); web.destroy(); }
			}));
		}
	}
}
