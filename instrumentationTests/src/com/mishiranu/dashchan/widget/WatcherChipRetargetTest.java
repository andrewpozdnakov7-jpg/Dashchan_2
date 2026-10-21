package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.service.WatcherService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Deterministic software-Canvas composition fixtures, NOT hardware/device-animation acceptance. */
@RunWith(AndroidJUnit4.class)
public class WatcherChipRetargetTest {
	private void set(WatcherChipView view, String name, Object value) throws Exception {
		Field f = WatcherChipView.class.getDeclaredField(name); f.setAccessible(true); f.set(view, value);
	}
	private Object get(WatcherChipView view, String name) throws Exception {
		Field f = WatcherChipView.class.getDeclaredField(name); f.setAccessible(true); return f.get(view);
	}
	private boolean capture(WatcherChipView view) throws Exception {
		Method m = WatcherChipView.class.getDeclaredMethod("captureCurrentContent"); m.setAccessible(true);
		return (boolean) m.invoke(view);
	}
	private Bitmap content(WatcherChipView view) throws Exception {
		Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
		bitmap.setDensity(Bitmap.DENSITY_NONE);
		Method m = WatcherChipView.class.getDeclaredMethod("drawContent", Canvas.class); m.setAccessible(true);
		m.invoke(view, new Canvas(bitmap)); return bitmap;
	}
	private WatcherChipView fixture() {
		Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		DrawerStyle style = new DrawerStyle(context); WatcherChipView view = new WatcherChipView(context, style);
		view.measure(View.MeasureSpec.makeMeasureSpec(style.chipWidth, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(style.rowHeight, View.MeasureSpec.EXACTLY));
		view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight()); view.bind(1, "A");
		view.update(new WatcherService.Counter(WatcherService.Counter.State.ENABLED, false, 0, false, false));
		return view;
	}
	private void samePixels(Bitmap before, Bitmap after) {
		assertEquals(before.getWidth(), after.getWidth()); assertEquals(before.getHeight(), after.getHeight());
		for (int y = 0; y < before.getHeight(); y++) for (int x = 0; x < before.getWidth(); x++) {
			int a = before.getPixel(x, y), b = after.getPixel(x, y);
			assertEquals(Color.alpha(a), Color.alpha(b), 1);
			if (Color.alpha(a) > 8 && Color.alpha(b) > 8) {
				assertEquals(Color.red(a), Color.red(b), 2); assertEquals(Color.green(a), Color.green(b), 2);
				assertEquals(Color.blue(a), Color.blue(b), 2);
			}
		}
	}
	@Test public void repeatedInterruptionsFreezeVisibleMixtureInsteadOfFullPreviousTarget() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try {
				WatcherChipView view = fixture(); assertTrue(capture(view));
				for (int count : new int[]{1, 2, 3, 4, 999, 1000, 1001, 0}) {
					set(view, "state", WatcherPresentation.of(true, false, false, count, false, false));
					set(view, "crossfade", .4f); Bitmap before = content(view);
					assertTrue(capture(view)); set(view, "crossfade", 0f); Bitmap after = content(view);
					samePixels(before, after); before.recycle(); after.recycle();
					assertNotNull(get(view, "snapshot")); assertNotNull(get(view, "scratch"));
				}
				set(view, "crossfade", 1f); view.clear();
				assertNull(get(view, "snapshot")); assertNull(get(view, "scratch"));
				view.bind(2, "B"); view.update(new WatcherService.Counter(WatcherService.Counter.State.ENABLED, false, 7, false, false));
				assertNull(get(view, "snapshot"));
			} catch (Exception e) { throw new AssertionError(e); }
		});
	}
	@Test public void frozenContentExcludesTheLiveLoader() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try {
				WatcherChipView view = fixture(); set(view, "loaderVisible", true);
				assertTrue(capture(view)); Bitmap snapshot = (Bitmap) get(view, "snapshot");
				int iconEnd = new DrawerStyle(view.getContext()).numberStart();
				for (int y = 0; y < snapshot.getHeight(); y++) for (int x = 0; x < iconEnd; x++) {
					assertEquals(0, Color.alpha(snapshot.getPixel(x, y)));
				}
				view.clear(); assertNull(get(view, "snapshot")); assertNull(get(view, "scratch"));
			} catch (Exception e) { throw new AssertionError(e); }
		});
	}
}
