package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ExecutorTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import com.mishiranu.dashchan.widget.ProgressDialog;

public class MediaFragment extends PreferenceFragment {
	private static final String RESULT_CACHE_SIZE_CHANGED = "mediaCacheSizeChanged";

	private Preference<?> clearCachePreference;
	private long lastCacheSize = Long.MIN_VALUE;
	private final Runnable refreshCacheSize = new Runnable() {
		@Override
		public void run() {
			if (clearCachePreference == null) return;
			long size = CacheManager.getInstance().getCacheSize();
			if (lastCacheSize != size) {
				lastCacheSize = size;
				clearCachePreference.invalidate();
			}
			// Observe only while this screen is started; also catches a later remount/rescan.
			ConcurrentUtils.HANDLER.postDelayed(this, 500);
		}
	};

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		addButton(R.string.media_preload, R.string.media_preload_summary).setOnClickListener(p ->
				((FragmentHandler) requireActivity()).pushFragment(new VideoPreloadFragment()));
		addButton(R.string.gallery, R.string.media_gallery_summary).setOnClickListener(p ->
				((FragmentHandler) requireActivity()).pushFragment(new GallerySettingsFragment()));
		addButton(R.string.media_images_attachments, R.string.media_images_attachments_summary).setOnClickListener(p ->
				((FragmentHandler) requireActivity()).pushFragment(new ImageAttachmentsFragment()));
		addButton(R.string.downloads, R.string.media_downloads_summary).setOnClickListener(p ->
				((FragmentHandler) requireActivity()).pushFragment(new DownloadsFragment()));
		addButton(R.string.video_player, R.string.media_video_player_summary).setOnClickListener(p ->
				((FragmentHandler) requireActivity()).pushFragment(new VideoPlayerSettingsFragment()));

