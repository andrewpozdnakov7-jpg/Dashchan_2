package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared source fixtures, not a claim that Android tests or real IME/Toolbar scenarios ran. */
@RunWith(AndroidJUnit4.class)
public class ElementMotionTest {
	private Context context() { return ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext()); }
	@Test public void finishingHideSettlesLatestStateAndOldCleanupCannotHideANewShow() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(context()); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
			ElementMotion.Visibility hide = new ElementMotion.Visibility(view, false, 3f, .8f, .9f, () -> true);
			view.setAlpha(.3f); view.setTranslationY(15f); view.setScaleX(.5f); hide.finish();
			assertEquals(View.GONE, view.getVisibility()); assertEquals(1f, view.getAlpha(), 0f);
			assertEquals(3f, view.getTranslationY(), 0f); assertEquals(.8f, view.getScaleX(), 0f);
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, view.getImportantForAccessibility());
			ElementMotion.Visibility show = new ElementMotion.Visibility(view, true, 3f, .8f, .9f, () -> true);
			view.setVisibility(View.VISIBLE); view.setAlpha(.6f); hide.finish();
			assertSame(show, view.getTag(R.id.element_motion_owner)); assertEquals(.6f, view.getAlpha(), 0f);
			show.finish(); assertEquals(View.VISIBLE, view.getVisibility()); assertEquals(1f, view.getAlpha(), 0f);
		});
	}
	@Test public void foreignVisibilityOwnerKeepsItsPoseAndTag() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(context()); ElementMotion.Visibility owner = new ElementMotion.Visibility(view, false, 0f, 1f, 1f, () -> true);
			Object foreign = new Object(); view.setTag(R.id.element_motion_owner, foreign); view.setAlpha(.7f); view.setTranslationY(9f);
			owner.finish(); assertSame(foreign, view.getTag(R.id.element_motion_owner));
			assertEquals(.7f, view.getAlpha(), 0f); assertEquals(9f, view.getTranslationY(), 0f);
		});
	}
	@Test public void interruptedHideDoesNotMakeTheNextShowInaccessible() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(context());
			view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
			ElementMotion.Visibility hide = new ElementMotion.Visibility(view, false, 0f, 1f, 1f, () -> true);
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view.getImportantForAccessibility());
			ElementMotion.visibility(view, true, 0f, true, () -> true);
			assertEquals(View.VISIBLE, view.getVisibility());
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, view.getImportantForAccessibility());
			hide.finish();
			assertEquals(View.VISIBLE, view.getVisibility());
			assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, view.getImportantForAccessibility());
		});
	}
	@Test public void cancelledHeightCannotMutateAReboundLayoutFromALateAnimatorFrame() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(context()); view.setLayoutParams(new FrameLayout.LayoutParams(100, 40));
			ValueAnimator old = (ValueAnimator) ElementMotion.height(view, 40, 200, ViewGroup.LayoutParams.WRAP_CONTENT);
			ElementMotion.finish(view); assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, view.getLayoutParams().height);
			FrameLayout.LayoutParams rebound = new FrameLayout.LayoutParams(100, 77); view.setLayoutParams(rebound);
			old.setCurrentFraction(1f); ElementMotion.finish(view);
			assertSame(rebound, view.getLayoutParams()); assertEquals(77, rebound.height);
			assertNull(view.getTag(R.id.element_motion_owner));
		});
	}
	@Test public void cancelledContentRestoresTheLiveViewAndRecyclesItsOutgoingImage() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			FrameLayout root = new FrameLayout(context()); View incoming = new View(root.getContext()); root.addView(incoming);
			incoming.setAlpha(.65f); incoming.setTranslationY(4f); incoming.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
			Bitmap image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
			ContentStateMotion.Session session = new ContentStateMotion.Session(root, incoming, image, new Rect(0, 0, 20, 20));
			session.bindIncoming(); assertEquals(0f, incoming.getAlpha(), 0f); session.finish(); session.finish(); session.commit();
			root.getViewTreeObserver().dispatchOnPreDraw();
			assertTrue(image.isRecycled()); assertNull(root.getTag(R.id.content_state_motion_owner));
			assertNull(incoming.getTag(R.id.content_state_motion_owner)); assertEquals(.65f, incoming.getAlpha(), 0f);
			assertEquals(4f, incoming.getTranslationY(), 0f); assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, incoming.getImportantForAccessibility());
		});
	}
	@Test public void contentCaptureIsBoundedAndDrawFailureHasNoImage() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(context()); view.layout(0, 0, 2000, 1200); Bitmap image = ContentStateMotion.capture(view);
			assertNotNull(image); try { assertTrue((long) image.getWidth() * image.getHeight() <= 400000); } finally { image.recycle(); }
			View failing = new View(context()) { @Override public void draw(Canvas canvas) { throw new IllegalStateException("capture"); } };
			failing.layout(0, 0, 100, 100); assertNull(ContentStateMotion.capture(failing));
		});
	}
	@Test public void iconCleanupKeepsViewGeometryAndCannotReplaceANewerDrawable() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ImageView view = new ImageView(context()); ColorDrawable from = new ColorDrawable(Color.RED), target = new ColorDrawable(Color.BLUE);
			ElementMotion.Icon icon = new ElementMotion.Icon(view, R.drawable.ic_play_arrow, target);
			icon.drawable = new ElementMotion.IconDrawable(from, target); view.setImageDrawable(icon.drawable); view.setTag(R.id.icon_motion_owner, icon);
			view.setAlpha(.45f); view.setScaleX(.75f); view.setTranslationY(5f); icon.finish();
			assertSame(target, view.getDrawable()); assertEquals(.45f, view.getAlpha(), 0f);
			assertEquals(.75f, view.getScaleX(), 0f); assertEquals(5f, view.getTranslationY(), 0f);
			ColorDrawable foreign = new ColorDrawable(Color.GREEN); view.setImageDrawable(foreign); icon.finish();
			assertSame(foreign, view.getDrawable()); assertSame(view, foreign.getCallback());
		});
	}
	@Test public void iconTransitionForwardsTintToItsCurrentDrawable() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ElementMotion.IconDrawable icon = new ElementMotion.IconDrawable(new ColorDrawable(Color.WHITE), new ColorDrawable(Color.BLACK));
			icon.setTintList(ColorStateList.valueOf(Color.RED)); icon.setBounds(0, 0, 32, 32); icon.progress = 1f;
			Bitmap image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
			try { icon.draw(new Canvas(image)); assertEquals(Color.RED, image.getPixel(16, 16)); } finally { icon.release(); image.recycle(); }
		});
	}
	@Test public void releasingImeHookPreservesUnanimatedPanelAndReplacesOnlyItsOwnRegistration() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			FrameLayout root = new FrameLayout(context()); View panel = new View(root.getContext()); root.addView(panel); panel.setTranslationY(7f);
			ImePanelMotion.attach(root, panel); Object first = root.getTag(R.id.ime_panel_motion_owner); assertNotNull(first);
			ImePanelMotion.attach(root, panel); assertNotSame(first, root.getTag(R.id.ime_panel_motion_owner));
			ImePanelMotion.finish(root); ImePanelMotion.release(root); ImePanelMotion.release(root);
			assertNull(root.getTag(R.id.ime_panel_motion_owner)); assertEquals(7f, panel.getTranslationY(), 0f);
		});
	}
}
