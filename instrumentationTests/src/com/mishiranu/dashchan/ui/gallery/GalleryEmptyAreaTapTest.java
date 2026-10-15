package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared native gesture regression tests; no network or media required. */
@RunWith(AndroidJUnit4.class)
public class GalleryEmptyAreaTapTest {
	private static RecyclerView grid() {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		RecyclerView view = new RecyclerView(context); view.setLayoutManager(new LinearLayoutManager(context));
		view.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
			@Override public int getItemCount() { return 2; }
			@Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
				View cell = new View(parent.getContext());
				cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
				return new RecyclerView.ViewHolder(cell) {};
			}
			@Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {}
		});
		view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
		view.layout(0, 0, 300, 600);
		assertNotNull(view.findChildViewUnder(150, 50));
		assertNull(view.findChildViewUnder(150, 500));
		assertFalse(view.hasPendingAdapterUpdates());
		return view;
	}

	private static void event(GalleryEmptyAreaTap owner, long down, long time, int action, float x, float y) {
		MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
		try { owner.onTouchEvent(event); } finally { event.recycle(); }
	}

	@Test public void onlyEnabledShortEmptyToEmptyTapCloses() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RecyclerView view = grid(); boolean[] enabled = {false}; int[] closes = {0};
			GalleryEmptyAreaTap owner = new GalleryEmptyAreaTap(view, () -> enabled[0], () -> closes[0]++);
			long t = SystemClock.uptimeMillis();
			event(owner, t, t, MotionEvent.ACTION_DOWN, 150, 500);
			event(owner, t, t + 50, MotionEvent.ACTION_UP, 150, 500);
			assertEquals(0, closes[0]);
			enabled[0] = true; t += 1000;
			event(owner, t, t, MotionEvent.ACTION_DOWN, 150, 50);
			event(owner, t, t + 50, MotionEvent.ACTION_UP, 150, 50);
			assertEquals(0, closes[0]);
			t += 1000;
			event(owner, t, t, MotionEvent.ACTION_DOWN, 150, 500);
			event(owner, t, t + 50, MotionEvent.ACTION_UP, 150, 500);
			assertEquals(1, closes[0]);
			t += 1000;
			event(owner, t, t, MotionEvent.ACTION_DOWN, 150, 500); enabled[0] = false;
			event(owner, t, t + 50, MotionEvent.ACTION_UP, 150, 500);
			assertEquals(1, closes[0]);
		});
	}

	@Test public void dragLongPressCancellationAndEndingOverAFileNeverClose() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			int[] closes = {0}; GalleryEmptyAreaTap owner = new GalleryEmptyAreaTap(grid(), () -> true, () -> closes[0]++);
			long t = SystemClock.uptimeMillis();
			event(owner, t, t, MotionEvent.ACTION_DOWN, 150, 500);
			event(owner, t, t + 20, MotionEvent.ACTION_MOVE, 150, 300);
			event(owner, t, t + 40, MotionEvent.ACTION_UP, 150, 300);
			t += 1000;
			event(owner, t, t, MotionEvent.ACTION_DOWN, 150, 500);
			event(owner, t, t + ViewConfiguration.getLongPressTimeout() + 1, MotionEvent.ACTION_UP, 150, 500);
			t += 1000;
			event(owner, t, t, MotionEvent.ACTION_DOWN, 150, 500);
			event(owner, t, t + 10, MotionEvent.ACTION_CANCEL, 150, 500);
			event(owner, t, t + 50, MotionEvent.ACTION_UP, 150, 500);
			t += 1000;
			event(owner, t, t, MotionEvent.ACTION_DOWN, 150, 500);
			event(owner, t, t + 50, MotionEvent.ACTION_UP, 150, 50);
			assertEquals(0, closes[0]);
		});
	}

	@Test public void secondFingerCancelsThePendingEmptyTap() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			int[] closes = {0}; GalleryEmptyAreaTap owner = new GalleryEmptyAreaTap(grid(), () -> true, () -> closes[0]++);
			long t = SystemClock.uptimeMillis();
			event(owner, t, t, MotionEvent.ACTION_DOWN, 150, 500);
			MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[2];
			MotionEvent.PointerCoords[] points = new MotionEvent.PointerCoords[2];
			for (int i = 0; i < 2; i++) {
				props[i] = new MotionEvent.PointerProperties(); props[i].id = i;
				props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
				points[i] = new MotionEvent.PointerCoords(); points[i].x = 150 + i * 20; points[i].y = 500;
			}
			for (int action : new int[]{MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP}) {
				MotionEvent event = MotionEvent.obtain(t, t + 10,
						action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, points,
						0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
				try { owner.onTouchEvent(event); } finally { event.recycle(); }
			}
			event(owner, t, t + 50, MotionEvent.ACTION_UP, 150, 500);
			assertEquals(0, closes[0]);
		});
	}
}
