package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import com.mishiranu.dashchan.content.Preferences;
import java.util.Arrays;
import org.junit.Test;

/** Actual grid layout and arrow hit targets, without network or board databases. */
public class QuickNavigationBoardEdgesTest {
	private static final class Rows extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private int[] heights;
		Rows(int[] heights) { this.heights = heights; }
		@NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
			View view = new View(parent.getContext());
			view.setLayoutParams(new RecyclerView.LayoutParams(-1, 100));
			return new RecyclerView.ViewHolder(view) {};
		}
		@Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			holder.itemView.getLayoutParams().height = heights[position];
		}
		@Override public int getItemCount() { return heights.length; }
		void append(int count) {
			int previous = heights.length;
			heights = Arrays.copyOf(heights, previous + count);
			Arrays.fill(heights, previous, heights.length, 100);
			notifyItemRangeInserted(previous, count);
		}
	}

	private static final class Fixture {
		final FrameLayout root;
		final PaddedRecyclerView list;
		final GridLayoutManager manager;
		final ThreadQuickNavigation navigation;
		final Rows rows;
		Fixture(StandardContentTestActivity activity, int columns, int[] heights, boolean header) {
			Context context = ThemeEngine.attach(activity);
			root = new FrameLayout(context);
			list = new PaddedRecyclerView(context);
			manager = new GridLayoutManager(context, columns);
			if (header) manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
				@Override public int getSpanSize(int position) { return position == 0 ? columns : 1; }
			});
			list.setLayoutManager(manager);
			list.setItemAnimator(null);
			rows = new Rows(heights);
			list.setAdapter(rows);
			root.addView(list, new FrameLayout.LayoutParams(400, 600));
			navigation = new ThreadQuickNavigation(list, true);
			root.addView(navigation, new FrameLayout.LayoutParams(400, 600));
			activity.setContentView(root);
			layout();
		}
		void layout() {
			root.forceLayout();
			root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
			root.layout(0, 0, 400, 600);
			navigation.setContentVisible(true);
		}
		View arrow(int index) { return ((FrameLayout) navigation.getChildAt(0)).getChildAt(index); }
		void assertArrows(boolean top, boolean bottom) {
			assertEquals(top, arrow(0).isClickable());
			assertEquals(bottom, arrow(1).isClickable());
			assertEquals(top, arrow(0).isFocusable());
			assertEquals(bottom, arrow(1).isFocusable());
		}
	}

	private void withArrows(java.util.function.Consumer<StandardContentTestActivity> run) {
		String key = Preferences.KEY_THREAD_QUICK_NAVIGATION;
		Object old = Preferences.PREFERENCES.getAll().get(key);
		try (ActivityScenario<StandardContentTestActivity> scenario = ActivityScenario.launch(StandardContentTestActivity.class)) {
			scenario.onActivity(activity -> {
				Preferences.PREFERENCES.edit().put(key, "right").close();
				run.accept(activity);
			});
		} finally {
			if (old instanceof String) Preferences.PREFERENCES.edit().put(key, (String) old).close();
			else Preferences.PREFERENCES.edit().remove(key).close();
		}
	}

	@Test public void listAndGridWaitForTheWholeEdgeRowAndAppendDoesNotMoveTheViewport() {
		withArrows(activity -> {
			for (int columns : new int[]{1, 3}) {
				int[] heights = new int[60];
				Arrays.fill(heights, 100);
				Fixture f = new Fixture(activity, columns, heights, false);
				f.assertArrows(false, true);
				f.list.scrollBy(0, 50); f.navigation.setContentVisible(true);
				f.assertArrows(false, true);
				f.list.scrollBy(0, 51); f.navigation.setContentVisible(true);
				f.assertArrows(true, true);
				f.list.scrollBy(0, -101); f.navigation.setContentVisible(true);
				f.assertArrows(false, true);
				f.list.scrollBy(0, 100000); f.navigation.setContentVisible(true);
				f.assertArrows(true, false);
				int first = f.manager.findFirstVisibleItemPosition();
				int top = f.manager.findViewByPosition(first).getTop();
				f.rows.append(12); f.layout();
				f.assertArrows(true, true);
				assertEquals(first, f.manager.findFirstVisibleItemPosition());
				assertEquals(top, f.manager.findViewByPosition(first).getTop());
			}
		});
	}

	@Test public void shortListsTallSingleRowsAndFullSpanHeadersKeepNavigationAvailable() {
		withArrows(activity -> {
			Fixture shortList = new Fixture(activity, 3, new int[]{100, 100, 100}, false);
			shortList.assertArrows(false, false);
			Fixture tall = new Fixture(activity, 3, new int[]{1200, 100, 100}, false);
			tall.assertArrows(false, true);
			tall.list.scrollBy(0, 50); tall.navigation.setContentVisible(true);
			tall.assertArrows(true, true);
			int[] heights = new int[60]; Arrays.fill(heights, 100); heights[0] = 12;
			Fixture header = new Fixture(activity, 3, heights, true);
			header.assertArrows(false, true);
			header.list.scrollBy(0, 13); header.navigation.setContentVisible(true);
			header.assertArrows(true, true);
		});
	}
}
