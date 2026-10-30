package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Uses actual attached/decorated child bounds rather than mocked RecyclerView scroll metrics. */
@RunWith(AndroidJUnit4.class)
public class PaddedRecyclerViewFastScrollPositionTest {
	private static PaddedRecyclerView createList(int[] heights) {
		return createList(heights, 0, false);
	}

	private static final class Rows extends RecyclerView.Adapter<RecyclerView.ViewHolder>
			implements PaddedRecyclerView.ItemFastScrollProvider {
		private int[] heights;
		boolean compactHiddenRows;
		Rows(int[] heights, boolean compactHiddenRows) {
			this.heights = heights;
			this.compactHiddenRows = compactHiddenRows;
		}
		@Override public boolean useItemFastScrollGeometry() { return compactHiddenRows; }
		@NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(
				@NonNull ViewGroup parent, int viewType) {
			View view = new View(parent.getContext());
			view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
			return new RecyclerView.ViewHolder(view) {};
		}
		@Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
			params.height = heights[position];
			holder.itemView.setLayoutParams(params);
		}
		@Override public int getItemCount() { return heights.length; }
		void append(int[] more) {
			int previous = heights.length;
			heights = Arrays.copyOf(heights, previous + more.length);
			System.arraycopy(more, 0, heights, previous, more.length);
			notifyItemRangeInserted(previous, more.length);
		}
	}

	private static PaddedRecyclerView createList(int[] heights, int columns, boolean compactHiddenRows) {
		Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		PaddedRecyclerView list = new PaddedRecyclerView(context);
		list.setLayoutManager(columns > 0 ? new GridLayoutManager(context, columns) : new LinearLayoutManager(context));
		list.setAdapter(new Rows(heights, compactHiddenRows));
		list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 400, 600);
		return list;
	}

	private static void assertContinuousBottom(int[] heights) {
		PaddedRecyclerView list = createList(heights);
		int total = 0;
		for (int height : heights) total += height;
		list.scrollBy(0, total - 600 - 1);
		FastScrollGeometry nearBottom = list.getFastScrollGeometry();
		assertNotNull(nearBottom);
		assertTrue(nearBottom.offset() > 0.99f);
		assertTrue(nearBottom.offset() < 1f);
		list.scrollBy(0, 1);
		FastScrollGeometry bottom = list.getFastScrollGeometry();
		assertNotNull(bottom);
		assertFalse(list.canScrollVertically(1));
		assertEquals(1, bottom.offset(), 0);
		assertTrue(bottom.offset() - nearBottom.offset() < 0.01f);
		assertEquals(bottom.start, bottom.targetCoordinate(bottom.offset()), 0.00001);
		list.scrollBy(0, -1);
		assertEquals(nearBottom.offset(), list.getFastScrollGeometry().offset(), 0.000001);
	}

	@Test public void uniformPostsReachBottomWithoutTheOldHalfTrackJump() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			int[] heights = new int[13];
			Arrays.fill(heights, 100);
			assertContinuousBottom(heights);
		});
	}

	@Test public void unequalAndOversizedPostsPreserveContinuousCoordinates() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			int[] firstTall = new int[13];
			Arrays.fill(firstTall, 100);
			firstTall[0] = 1100;
			assertContinuousBottom(firstTall);
			int[] lastTall = new int[13];
			Arrays.fill(lastTall, 100);
			lastTall[12] = 1100;
			assertContinuousBottom(lastTall);
			assertContinuousBottom(new int[] {1100});
		});
	}

	@Test public void compactHiddenRowsInOneColumnHaveContinuousThumbCoordinates() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			int[] heights = {40, 40, 400, 400, 40, 40, 400, 400, 40, 40, 400, 40};
			PaddedRecyclerView list = createList(heights, 1, true);
			int total = 0;
			for (int height : heights) total += height;
			assertNotNull(list.getFastScrollGeometry());
			float previous = list.getFastScrollGeometry().offset();
			assertEquals(0f, previous, 0f);
			for (int pixel = 0; pixel < total - 600; pixel++) {
				list.scrollBy(0, 1);
				FastScrollGeometry geometry = list.getFastScrollGeometry();
				assertNotNull(geometry);
				float current = geometry.offset();
				assertTrue("Thumb moved backwards at pixel " + pixel, current >= previous - 0.000001f);
				assertTrue("Thumb jumped at pixel " + pixel, current - previous < 0.02f);
				assertEquals(geometry.start, geometry.targetCoordinate(current), 0.00001);
				previous = current;
			}
			assertFalse(list.canScrollVertically(1));
			assertEquals(1f, previous, 0f);
		});
	}

	@Test public void oneColumnWithoutHiddenRowsKeepsLegacyGeometryAndCanReturnToIt() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PaddedRecyclerView list = createList(new int[] {400, 400, 400, 400}, 1, false);
			assertNull(list.getFastScrollGeometry());
			list.scrollBy(0, 123);
			assertNull(list.getFastScrollGeometry());
			Rows rows = (Rows) list.getAdapter();
			rows.compactHiddenRows = true;
			assertNotNull(list.getFastScrollGeometry());
			rows.compactHiddenRows = false;
			assertNull(list.getFastScrollGeometry());
		});
	}

	@Test public void realMultiColumnGridKeepsLegacyGeometryEvenWithHiddenRows() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			PaddedRecyclerView list = createList(new int[] {40, 400, 40, 400, 40, 400, 40, 400}, 2, true);
			assertNull(list.getFastScrollGeometry());
			list.scrollBy(0, 123);
			assertNull(list.getFastScrollGeometry());
		});
	}
	private static float shownOffset(PaddedRecyclerView list) {
		try {
			java.lang.reflect.Method method = PaddedRecyclerView.class.getDeclaredMethod("getCurrentOffset");
			method.setAccessible(true); return (float) method.invoke(list);
		} catch (ReflectiveOperationException error) { throw new AssertionError(error); }
	}
	@Test public void backgroundAppendKeepsTheThumbAndContentAnchorWhileTheCanonicalRangeGrows() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			int[] heights = {40, 40, 400, 400, 40, 40, 400, 400, 40, 40, 400, 40};
			PaddedRecyclerView list = createList(heights, 1, true); list.setFastScrollerEnabled(true);
			list.scrollBy(0, 120);
			LinearLayoutManager manager = (LinearLayoutManager) list.getLayoutManager();
			int first = manager.findFirstVisibleItemPosition(), top = manager.findViewByPosition(first).getTop();
			float before = shownOffset(list);
			list.prepareFastScrollAppend(); ((Rows) list.getAdapter()).append(heights);
			list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
			list.layout(0, 0, 400, 600);
			assertTrue(list.getFastScrollGeometry().offset() < before);
			assertEquals(before, shownOffset(list), .002f);
			assertEquals(first, manager.findFirstVisibleItemPosition());
			assertEquals(top, manager.findViewByPosition(first).getTop());
			list.setFastScrollerEnabled(false);
			assertEquals(list.getFastScrollGeometry().offset(), shownOffset(list), 0f);
		});
	}
}
