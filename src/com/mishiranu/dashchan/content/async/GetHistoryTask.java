package com.mishiranu.dashchan.content.async;

import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import com.mishiranu.dashchan.content.database.CommonDatabase;
import com.mishiranu.dashchan.content.database.HistoryDatabase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class GetHistoryTask extends ExecutorTask<Void, HistoryDatabase.HistoryCursor> {
	public interface Callback {
		void onGetHistoryResult(HistoryDatabase.HistoryCursor cursor);
	}

	private final Callback callback;
	private final String chanName;
	private final String searchQuery;
	private final List<HistoryDatabase.Board> selectedBoards;
	private final CancellationSignal signal = new CancellationSignal();

	public GetHistoryTask(Callback callback, String chanName, String searchQuery) {
		this(callback, chanName, searchQuery, Collections.emptyList());
	}

	public GetHistoryTask(Callback callback, String chanName, String searchQuery,
			Collection<HistoryDatabase.Board> selectedBoards) {
		this.callback = callback;
		this.chanName = chanName;
		this.searchQuery = searchQuery;
		this.selectedBoards = new ArrayList<>(selectedBoards);
	}

	@Override
	protected HistoryDatabase.HistoryCursor run() {
		try {
			return CommonDatabase.getInstance().getHistory().getHistory(chanName, searchQuery, selectedBoards, signal);
		} catch (OperationCanceledException e) {
			return null;
		}
	}

	@Override
	public void cancel() {
		super.cancel();
		try {
			signal.cancel();
		} catch (Exception e) {
			// Ignore
		}
	}

	@Override
	protected void onCancel(HistoryDatabase.HistoryCursor cursor) {
		if (cursor != null) {
			cursor.close();
		}
	}

	@Override
	protected void onComplete(HistoryDatabase.HistoryCursor cursor) {
		callback.onGetHistoryResult(cursor);
	}
}
