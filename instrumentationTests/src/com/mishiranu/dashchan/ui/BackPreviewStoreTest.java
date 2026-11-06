package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.ui.navigator.Page;
import com.mishiranu.dashchan.ui.navigator.PageFragment;
import com.mishiranu.dashchan.widget.BackPreviewBudget;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BackPreviewStoreTest {
	private FrameLayout host() {
		FrameLayout host = new FrameLayout(ThemeEngine.attach(
				InstrumentationRegistry.getInstrumentation().getTargetContext()));
		host.layout(0, 0, 100, 200);
		return host;
	}
	@Test public void cacheSurvivesControllerReplacementAndLateOldOwnerCannotPruneIt() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			BackPreviewStore store = new BackPreviewStore(true);
			FrameLayout firstHost = host();
			PredictiveBackController first = new PredictiveBackController(firstHost, () -> {}, store);
			Bitmap image = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
			String route = new String("saved-route");
			store.previews.put(route, new PredictiveBackController.Preview(image,
					new PredictiveBackController.Signature(firstHost)));
			PredictiveBackController second = new PredictiveBackController(host(), () -> {}, store);
			try {
				first.close(); first.close();
				first.clear(); first.retainReachable(Collections.emptySet());
				assertFalse(image.isRecycled());
				assertSame(image, store.previews.get(new String("saved-route")).image);
				second.retainReachable(Collections.singleton(new String("saved-route")));
				assertEquals(1, store.previews.size());
				second.close();
				assertFalse(image.isRecycled());
			} finally { first.close(); second.close(); store.close(); }
			assertTrue(image.isRecycled());
		});
	}
	@Test public void evictionProtectsRestoredEqualKeyAndClearingPreservesAnActiveLease() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			FrameLayout host = host(); BackPreviewStore store = new BackPreviewStore(true);
			View view = new View(host.getContext()); view.layout(0, 0, 100, 200);
			Bitmap leased = BackPreviewBudget.capture(view, 100 * 200 * 4, 0);
			assertNotNull(leased);
			Bitmap other = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
			PredictiveBackController.Signature signature = new PredictiveBackController.Signature(host);
			Object token = new Object(); store.attach(token);
			try {
				store.previews.put(new String("nearest"), new PredictiveBackController.Preview(leased, signature));
				store.previews.put("other", new PredictiveBackController.Preview(other, signature));
				store.protect(token, new String("nearest"));
				assertTrue(store.evictUnprotected("test_budget")); assertTrue(other.isRecycled());
				assertFalse(store.evictUnprotected("test_budget"));
				assertFalse(leased.isRecycled());
				assertTrue(BackPreviewBudget.retain(leased));
				store.clear("test_pressure");
				assertFalse(leased.isRecycled());
				BackPreviewBudget.release(leased);
				assertTrue(leased.isRecycled());
			} finally { store.close(); if (!leased.isRecycled()) BackPreviewBudget.release(leased); }
		});
	}
	@Test public void parcelRestorationKeepsAliasWithoutMutatingOriginalArgumentsAndReplacementGetsNewAlias() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ContentFragment original = new PageFragment(new Page(Page.Content.BOARDS, "test", null, null, null), "retain");
			String alias = original.getBackPreviewKey(); Bundle originalArguments = new Bundle(original.requireArguments());
			StackItem saved = new StackItem(PageFragment.class.getName(), original.copyBackPreviewArguments(), null);
			Parcel write = Parcel.obtain(), read = Parcel.obtain();
			try {
				saved.writeToParcel(write, 0); byte[] bytes = write.marshall();
				read.unmarshall(bytes, 0, bytes.length); read.setDataPosition(0);
				StackItem restored = StackItem.CREATOR.createFromParcel(read);
				assertEquals(alias, restored.backPreviewKey);
				ContentFragment page = (ContentFragment) restored.create(null);
				assertEquals(alias, page.getBackPreviewKey());
				assertEquals(originalArguments.keySet(), original.requireArguments().keySet());
				ContentFragment replacement = (ContentFragment) restored.create(fragment -> fragment);
				assertNotEquals(alias, replacement.getBackPreviewKey());
				assertEquals(alias, restored.backPreviewKey);
			} finally { write.recycle(); read.recycle(); }
		});
	}
	@Test public void serializingAnOldRestoredRouteRefreshesOnlyTheSavedCopyProcessMarker() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ContentFragment original = new PageFragment(new Page(Page.Content.BOARDS, "test", null, null, null), "retain");
			Bundle arguments = original.copyBackPreviewArguments();
			String markerKey = "slooop.internal.backPreviewProcess";
			String current = com.mishiranu.dashchan.media.VideoDiagnostics.backPreviewProcessMarker();
			assertEquals(current, arguments.getString(markerKey));
			arguments.putString(markerKey, "previous-test-process");
			StackItem saved = new StackItem(PageFragment.class.getName(), arguments, null);
			Parcel parcel = Parcel.obtain();
			try {
				saved.writeToParcel(parcel, 0); parcel.setDataPosition(0);
				StackItem restored = StackItem.CREATOR.createFromParcel(parcel);
				assertEquals(current, restored.arguments.getString(markerKey));
				assertEquals("previous-test-process", saved.arguments.getString(markerKey));
				assertEquals(saved.backPreviewKey, restored.backPreviewKey);
			} finally { parcel.recycle(); }
		});
	}
}
