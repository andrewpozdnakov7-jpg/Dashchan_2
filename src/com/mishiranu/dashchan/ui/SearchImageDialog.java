package com.mishiranu.dashchan.ui;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.http.HttpException;
import chan.http.HttpHolder;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.async.HttpHolderTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ProgressDialog;
import java.io.File;
import java.io.IOException;
import org.json.JSONException;

public class SearchImageDialog extends DialogFragment {
	private static final String EXTRA_CHAN_NAME = "chanName";
	private static final String EXTRA_URI = "uri";
	private static final String EXTRA_PREVIEW_URI = "previewUri";

	public SearchImageDialog() {}

	public SearchImageDialog(String chanName, Uri uri) {
		this(chanName, uri, null);
	}

	public SearchImageDialog(String chanName, Uri uri, Uri previewUri) {
		Bundle args = new Bundle();
		args.putString(EXTRA_CHAN_NAME, chanName);
		args.putParcelable(EXTRA_URI, uri);
		args.putParcelable(EXTRA_PREVIEW_URI, previewUri);
		setArguments(args);
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		Context context = requireContext();
		String chanName = requireArguments().getString(EXTRA_CHAN_NAME);
		Uri uri = AndroidUtils.getParcelable(requireArguments(), EXTRA_URI, Uri.class);
		Uri previewUri = AndroidUtils.getParcelable(requireArguments(), EXTRA_PREVIEW_URI, Uri.class);
		Chan chan = Chan.get(chanName);
		uri = chan.locator.convert(uri);
		previewUri = chan.locator.convert(previewUri);
		DialogMenu dialogMenu = new DialogMenu(new ContextThemeWrapper(context, R.style.Theme_Gallery));
		for (ImageSearchProvider provider : ImageSearchProvider.getProviders()) {
			Uri finalUri = uri;
			Uri finalPreviewUri = previewUri;
			dialogMenu.add(provider.titleResId, () -> searchImage(provider, finalUri, finalPreviewUri));
		}
		Uri finalUri = uri;
		Uri finalPreviewUri = previewUri;
		dialogMenu.add(R.string.search_image_with_app,
				() -> searchImageWithApplication(finalUri, finalPreviewUri));
		return dialogMenu.create();
	}

	private void searchImage(ImageSearchProvider provider, Uri imageUri, Uri previewUri) {
		if (provider.requiresUpload(imageUri, previewUri)) {
			new YandexSearchDialog(imageUri, previewUri).show(getParentFragmentManager(),
					YandexSearchDialog.class.getName());
		} else {
			searchImageUri(provider.buildSearchUri(imageUri, previewUri));
		}
	}

	private void searchImageUri(Uri searchUri) {
		NavigationUtils.handleUri(requireContext(), null, searchUri, NavigationUtils.BrowserType.EXTERNAL);
	}

	private void searchImageWithApplication(Uri imageUri, Uri previewUri) {
		CacheManager cacheManager = CacheManager.getInstance();
		File file = cacheManager.getMediaFile(imageUri, false);
		String fileName = imageUri.getLastPathSegment();
		if (file == null && previewUri != null) {
			String thumbnailKey = cacheManager.getCachedFileKey(previewUri);
			File thumbnailFile = cacheManager.getThumbnailFile(thumbnailKey);
			if (thumbnailFile != null && thumbnailFile.isFile()) {
				file = thumbnailFile;
				fileName = "image-search.png";
			}
		}
		if (file == null) {
			ClickableToast.show(R.string.cache_is_unavailable);
			return;
		}
		if (fileName == null) {
			fileName = "image-search.jpg";
		}
		NavigationUtils.shareFile(requireContext(), file, fileName);
	}

	public static class YandexSearchDialog extends DialogFragment {
		private static final String EXTRA_URI = "uri";
		private static final String EXTRA_PREVIEW_URI = "previewUri";

		public YandexSearchDialog() {}

		public YandexSearchDialog(Uri uri, Uri previewUri) {
			Bundle args = new Bundle();
			args.putParcelable(EXTRA_URI, uri);
			args.putParcelable(EXTRA_PREVIEW_URI, previewUri);
			setArguments(args);
		}

