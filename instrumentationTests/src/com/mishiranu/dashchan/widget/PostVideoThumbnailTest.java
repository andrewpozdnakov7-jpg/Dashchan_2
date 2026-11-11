package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.Preferences;
import java.util.function.Consumer;
import org.junit.Test;

/** Prepared device tests. Requires system animators enabled for the ownership/fade cases. */
public class PostVideoThumbnailTest {
	private static final String RESOURCE = "fixture:post-video-style";
	private static Bitmap frame(int color) {
		Bitmap image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
		image.eraseColor(color); return image;
	}
	private static int center(AttachmentView view) {
		Bitmap image = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
		try { view.draw(new Canvas(image)); return image.getPixel(120, 120); }
		finally { image.recycle(); }
	}
	private static void restore(String key, Object value) {
		if (value instanceof Boolean) Preferences.PREFERENCES.edit().put(key, (Boolean) value).close();
		else Preferences.PREFERENCES.edit().remove(key).close();
	}
	private static void withView(Consumer<AttachmentView> action) {
		withView(action, view -> {});
	}
	private static void withView(Consumer<AttachmentView> action, Consumer<AttachmentView> afterDeadline) {
		Object oldUpdate = Preferences.PREFERENCES.getAll().get(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS);
		Object oldMotion = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
		AttachmentView[] holder = {null};
		Bitmap original = frame(Color.GREEN);
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			try {
				scenario.onActivity(activity -> {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, true)
							.put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					AttachmentView view = new AttachmentView(ThemeEngine.attach(activity), null);
					view.setPostVideoStyle(true); view.setCropEnabled(true);
					view.resetImage("site", AttachmentView.Overlay.VIDEO);
					view.setMediaResource(RESOURCE);
					view.handleLoadedImage("site", original, false, true);
					FrameLayout root = new FrameLayout(activity);
					root.addView(view, new FrameLayout.LayoutParams(240, 240));
					activity.setContentView(root); holder[0] = view;
					VideoThumbnailPreview.bind(view, RESOURCE);
				});
				InstrumentationRegistry.getInstrumentation().waitForIdleSync();
				scenario.onActivity(activity -> {
					AttachmentView view = holder[0]; view.layout(0, 0, 240, 240);
					assertTrue(view.isAttachedToWindow()); assertTrue(view.isShown());
					action.accept(view);
				});
				// Main-thread deadline must release a lease even when no draw is requested by the test.
				SystemClock.sleep(240);
				InstrumentationRegistry.getInstrumentation().waitForIdleSync();
				scenario.onActivity(activity -> {
					afterDeadline.accept(holder[0]);
					VideoThumbnailPreview.invalidate(RESOURCE);
				});
			} finally {
				scenario.onActivity(activity -> {
					VideoThumbnailPreview.invalidate(RESOURCE);
					if (holder[0] != null) VideoThumbnailPreview.unbind(holder[0]);
					restore(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, oldUpdate);
					restore(Preferences.KEY_NEW_INTERFACE_MOTION, oldMotion);
				});
			}
		} finally { original.recycle(); }
	}
	@Test public void centerOfVideoFrameIsNotDimmedAndGroupedOverlayKeepsItsStyle() {
		withView(view -> {
			assertEquals(Color.GREEN, center(view));
			view.resetImage("site", AttachmentView.Overlay.MULTIPLE);
			assertNotEquals(Color.GREEN, center(view));
		});
	}
	@Test public void invalidateClearsBothCurrentFrameAndAnActiveTransitionLease() {
		assumeTrue(ValueAnimator.areAnimatorsEnabled() && BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION);
		withView(view -> {
			Bitmap first = frame(Color.RED), next = frame(Color.BLUE);
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> first));
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> next));
			assertFalse(first.isRecycled()); assertFalse(next.isRecycled());
			VideoThumbnailPreview.invalidate(RESOURCE);
			assertTrue(first.isRecycled()); assertTrue(next.isRecycled());
			assertEquals(Color.GREEN, center(view));
		});
	}
	@Test public void rapidUpdatesAndDetachReleaseOnlySupersededFrames() {
		assumeTrue(ValueAnimator.areAnimatorsEnabled() && BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION);
		withView(view -> {
			Bitmap first = frame(Color.RED), second = frame(Color.BLUE), third = frame(Color.YELLOW);
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> first));
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> second));
			assertFalse(first.isRecycled());
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> third));
			assertTrue(first.isRecycled()); assertFalse(second.isRecycled());
			((FrameLayout) view.getParent()).removeView(view);
			assertTrue(second.isRecycled()); assertFalse(third.isRecycled());
			VideoThumbnailPreview.invalidate(RESOURCE); assertTrue(third.isRecycled());
		});
	}
	@Test public void cachedRebindSettlesAnActiveFadeAndReleasesItsPreviousFrame() {
		assumeTrue(ValueAnimator.areAnimatorsEnabled() && BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION);
		withView(view -> {
			Bitmap first = frame(Color.RED), second = frame(Color.BLUE);
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> first));
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> second));
			assertFalse(first.isRecycled());
			VideoThumbnailPreview.bind(view, RESOURCE);
			assertTrue(first.isRecycled()); assertFalse(second.isRecycled());
			assertEquals(Color.BLUE, center(view));
		});
	}
	@Test public void turningOffUpdatesDuringFadeReleasesBothFrames() {
		assumeTrue(ValueAnimator.areAnimatorsEnabled() && BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION);
		withView(view -> {
			Bitmap first = frame(Color.RED), second = frame(Color.BLUE);
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> first));
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> second));
			Preferences.PREFERENCES.edit().put(Preferences.KEY_UPDATE_VIDEO_THUMBNAILS, false).close();
			assertTrue(first.isRecycled()); assertTrue(second.isRecycled());
			assertEquals(Color.GREEN, center(view));
		});
	}
	@Test public void deadlineReleasesPreviousFrameWithoutAnotherDraw() {
		assumeTrue(ValueAnimator.areAnimatorsEnabled() && BuildConfig.ENABLE_EXPERIMENTAL_INTERFACE_MOTION);
		Bitmap first = frame(Color.RED), second = frame(Color.BLUE);
		withView(view -> {
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> first));
			assertTrue(VideoThumbnailPreview.publish(view.getContext(), RESOURCE, () -> second));
			assertFalse(first.isRecycled());
		}, view -> {
			assertTrue(first.isRecycled()); assertFalse(second.isRecycled());
			assertEquals(Color.BLUE, center(view));
		});
	}
}
