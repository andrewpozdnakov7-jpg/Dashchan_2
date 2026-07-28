package com.mishiranu.dashchan.ui;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import chan.content.Chan;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import java.io.File;

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
			Uri searchUri = provider.buildSearchUri(uri, previewUri);
			dialogMenu.add(provider.title, () -> searchImageUri(searchUri));
		}
		Uri finalUri = uri;
		Uri finalPreviewUri = previewUri;
		dialogMenu.add(R.string.search_image_with_app,
				() -> searchImageWithApplication(finalUri, finalPreviewUri));
		return dialogMenu.create();
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
}
