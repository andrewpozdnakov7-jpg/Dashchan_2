package com.mishiranu.dashchan.ui.navigator.page;

import static org.junit.Assert.*;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PostsHistoryVisitTest {
	private static PostsPage.HistoryVisitViewModel visit(ViewModelStore store) {
		return new ViewModelProvider(store, new ViewModelProvider.NewInstanceFactory())
				.get(PostsPage.HistoryVisitViewModel.class);
	}

	@Test public void recreationAndRepeatedResultsKeepOneVisitButReentryRecordsAgain() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ViewModelStore current = new ViewModelStore();
			ViewModelStore reopened = new ViewModelStore();
			int[] writes = {0};
			try {
				PostsPage.HistoryVisitViewModel first = visit(current);
				first.recordIfNeeded(true, () -> writes[0]++);
				first.recordIfNeeded(true, () -> writes[0]++);
				assertSame(first, visit(current));
				visit(current).recordIfNeeded(true, () -> writes[0]++);
				assertEquals(1, writes[0]);
				assertNotSame(first, visit(reopened));
				visit(reopened).recordIfNeeded(true, () -> writes[0]++);
				assertEquals(2, writes[0]);
			} finally { current.clear(); reopened.clear(); }
		});
	}

	@Test public void disabledHistoryDoesNotConsumeTheVisit() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			ViewModelStore store = new ViewModelStore();
			int[] writes = {0};
			try {
				visit(store).recordIfNeeded(false, () -> writes[0]++);
				assertEquals(0, writes[0]);
				visit(store).recordIfNeeded(true, () -> writes[0]++);
				visit(store).recordIfNeeded(true, () -> writes[0]++);
				assertEquals(1, writes[0]);
			} finally { store.clear(); }
		});
	}
}
