package com.mishiranu.dashchan.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.database.HistoryDatabase.HistoryItem;
import com.mishiranu.dashchan.ui.navigator.adapter.HistoryAdapter;
import com.mishiranu.dashchan.util.ThreadMotionKey;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Isolated row-action fixtures. They never delete or open the device's real history. */
@RunWith(AndroidJUnit4.class)
public class HistoryRemoveSwipeControllerTest {
	private static ThreadMotionKey key(HistoryItem item) {
		return new ThreadMotionKey(item.chanName, item.boardName, item.threadNumber);
	}
	private static HistoryItem item(String site, String board) {
		HistoryItem item = new HistoryItem(); item.chanName = site; item.boardName = board; item.threadNumber = "123";
		return item;
	}
	private static final class Rows extends HistoryAdapter {
		final ArrayList<HistoryItem> items = new ArrayList<>();
		Rows(Context context) {
			super(context, null, null); setHasStableIds(false);
			items.add(item("one", "a")); items.add(item("one", "b")); items.add(item("two", "a"));
		}
		@Override public int getItemCount() { return items.size(); }
		@Override public HistoryItem getItem(int position) { return items.get(position).copy(); }
		@NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
			TextView row = new TextView(parent.getContext()); row.setLayoutParams(new RecyclerView.LayoutParams(-1, 100));
			return new RecyclerView.ViewHolder(row) {};
		}
		@Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			HistoryRemoveSwipeController.onBinding(holder.itemView);
			holder.itemView.setTag(R.id.history_item_motion_key, key(items.get(position)));
		}
	}
	private static final class Fixture implements AutoCloseable {
		final RecyclerView list;
		final Rows rows;
		final ArrayList<HistoryItem> removed = new ArrayList<>();
		final HistoryRemoveSwipeController controller;
		final ItemTouchHelper.SimpleCallback callback;
		Fixture() {
			Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
			list = new RecyclerView(context); list.setLayoutManager(new LinearLayoutManager(context));
			rows = new Rows(context); list.setAdapter(rows); list.setItemAnimator(null);
			list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)); list.layout(0, 0, 400, 500);
			controller = new HistoryRemoveSwipeController(list, rows, removed::add); controller.resume();
			try {
				Class<?> type = Class.forName(HistoryRemoveSwipeController.class.getName() + "$Callback");
				Constructor<?> constructor = type.getDeclaredConstructor(HistoryRemoveSwipeController.class);
				constructor.setAccessible(true); callback = (ItemTouchHelper.SimpleCallback) constructor.newInstance(controller);
			} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
		}
		RecyclerView.ViewHolder begin() {
			RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0); assertNotNull(holder);
			callback.onSelectedChanged(holder, ItemTouchHelper.ACTION_STATE_SWIPE);
			callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE); return holder;
		}
		@Override public void close() { controller.close(); }
	}

	@Test public void leftSwipeCommitsExactlyOnceForTheCompleteSiteBoardThreadIdentity() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try (Fixture f = new Fixture()) {
				RecyclerView.ViewHolder holder = f.begin();
				assertEquals(ItemTouchHelper.LEFT, f.callback.getSwipeDirs(f.list, holder));
				f.callback.onSwiped(holder, ItemTouchHelper.RIGHT); assertTrue(f.removed.isEmpty());
				f.callback.onSwiped(holder, ItemTouchHelper.LEFT); f.callback.onSwiped(holder, ItemTouchHelper.LEFT);
				assertEquals(1, f.removed.size()); assertEquals(key(f.rows.items.get(0)), key(f.removed.get(0)));
			}
		});
	}

	@Test public void cursorRefreshCannotDeleteTheNewRowAtAnOldPosition() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try (Fixture f = new Fixture()) {
				RecyclerView.ViewHolder holder = f.begin(); Collections.swap(f.rows.items, 0, 1);
				f.rows.notifyDataSetChanged(); f.callback.onSwiped(holder, ItemTouchHelper.LEFT);
				assertTrue(f.removed.isEmpty()); assertEquals(0f, holder.itemView.getTranslationX(), 0f);
			}
		});
	}

	@Test public void cancellingForSearchOrLeavingThePageNeverCommits() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try (Fixture f = new Fixture()) {
				RecyclerView.ViewHolder holder = f.begin(); f.controller.cancelPresentation();
				f.callback.onSwiped(holder, ItemTouchHelper.LEFT); assertTrue(f.removed.isEmpty());
				holder = f.begin(); f.controller.pause(); f.callback.onSwiped(holder, ItemTouchHelper.LEFT);
				assertTrue(f.removed.isEmpty()); assertNull(holder.itemView.getTag(R.id.history_remove_swipe_state));
			}
		});
	}

	@Test public void recycledRowsReleaseOnlyTheirOwnPresentation() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try (Fixture f = new Fixture()) {
				View view = f.list.findViewHolderForAdapterPosition(0).itemView;
				HistoryRemoveSwipeController.OwnedView motion = new HistoryRemoveSwipeController.OwnedView(
						view, key(f.rows.items.get(0)), 0, 90, true);
				motion.frame(0); assertEquals(90f, view.getTranslationY(), 0f);
				HistoryRemoveSwipeController.onBinding(view);
				assertEquals(0f, view.getTranslationY(), 0f); assertEquals(1f, view.getAlpha(), 0f);
				assertNull(view.getTag(R.id.history_remove_motion_owner));
			}
		});
	}
}
