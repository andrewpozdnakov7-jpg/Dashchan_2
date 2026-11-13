package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.widget.BackPreviewBudget;
import com.mishiranu.dashchan.widget.StandardContentTestActivity;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import org.junit.Test;

/** Real grid/cache hooks, no media/network. Prepared only; requires device execution. */
public class PredictiveGridCaptureTest {
	private static void set(ListUnit list, String name, Object value) {
		try { Field f = ListUnit.class.getDeclaredField(name); f.setAccessible(true); f.set(list, value); }
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static void call(ListUnit list, String name) {
		try { Method m = ListUnit.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(list); }
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static ListUnit create(StandardContentTestActivity activity) {
		GalleryInstance.Callback callback = (GalleryInstance.Callback) Proxy.newProxyInstance(
				GalleryInstance.Callback.class.getClassLoader(), new Class<?>[] {GalleryInstance.Callback.class},
				(proxy, method, arguments) -> {
					if (method.getReturnType() == boolean.class) return false;
					if (method.getReturnType() == void.class) return null;
					throw new AssertionError("Unexpected gallery callback: " + method.getName());
				});
		ListUnit list = new ListUnit(new GalleryInstance(activity, callback, Color.BLACK, null, new java.util.ArrayList<>(), null));
		FrameLayout root = new FrameLayout(activity);
		root.addView(list.getRecyclerView(), new FrameLayout.LayoutParams(240, 360)); activity.setContentView(root);
		list.switchMode(true, 0); return list;
	}
	private static void withGrid(java.util.function.Consumer<ListUnit> action) {
		Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_PREDICTIVE_BACK); ListUnit[] list = {null};
		try {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
					Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, true).close());
			try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
				scenario.onActivity(a -> list[0] = create(a)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
				scenario.onActivity(a -> {
					try { action.accept(list[0]); }
					finally { list[0].cancelPendingGridOpen(); list[0].clearPredictiveGrid(); }
				});
			}
		} finally {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
				if (previous instanceof Boolean) Preferences.PREFERENCES.edit().put(Preferences.KEY_PREDICTIVE_BACK, (Boolean) previous).close();
				else Preferences.PREFERENCES.edit().remove(Preferences.KEY_PREDICTIVE_BACK).close();
			});
		}
	}
	@Test public void unsettledRecaptureKeepsTheCompatibleOldAllocationAndItsItem() {
		withGrid(list -> {
			View grid = list.getRecyclerView(); GalleryItem item = new GalleryItem(Uri.parse("file:///grid-fixture.png"), null, null);
			Bitmap frame = BackPreviewBudget.capture(grid, 1024L * 1024, Color.BLACK); assertNotNull(frame);
			set(list, "predictiveGrid", frame); set(list, "predictiveGridWidth", grid.getWidth());
			set(list, "predictiveGridHeight", grid.getHeight()); set(list, "predictiveGridDirection", grid.getLayoutDirection());
			set(list, "predictiveGridColumns", ((GridLayoutManager) list.getRecyclerView().getLayoutManager()).getSpanCount());
			set(list, "predictiveGridFirstPosition", ((GridLayoutManager) list.getRecyclerView().getLayoutManager()).findFirstVisibleItemPosition());
			set(list, "predictiveGridFirstTop", 0);
			set(list, "predictiveGridTheme", ThemeEngine.getTheme(grid.getContext()));
			set(list, "predictiveGridInsets", grid.getRootWindowInsets());
			set(list, "predictiveGridGeometry", new PredictiveGridGeometry(list.getRecyclerView()));
			try {
				Field f = ListUnit.class.getDeclaredField("predictiveGridItems"); f.setAccessible(true);
				@SuppressWarnings("unchecked") IdentityHashMap<GalleryItem, Boolean> items = (IdentityHashMap<GalleryItem, Boolean>) f.get(list);
				items.put(item, true);
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
			assertSame(frame, list.getPredictiveGrid(item)); grid.requestLayout(); call(list, "preparePredictiveGrid");
			assertFalse(frame.isRecycled()); assertSame(frame, list.getPredictiveGrid(item));
		});
	}
	@Test public void cancelledDeferredOpenCannotRunLaterAndLatestRequestRunsOnlyOnce() {
		withGrid(list -> {
			int[] first = {0}, second = {0}; list.getRecyclerView().requestLayout();
			list.openPageWhenReady(() -> first[0]++); assertEquals(0, first[0]); list.cancelPendingGridOpen();
			call(list, "finishPendingGridOpen"); assertEquals(0, first[0]);
			list.openPageWhenReady(() -> first[0]++); list.openPageWhenReady(() -> second[0]++);
			call(list, "finishPendingGridOpen"); call(list, "finishPendingGridOpen");
			assertEquals(0, first[0]); assertEquals(1, second[0]);
		});
	}
}
