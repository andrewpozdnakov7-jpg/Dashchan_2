package com.mishiranu.dashchan.ui.posting;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import chan.content.Chan;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.chan.dvach.DvachChanConfiguration;
import com.mishiranu.dashchan.content.async.HttpHolderTask;
import com.mishiranu.dashchan.media.compression.UploadLimitResolver;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.Objects;

/** One form-scoped lookup, retained across rotation; no View or Activity is retained. */
public final class PostingLimitsModel extends ViewModel {
	final MutableLiveData<Long> totalBytes = new MutableLiveData<>(0L);
	private HttpHolderTask<Void, Long> task;
	private String chanName, boardName, cookie;
	private boolean loaded;

	void load(Chan chan, String board) {
		String nextCookie = chan.configuration instanceof DvachChanConfiguration
				? chan.configuration.getCookie("passcode_auth") : null;
		if (loaded && Objects.equals(chanName, chan.name) && Objects.equals(boardName, board)
				&& Objects.equals(cookie, nextCookie)) return;
		if (task != null) { task.cancel(); task = null; }
		chanName = chan.name; boardName = board; cookie = nextCookie; loaded = true;
		totalBytes.setValue(0L);
		if (!(chan.configuration instanceof DvachChanConfiguration) || board == null) return;
		HttpHolderTask<Void, Long> next = new HttpHolderTask<Void, Long>(chan) {
			@Override protected Long run(HttpHolder holder) {
				try { return UploadLimitResolver.resolve(chan, board, holder); }
				catch (Exception e) { return 0L; }
			}
			@Override protected void onComplete(Long value) {
				if (task != this) return;
				task = null;
				if (Objects.equals(nextCookie, chan.configuration.getCookie("passcode_auth"))) {
					totalBytes.setValue(value != null ? value : 0L);
				}
			}
		};
		task = next;
		try { next.execute(ConcurrentUtils.SEPARATE_EXECUTOR); }
		catch (RuntimeException e) { task = null; next.cancel(); }
	}

	@Override protected void onCleared() {
		if (task != null) { task.cancel(); task = null; }
		cookie = null;
	}
}
