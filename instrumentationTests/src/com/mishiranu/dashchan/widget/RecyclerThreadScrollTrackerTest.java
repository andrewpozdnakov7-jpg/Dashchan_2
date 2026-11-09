package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import static com.mishiranu.dashchan.widget.ExpandedThreadScrollState.Decision.*;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Prepared actual-consumed-scroll fixtures. No device test execution implied. */
@RunWith(AndroidJUnit4.class)
public class RecyclerThreadScrollTrackerTest {
	private static final class Rows extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		int count; final int height;
		Rows(int count, int height) { this.count = count; this.height = height; }
		@NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
			View view = new View(parent.getContext());
			view.setLayoutParams(new RecyclerView.LayoutParams(-1, height));
			return new RecyclerView.ViewHolder(view) {};
		}
		@Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}
		@Override public int getItemCount() { return count; }
	}
	private static RecyclerView list(int count, int rowHeight) {
		Context context = ThemeEngine.attach(InstrumentationRegistry.getInstrumentation().getTargetContext());
		RecyclerView list = new RecyclerView(context); list.setItemAnimator(null);
		list.setLayoutManager(new LinearLayoutManager(context)); list.setPadding(0, 24, 0, 16);
		list.setAdapter(new Rows(count, rowHeight)); layout(list); return list;
	}
	private static void layout(RecyclerView list) {
		list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 400, 600);
	}
	private static final class Listener implements RecyclerScrollTracker.OnScrollListener {
		final IdentityHashMap<RecyclerView, List<ExpandedThreadScrollState.Decision>> events = new IdentityHashMap<>();
		@Override public void onScroll(ViewGroup view, boolean down, int count, boolean first, boolean last) {
			throw new AssertionError("A thread must not enter the legacy last-item policy");
		}
		@Override public void onThreadScroll(RecyclerView view, ExpandedThreadScrollState.Decision decision) {
			List<ExpandedThreadScrollState.Decision> list = events.get(view);
			if (list == null) { list = new ArrayList<>(); events.put(view, list); }
			list.add(decision);
		}
		ExpandedThreadScrollState.Decision last(RecyclerView view) {
			List<ExpandedThreadScrollState.Decision> list = events.get(view);
			return list != null && !list.isEmpty() ? list.get(list.size() - 1) : KEEP;
		}
	}

	@Test public void partialOpAndOneTallPostAreNotMistakenForTheActualTop() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RecyclerView tall = list(1, 1800);
			assertTrue(RecyclerScrollTracker.isThreadAtStart(tall));
			assertTrue(tall.canScrollVertically(1)); tall.scrollBy(0, 40);
			assertEquals(0, ((LinearLayoutManager) tall.getLayoutManager()).findFirstVisibleItemPosition());
			assertFalse(RecyclerScrollTracker.isThreadAtStart(tall));
			tall.scrollBy(0, 100000); assertFalse(RecyclerScrollTracker.isThreadAtStart(tall));
			tall.scrollBy(0, -100000); assertTrue(RecyclerScrollTracker.isThreadAtStart(tall));
			RecyclerView shortList = list(2, 100);
			assertFalse(shortList.canScrollVertically(-1)); assertFalse(shortList.canScrollVertically(1));
			assertTrue(RecyclerScrollTracker.isThreadAtStart(shortList));
		});
	}

	@Test public void arrivingAtBottomAndAppendingWithoutConsumedDyNeverShowTheToolbar() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RecyclerView view = list(30, 100); Listener listener = new Listener();
			RecyclerScrollTracker tracker = new RecyclerScrollTracker(listener); tracker.attach(view, true);
			try {
				view.scrollBy(0, 100000); assertEquals(HIDE, listener.last(view));
				listener.events.get(view).clear();
				view.scrollBy(0, 3); // Unconsumed motion at the actual bottom.
				Rows rows = (Rows) view.getAdapter(); int old = rows.count; rows.count += 4;
				rows.notifyItemRangeInserted(old, 4); layout(view);
				for (ExpandedThreadScrollState.Decision event : listener.events.get(view)) assertNotEquals(SHOW, event);
			} finally { tracker.detach(view); }
		});
	}

	@Test public void registeredListsKeepIndependentDebtAndDetachRemovesCallbacks() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RecyclerView one = list(10, 300), two = list(10, 300);
			Listener listener = new Listener(); RecyclerScrollTracker tracker = new RecyclerScrollTracker(listener);
			tracker.attach(one, true); tracker.attach(two, true);
			try {
				int threshold = Math.round(12 * one.getResources().getDisplayMetrics().density);
				one.scrollBy(0, threshold - 1); assertEquals(KEEP, listener.last(one));
				two.scrollBy(0, 1); assertEquals(KEEP, listener.last(two));
				one.scrollBy(0, 1); assertEquals(HIDE, listener.last(one));
				tracker.detach(one); int previous = listener.events.get(one).size();
				one.scrollBy(0, 100); assertEquals(previous, listener.events.get(one).size());
			} finally { tracker.detach(one); tracker.detach(two); }
		});
	}
}
