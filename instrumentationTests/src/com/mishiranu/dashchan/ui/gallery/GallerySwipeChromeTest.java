package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GallerySwipeChromeTest {
	private static void onMain(java.util.function.Consumer<View> test) {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
				test.accept(new View(InstrumentationRegistry.getInstrumentation().getTargetContext())));
	}
	@Test public void cancelledSwipeRestoresOriginalAlphaWithoutAccumulatingFade() {
		onMain(view -> {
			GallerySwipeChrome chrome = new GallerySwipeChrome(); view.setAlpha(.8f);
			chrome.fade(.5f, view, view); assertEquals(.4f, view.getAlpha(), .0001f);
			chrome.fade(.25f, view); assertEquals(.2f, view.getAlpha(), .0001f);
			chrome.fade(1f, view); chrome.reset(); assertEquals(.8f, view.getAlpha(), 0f);
		});
	}
	@Test public void completedSwipeHidesTitleAndHiddenTitleNeverBecomesVisible() {
		onMain(view -> {
			GallerySwipeChrome chrome = new GallerySwipeChrome();
			view.setVisibility(View.GONE); chrome.fade(.5f, view); chrome.fade(0f, view);
			assertEquals(0f, view.getAlpha(), 0f); assertEquals(View.GONE, view.getVisibility());
			chrome.reset(); assertEquals(1f, view.getAlpha(), 0f);
			assertEquals(View.GONE, view.getVisibility());
		});
	}
	@Test public void resetDoesNotOverwriteNewerPresentation() {
		onMain(view -> {
			GallerySwipeChrome chrome = new GallerySwipeChrome(); chrome.fade(.5f, view);
			view.setAlpha(.7f); chrome.reset(); assertEquals(.7f, view.getAlpha(), 0f);
			Object owner = new Object(); view.setTag(R.id.gallery_motion_owner, owner);
			chrome.fade(0f, view); chrome.reset();
			assertEquals(.7f, view.getAlpha(), 0f); assertSame(owner, view.getTag(R.id.gallery_motion_owner));
		});
	}
}
