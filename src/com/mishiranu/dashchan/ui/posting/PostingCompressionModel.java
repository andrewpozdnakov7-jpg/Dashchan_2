package com.mishiranu.dashchan.ui.posting;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import com.mishiranu.dashchan.media.compression.VideoCompressionJob;
import com.mishiranu.dashchan.media.compression.VideoCompressionService;

/** Only detached data survives rotation. The service never retains a Fragment, Activity or View. */
public final class PostingCompressionModel extends ViewModel {
	final MutableLiveData<VideoCompressionJob> state = new MutableLiveData<>();
	VideoCompressionJob job;
	String signature;
	final java.util.Set<String> forcedVideos = new java.util.HashSet<>();
	private final Observer<VideoCompressionJob> observer = state::setValue;
	void replace(VideoCompressionJob next, String signature) {
		clear(); this.signature = signature; job = next;
		if (next != null) {
			next.changes.observeForever(observer); state.setValue(next);
			// Start while the initiating UI is foreground; preparation may finish after it is hidden.
			if (VideoCompressionService.start(next.context, next)) next.prepare();
		}
	}
	void clear() {
		if (job != null) {
			job.changes.removeObserver(observer);
			if (job.busy()) VideoCompressionService.cancel(job); else job.release();
		}
		job = null; signature = null; state.setValue(null);
	}
	@Override protected void onCleared() { clear(); }
}
