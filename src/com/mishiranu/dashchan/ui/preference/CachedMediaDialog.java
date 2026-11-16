package com.mishiranu.dashchan.ui.preference;

import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CachedMedia;
import com.mishiranu.dashchan.content.async.ExecutorTask;
import com.mishiranu.dashchan.content.model.GalleryItem;
import com.mishiranu.dashchan.ui.gallery.GalleryOverlay;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ProgressDialog;
import java.io.IOException;
import java.util.ArrayList;

/** Cancellable worker snapshot followed by the existing gallery, with no network sources. */
public class CachedMediaDialog extends DialogFragment {
	private ScanViewModel model;
	public static void open(FragmentManager manager) {
		String tag = CachedMediaDialog.class.getName();
		if (!manager.isStateSaved() && !manager.isDestroyed() && manager.findFragmentByTag(tag) == null) {
			new CachedMediaDialog().show(manager, tag);
		}
	}
	@Override public void onCreate(Bundle state) {
		super.onCreate(state);
		model = new ViewModelProvider(this).get(ScanViewModel.class);
		model.result.observe(this, ignored -> openResult());
		model.start();
	}
	@NonNull @Override public ProgressDialog onCreateDialog(Bundle state) {
		ProgressDialog dialog = ProgressDialog.standard(requireContext(), null);
		dialog.setMessage(getString(R.string.cache_browser_loading));
		return dialog;
	}
	@Override public void onResume() { super.onResume(); openResult(); }
	private void openResult() {
		ScanResult result = model.result.getValue();
		if (result == null || model.delivered || !isResumed() || getParentFragmentManager().isStateSaved()) return;
		model.delivered = true;
		if (result.items == null) ClickableToast.show(R.string.cache_is_unavailable);
		else if (result.items.isEmpty()) ClickableToast.show(R.string.cache_browser_empty);
		else {
			GalleryOverlay gallery = new GalleryOverlay(null, result.items, 0, getString(R.string.cache_browser),
					null, GalleryOverlay.NavigatePostMode.DISABLED, true);
			gallery.show(getParentFragmentManager(), GalleryOverlay.class.getName());
		}
		dismiss();
	}
	@Override public void onCancel(@NonNull DialogInterface dialog) { model.cancel(); super.onCancel(dialog); }

	private static final class ScanResult {
		final ArrayList<GalleryItem> items;
		ScanResult(ArrayList<GalleryItem> items) { this.items = items; }
	}
	public static class ScanViewModel extends ViewModel {
		final MutableLiveData<ScanResult> result = new MutableLiveData<>();
		private ExecutorTask<Void, ScanResult> task;
		boolean delivered;
		void start() {
			if (task != null || result.getValue() != null) return;
			task = new ExecutorTask<Void, ScanResult>() {
				@Override protected ScanResult run() throws InterruptedException {
					try { return new ScanResult(CachedMedia.scan(this::isCancelled)); }
					catch (IOException | SecurityException e) { return new ScanResult(null); }
				}
				@Override protected void onComplete(ScanResult value) { task = null; result.setValue(value); }
			};
			task.execute(ConcurrentUtils.SEPARATE_EXECUTOR);
		}
		void cancel() { if (task != null) { task.cancel(); task = null; } }
		@Override protected void onCleared() { cancel(); }
	}
}