		addHeader(R.string.additional);
		addSeek(Preferences.KEY_CACHE_SIZE, Preferences.DEFAULT_CACHE_SIZE, getString(R.string.cache_size), "%d MB",
				null, Preferences.MIN_CACHE_SIZE, Preferences.MAX_CACHE_SIZE, Preferences.STEP_CACHE_SIZE);
		clearCachePreference = addButton(getString(R.string.clear_cache),
				p -> {
					long size = CacheManager.getInstance().getCacheSize();
					return size == -1L ? getString(R.string.cache_size_calculating)
							: size < 0 ? getString(R.string.cache_size_unavailable)
							: StringUtils.formatFileSizeMegabytes(size);
				});
		clearCachePreference.setOnClickListener(p -> {
			ClearCacheDialog dialog = new ClearCacheDialog();
			dialog.show(getChildFragmentManager(), ClearCacheDialog.class.getName());
		});
		clearCachePreference.invalidate();
		getParentFragmentManager().setFragmentResultListener(RESULT_CACHE_SIZE_CHANGED,
				getViewLifecycleOwner(), (requestKey, result) -> clearCachePreference.invalidate());
	}

	@Override
	public void onStart() {
		super.onStart();
		ConcurrentUtils.HANDLER.removeCallbacks(refreshCacheSize);
		lastCacheSize = Long.MIN_VALUE;
		refreshCacheSize.run();
	}

	@Override
	public void onStop() {
		ConcurrentUtils.HANDLER.removeCallbacks(refreshCacheSize);
		super.onStop();
	}

	@Override
	public void onDestroyView() {
		ConcurrentUtils.HANDLER.removeCallbacks(refreshCacheSize);
		super.onDestroyView();
		clearCachePreference = null;
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.media), null);
	}

	public static class ClearCacheDialog extends DialogFragment {
		private static final String EXTRA_CHECKED_ITEMS = "checkedItems";

		private boolean[] checkedItems;

		@NonNull
		@Override
		public AlertDialog onCreateDialog(Bundle savedInstanceState) {
			checkedItems = savedInstanceState != null ? savedInstanceState.getBooleanArray(EXTRA_CHECKED_ITEMS) : null;
			if (checkedItems == null) {
				checkedItems = new boolean[] {true, true};
			}
			String[] items = {getString(R.string.thumbnails), getString(R.string.cached_files)};
			return MotionDialogBuilder.standard(requireContext())
					.setTitle(getString(R.string.clear_cache))
					.setMultiChoiceItems(items, checkedItems, (d, which, isChecked) -> checkedItems[which] = isChecked)
					.setPositiveButton(android.R.string.ok, (d, w) -> {
						ClearingDialog clearingDialog = new ClearingDialog(checkedItems[0], checkedItems[1]);
						clearingDialog.show(getParentFragment().getParentFragmentManager(),
								ClearingDialog.class.getName());
					})
					.setNegativeButton(android.R.string.cancel, null)
					.create();
		}

		@Override
		public void onSaveInstanceState(@NonNull Bundle outState) {
			super.onSaveInstanceState(outState);
			outState.putBooleanArray(EXTRA_CHECKED_ITEMS, checkedItems);
		}
	}

	public static class ClearingDialog extends DialogFragment {
		private static final String EXTRA_THUMBNAILS = "thumbnails";
		private static final String EXTRA_MEDIA = "media";
		private boolean taskInitialized;

		public ClearingDialog() {}

		public ClearingDialog(boolean thumbnails, boolean media) {
			Bundle args = new Bundle();
			args.putBoolean(EXTRA_THUMBNAILS, thumbnails);
			args.putBoolean(EXTRA_MEDIA, media);
			setArguments(args);
		}

		@NonNull
		@Override
		public ProgressDialog onCreateDialog(Bundle savedInstanceState) {
			ProgressDialog dialog = ProgressDialog.standard(requireContext(), null);
			dialog.setMessage(getString(R.string.clearing__ellipsis));
			return dialog;
		}

		@Override
		public void onStart() {
			super.onStart();
			// Do not add another observer or restart a completed task when returning to the dialog.
			if (taskInitialized) {
				return;
			}
			taskInitialized = true;

			ClearCacheViewModel viewModel = new ViewModelProvider(this).get(ClearCacheViewModel.class);
			if (!viewModel.hasTaskOrValue()) {
				Bundle args = requireArguments();
				boolean thumbnails = args.getBoolean(EXTRA_THUMBNAILS);
				boolean media = args.getBoolean(EXTRA_MEDIA);
				ClearCacheTask task = new ClearCacheTask(viewModel, thumbnails, media);
				task.execute(ConcurrentUtils.SEPARATE_EXECUTOR);
				viewModel.attach(task);
			}
			viewModel.observe(this, result -> {
				dismiss();
				sendUpdateCacheSize();
			});
		}

		private void sendUpdateCacheSize() {
			getParentFragmentManager().setFragmentResult(RESULT_CACHE_SIZE_CHANGED, new Bundle());
		}

		@Override
		public void onCancel(@NonNull DialogInterface dialog) {
			super.onCancel(dialog);
			sendUpdateCacheSize();
		}
	}

	public static class ClearCacheViewModel extends TaskViewModel<ClearCacheTask, Object> {}

	private static class ClearCacheTask extends ExecutorTask<Void, Object> {
		private final ClearCacheViewModel viewModel;
		private final boolean thumbnails;
		private final boolean media;

		public ClearCacheTask(ClearCacheViewModel viewModel, boolean thumbnails, boolean media) {
			this.viewModel = viewModel;
			this.thumbnails = thumbnails;
			this.media = media;
		}

		@Override
		protected Void run() throws InterruptedException {
			if (thumbnails) {
				CacheManager.getInstance().eraseThumbnailsCache();
			}
			if (isCancelled()) {
				return null;
			}
			if (media) {
				CacheManager.getInstance().eraseMediaCache();
			}
			return null;
		}

		@Override
		protected void onComplete(Object result) {
			viewModel.handleResult(this);
		}
	}
}
