package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Source handoff only: these Android tests have not been executed in the preparation environment. */
@RunWith(AndroidJUnit4.class)
public class SurfaceMotionTest {
	private Context context() { return ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext()); }
	private static int attribute(Resources.Theme theme, int attribute) {
		TypedValue value = new TypedValue(); assertTrue(theme.resolveAttribute(attribute, value, true));
		return value.resourceId != 0 ? value.resourceId : value.data;
	}
	@Test public void dialogPolicyPreservesDimAndLegacyWindowStyle() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			AlertDialog dialog = new AlertDialog.Builder(context()).setPositiveButton(android.R.string.ok, (d, w) -> {}).create();
			dialog.getWindow().setWindowAnimations(android.R.style.Animation_Dialog);
			float dim = dialog.getWindow().getAttributes().dimAmount;
			SurfaceMotion.configureDialog(dialog, false, true);
			assertEquals(android.R.style.Animation_Dialog, dialog.getWindow().getAttributes().windowAnimations);
			SurfaceMotion.configureDialog(dialog, true, true);
			assertEquals(R.style.Animation_Motion_Dialog, dialog.getWindow().getAttributes().windowAnimations);
			assertEquals(dim, dialog.getWindow().getAttributes().dimAmount, 0f);
			SurfaceMotion.configureDialog(dialog, true, false);
			assertEquals(0, dialog.getWindow().getAttributes().windowAnimations);
		});
	}
	@Test public void cachedPopupThemeRestoresOriginalPaletteAndStylesAfterPolicyToggle() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(); Resources.Theme theme = context.getResources().newTheme();
			theme.setTo(context.getTheme()); Resources.Theme original = context.getResources().newTheme(); original.setTo(theme);
			int text = attribute(original, android.R.attr.textColorPrimary), popup = attribute(original, android.R.attr.popupMenuStyle);
			SurfaceMotion.applyPopupPolicy(theme, original, true, true);
			assertEquals(R.style.Widget_Motion_Popup, attribute(theme, android.R.attr.popupMenuStyle));
			assertEquals(text, attribute(theme, android.R.attr.textColorPrimary));
			SurfaceMotion.applyPopupPolicy(theme, original, true, false);
			assertEquals(R.style.Widget_Motion_Popup_Static, attribute(theme, android.R.attr.popupMenuStyle));
			SurfaceMotion.applyPopupPolicy(theme, original, false, true);
			assertEquals(popup, attribute(theme, android.R.attr.popupMenuStyle));
			assertEquals(text, attribute(theme, android.R.attr.textColorPrimary));
		});
	}
	@Test public void inheritedMotionPopupThemeAlsoReturnsToNativeStyles() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = context(); Resources.Theme inherited = context.getResources().newTheme();
			inherited.setTo(context.getTheme()); inherited.applyStyle(R.style.Theme_Motion_Popup, true);
			Resources.Theme original = context.getResources().newTheme(); original.setTo(inherited);
			int text = attribute(original, android.R.attr.textColorPrimary);
			SurfaceMotion.applyPopupPolicy(inherited, original, false, true);
			assertEquals(android.R.style.Widget_Material_PopupMenu, attribute(inherited, android.R.attr.popupMenuStyle));
			assertEquals(text, attribute(inherited, android.R.attr.textColorPrimary));
		});
	}
	@Test public void popupFrameRestoresImplicitPivotAndDoesNotRestoreAForeignOwner() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(context()); view.layout(0, 0, 100, 60); view.setAlpha(.6f); view.setScaleX(.8f);
			assertFalse(view.isPivotSet()); PopupSurfaceTransition.Frame frame = new PopupSurfaceTransition.Frame(view);
			view.setTag(R.id.popup_motion_owner, frame); view.setPivotX(0f); view.setAlpha(0f); frame.release();
			assertFalse(view.isPivotSet()); assertEquals(.6f, view.getAlpha(), 0f); assertEquals(.8f, view.getScaleX(), 0f);
			PopupSurfaceTransition.Frame next = new PopupSurfaceTransition.Frame(view); Object foreign = new Object();
			view.setTag(R.id.popup_motion_owner, foreign); view.setAlpha(.25f); next.release();
			assertSame(foreign, view.getTag(R.id.popup_motion_owner)); assertEquals(.25f, view.getAlpha(), 0f);
		});
	}
	@Test public void cancelledSearchPreparationRecyclesImageAndPreservesQueryAndFocus() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			FrameLayout toolbar = new FrameLayout(context()); CustomSearchView search = new CustomSearchView(toolbar.getContext());
			toolbar.addView(search); search.setQuery("сохранённый запрос"); search.setAlpha(.7f);
			int[] changes = {0}; search.setOnChangeListener(query -> changes[0]++); boolean focused = search.isSearchFocused();
			Bitmap image = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
			ToolbarSearchMotion motion = new ToolbarSearchMotion(toolbar, image, false); motion.commit(); motion.run(); motion.run();
			toolbar.getViewTreeObserver().dispatchOnPreDraw();
			assertTrue(image.isRecycled()); assertNull(toolbar.getTag(R.id.toolbar_search_motion_owner));
			assertEquals("сохранённый запрос", search.getQuery()); assertEquals(0, changes[0]);
			assertEquals(focused, search.isSearchFocused()); assertEquals(.7f, search.getAlpha(), 0f);
		});
	}
	@Test public void failedSearchCaptureFallsBackAndSuccessfulCaptureIsBounded() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			FrameLayout toolbar = new FrameLayout(context()); toolbar.layout(0, 0, 2000, 200);
			Bitmap image = ToolbarSearchMotion.capture(toolbar); assertNotNull(image);
			try { assertTrue((long) image.getWidth() * image.getHeight() <= 180000); } finally { image.recycle(); }
			FrameLayout failing = new FrameLayout(context()) { @Override public void draw(Canvas canvas) { throw new IllegalStateException("capture"); } };
			failing.layout(0, 0, 100, 100); assertNull(ToolbarSearchMotion.capture(failing));
		});
	}
	@Test public void detachedExtraHasFinalHeightAndNoPermanentChildTransforms() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			MotionToolbarExtra extra = new MotionToolbarExtra(context(), null); View child = new View(extra.getContext());
			child.setAlpha(.6f); child.setTranslationY(3f); extra.addView(child, new FrameLayout.LayoutParams(100, 48));
			extra.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST));
			assertEquals(48, extra.getMeasuredHeight()); extra.finishPresentation(); extra.removeAllViews();
			extra.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST));
			assertEquals(0, extra.getMeasuredHeight()); assertEquals(.6f, child.getAlpha(), 0f); assertEquals(3f, child.getTranslationY(), 0f);
		});
	}
}
