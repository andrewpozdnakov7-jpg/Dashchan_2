package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
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
		Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		PaddedRecyclerView list = new PaddedRecyclerView(context);
		list.setLayoutManager(new LinearLayoutManager(context));
		list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
		});
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
}
