package com.mishiranu.dashchan.ui.navigator.adapter;

import com.mishiranu.dashchan.util.AuditDiagnostics;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Small, synchronous main-thread transactions. Never diff a live mutable model in the background. */
final class AdapterListUpdate {
	// Bound worst-case diff work on the UI thread; large replacements keep the established full refresh.
	static final int MAX_DIFF_ROWS = 1000;

	static final class Row {
		final Object key;
		final Object content;
		Row(Object key, Object content) {
			this.key = key;
			this.content = content;
		}
	}

	static List<Object> values(Object... values) {
		return Arrays.asList(values);
	}

	static DiffUtil.DiffResult calculate(List<Row> before, List<Row> after, boolean rebindRetained) {
		if (before.size() > MAX_DIFF_ROWS || after.size() > MAX_DIFF_ROWS
				|| !unique(before) || !unique(after)) return null;
		return DiffUtil.calculateDiff(new DiffUtil.Callback() {
			@Override public int getOldListSize() { return before.size(); }
			@Override public int getNewListSize() { return after.size(); }
			@Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
				return Objects.equals(before.get(oldPosition).key, after.get(newPosition).key);
			}
			@Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
				return !rebindRetained && Objects.equals(before.get(oldPosition).content, after.get(newPosition).content);
			}
		}, false); // Avoid the extra quadratic move-detection pass on sorted catalogs.
	}

	private static boolean unique(List<Row> rows) {
		HashSet<Object> keys = new HashSet<>();
		for (Row row : rows) if (!keys.add(row.key)) return false;
		return true;
	}

	/** The adapter must already expose 'after' when observers receive these notifications. */
	static void dispatch(RecyclerView.Adapter<?> adapter, List<Row> before, List<Row> after,
			boolean rebindRetained, String reason) {
		String kind = adapter instanceof ThreadsAdapter ? "threads" : "myPosts";
		DiffUtil.DiffResult result;
		try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/Diff/" + kind + "/calculate")
				.reason(reason).sizes(before.size(), after.size()).count(rebindRetained ? 1 : 0)) {
			result = calculate(before, after, rebindRetained);
			scope.result(result != null ? "diff" : before.size() > MAX_DIFF_ROWS || after.size() > MAX_DIFF_ROWS
					? "fallback_size" : "fallback_duplicate");
		}
		try (AuditDiagnostics.Scope scope = AuditDiagnostics.begin("Audit/Diff/" + kind + "/dispatch")
				.reason(reason).sizes(before.size(), after.size())) {
			if (result != null) result.dispatchUpdatesTo(adapter);
			else adapter.notifyDataSetChanged();
			scope.result(result != null ? "diff" : "full_refresh");
		}
	}
}