		@NonNull
		@Override
		public ProgressDialog onCreateDialog(Bundle savedInstanceState) {
			ProgressDialog dialog = new ProgressDialog(requireContext(), null);
			dialog.setMessage(getString(R.string.uploading_image__ellipsis));
			return dialog;
		}

		@Override
		public void onActivityCreated(Bundle savedInstanceState) {
			super.onActivityCreated(savedInstanceState);
			YandexSearchViewModel viewModel = new ViewModelProvider(this).get(YandexSearchViewModel.class);
			if (!viewModel.hasTaskOrValue()) {
				Bundle args = requireArguments();
				Uri uri = AndroidUtils.getParcelable(args, EXTRA_URI, Uri.class);
				Uri previewUri = AndroidUtils.getParcelable(args, EXTRA_PREVIEW_URI, Uri.class);
				YandexSearchTask task = new YandexSearchTask(viewModel, uri, previewUri);
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
				viewModel.attach(task);
			}
			viewModel.observe(this, result -> {
				dismiss();
				if (result.searchUri != null) {
					NavigationUtils.handleUri(requireContext(), null, result.searchUri,
							NavigationUtils.BrowserType.EXTERNAL);
				} else {
					ClickableToast.show(result.errorItem);
				}
			});
		}
	}

	public static class YandexSearchViewModel extends TaskViewModel<YandexSearchTask, YandexSearchResult> {}

	private static class YandexSearchResult {
		public final Uri searchUri;
		public final ErrorItem errorItem;

		private YandexSearchResult(Uri searchUri, ErrorItem errorItem) {
			this.searchUri = searchUri;
			this.errorItem = errorItem;
		}
	}

	private static class YandexSearchTask extends HttpHolderTask<Void, YandexSearchResult> {
		private final YandexSearchViewModel viewModel;
		private final Uri imageUri;
		private final Uri previewUri;

		public YandexSearchTask(YandexSearchViewModel viewModel, Uri imageUri, Uri previewUri) {
			super(Chan.getFallback());
			this.viewModel = viewModel;
			this.imageUri = imageUri;
			this.previewUri = previewUri;
		}

		@Override
		protected YandexSearchResult run(HttpHolder holder) {
			FileHolder fileHolder = obtainFileHolder(imageUri, previewUri);
			if (fileHolder == null) {
				return new YandexSearchResult(null, new ErrorItem(R.string.cache_is_unavailable));
			}
			try {
				return new YandexSearchResult(YandexImageSearch.upload(holder, fileHolder), null);
			} catch (HttpException e) {
				return new YandexSearchResult(null, e.getErrorItemAndHandle());
			} catch (IOException e) {
				return new YandexSearchResult(null, new ErrorItem(ErrorItem.Type.INVALID_DATA_FORMAT));
			} catch (JSONException | IllegalArgumentException e) {
				return new YandexSearchResult(null, new ErrorItem(ErrorItem.Type.INVALID_RESPONSE));
			}
		}

		@Override
		protected void onComplete(YandexSearchResult result) {
			viewModel.handleResult(result);
		}

		private static FileHolder obtainFileHolder(Uri imageUri, Uri previewUri) {
			FileHolder fileHolder = FileHolder.obtainForStreaming(imageUri);
			CacheManager cacheManager = CacheManager.getInstance();
			if (fileHolder == null) {
				File mediaFile = cacheManager.getMediaFile(imageUri, false);
				if (mediaFile != null && mediaFile.isFile()) {
					fileHolder = FileHolder.obtain(mediaFile);
				}
			}
			if (fileHolder == null && previewUri != null) {
				fileHolder = FileHolder.obtainForStreaming(previewUri);
			}
			if (fileHolder == null && previewUri != null) {
				String thumbnailKey = cacheManager.getCachedFileKey(previewUri);
				File thumbnailFile = cacheManager.getThumbnailFile(thumbnailKey);
				if (thumbnailFile != null && thumbnailFile.isFile()) {
					fileHolder = FileHolder.obtain(thumbnailFile);
				}
			}
			return fileHolder;
		}
	}
}
