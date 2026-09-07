package com.mishiranu.dashchan.content;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.async.ExecutorTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PostingShareActivity extends ComponentActivity {
	private static final Pattern PATTERN_HREF = Pattern.compile("<a\\s+[^>]*href=([\"'])(.*?)\\1[^>]*>");

	private static void addTextPart(LinkedHashSet<String> parts, CharSequence text) {
		String value = text != null ? StringUtils.nullIfEmpty(text.toString().trim()) : null;
		if (value != null) {
			parts.add(value);
		}
	}

	private static boolean isWebUri(Uri uri) {
		return uri != null && ("http".equalsIgnoreCase(uri.getScheme())
				|| "https".equalsIgnoreCase(uri.getScheme()));
	}

	private static boolean isStreamUri(Uri uri) {
		return uri != null && "content".equalsIgnoreCase(uri.getScheme());
	}

	private static ArrayList<Uri> collectStreamUris(Intent intent) {
		LinkedHashSet<Uri> uris = new LinkedHashSet<>();
		if (Intent.ACTION_SEND.equals(intent.getAction())) {
			Uri uri = AndroidUtils.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
			if (uri != null) {
				uris.add(uri);
			}
		} else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
			ArrayList<Uri> extraUris = AndroidUtils.getParcelableArrayListExtra(intent,
					Intent.EXTRA_STREAM, Uri.class);
			if (extraUris != null) {
				uris.addAll(extraUris);
			}
		}
		ClipData clipData = intent.getClipData();
		if (clipData != null) {
			for (int i = 0; i < clipData.getItemCount(); i++) {
				Uri uri = clipData.getItemAt(i).getUri();
				if (isStreamUri(uri)) {
					uris.add(uri);
				}
			}
		}
		Uri data = intent.getData();
		if (isStreamUri(data)) {
			uris.add(data);
		}
		return new ArrayList<>(uris);
	}

	private static String collectSharedText(Intent intent) {
		LinkedHashSet<String> parts = new LinkedHashSet<>();
		addTextPart(parts, intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT));
		addTextPart(parts, intent.getCharSequenceExtra(Intent.EXTRA_TEXT));
		ClipData clipData = intent.getClipData();
		if (clipData != null) {
			for (int i = 0; i < clipData.getItemCount(); i++) {
				ClipData.Item item = clipData.getItemAt(i);
				addTextPart(parts, item.getText());
				Uri uri = item.getUri();
				if (isWebUri(uri)) {
					addTextPart(parts, uri.toString());
				}
			}
		}
		Uri data = intent.getData();
		if (isWebUri(data)) {
			addTextPart(parts, data.toString());
		}
		StringBuilder builder = new StringBuilder();
		for (String part : parts) {
			if (builder.length() > 0) {
				builder.append('\n');
			}
			builder.append(part);
		}
		return StringUtils.nullIfEmpty(builder.toString());
	}

	private static Uri findChanUri(String text) {
		if (text == null) {
			return null;
		}
		Matcher matcher = PATTERN_HREF.matcher(StringUtils.linkify(text));
		while (matcher.find()) {
			Uri uri = Uri.parse(matcher.group(2));
			Chan chan = Chan.getPreferred(null, uri);
			if (chan.name != null && (chan.locator.safe(false).isBoardUri(uri)
					|| chan.locator.safe(false).isThreadUri(uri) || chan.locator.isImageUri(uri)
					|| chan.locator.isAudioUri(uri) || chan.locator.isVideoUri(uri))) {
				return uri;
			}
		}
		return null;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		FrameLayout layout = new FrameLayout(this);
		ProgressBar progressBar = new ProgressBar(this);
		layout.addView(progressBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		setContentView(layout);

		Intent intent = getIntent();
		ArrayList<Uri> uris = (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
				? collectStreamUris(intent) : new ArrayList<>();
		String sharedText = collectSharedText(intent);
		Uri contentUri = findChanUri(sharedText);

		if (!uris.isEmpty()) {
			ImportViewModel viewModel = new ViewModelProvider(this).get(ImportViewModel.class);
			viewModel.observe(this, result -> completeShare(result.success, result.uris,
					result.sharedText, result.contentUri));
			if (!viewModel.hasTaskOrValue()) {
				ImportTask task = new ImportTask(viewModel, uris, sharedText, contentUri);
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
				viewModel.attach(task);
			}
		} else {
			completeShare(0, uris, sharedText, contentUri);
		}
	}

	private void completeShare(int success, ArrayList<Uri> uris, String sharedText, Uri contentUri) {
		if (success > 0) {
			// Browsers commonly attach the source URL as EXTRA_TEXT when sharing an image.
			// It describes the attachment rather than a message the user entered. Keep
			// text importing for text-only shares, including fallback for invalid streams.
			Preferences.storeFuturePostText(uris.isEmpty() ? sharedText : null);
			Toast.makeText(this, R.string.draft_saved, Toast.LENGTH_SHORT).show();
		} else if (contentUri != null) {
			startActivity(new Intent(this, MainActivity.class).setData(contentUri));
		} else if (sharedText != null) {
			Preferences.storeFuturePostText(sharedText);
			startActivity(new Intent(this, MainActivity.class).setAction(C.ACTION_POSTING_SHARE));
		} else {
			Toast.makeText(this, R.string.unknown_address, Toast.LENGTH_SHORT).show();
		}
		finish();
	}

	private static class ImportResult {
		public final int success;
		public final ArrayList<Uri> uris;
		public final String sharedText;
		public final Uri contentUri;

		public ImportResult(int success, ArrayList<Uri> uris, String sharedText, Uri contentUri) {
			this.success = success;
			this.uris = uris;
			this.sharedText = sharedText;
			this.contentUri = contentUri;
		}
	}

	private static class ImportTask extends ExecutorTask<Void, ArrayList<DraftsStorage.AttachmentDraft>> {
		private final ImportViewModel viewModel;
		private final ArrayList<Uri> uris;
		private final String sharedText;
		private final Uri contentUri;

		public ImportTask(ImportViewModel viewModel, ArrayList<Uri> uris, String sharedText, Uri contentUri) {
			this.viewModel = viewModel;
			this.uris = uris;
			this.sharedText = sharedText;
			this.contentUri = contentUri;
		}

		@Override
		protected ArrayList<DraftsStorage.AttachmentDraft> run() {
			DraftsStorage draftsStorage = DraftsStorage.getInstance();
			ArrayList<DraftsStorage.AttachmentDraft> attachmentDrafts = new ArrayList<>();
			for (Uri uri : uris) {
				if (isCancelled()) break;
				FileHolder fileHolder = FileHolder.obtainForStreaming(uri);
				if (fileHolder != null) {
					DraftsStorage.AttachmentDraft attachmentDraft =
							draftsStorage.prepareFutureAttachmentDraft(fileHolder);
					if (attachmentDraft != null) attachmentDrafts.add(attachmentDraft);
				}
			}
			return attachmentDrafts;
		}

		@Override
		protected void onComplete(ArrayList<DraftsStorage.AttachmentDraft> attachmentDrafts) {
			if (!attachmentDrafts.isEmpty()) {
				DraftsStorage.getInstance().storeFutureAttachmentDrafts(attachmentDrafts);
			}
			viewModel.handleResult(new ImportResult(attachmentDrafts.size(), uris, sharedText, contentUri));
		}

		@Override
		protected void onCancel(ArrayList<DraftsStorage.AttachmentDraft> attachmentDrafts) {
			if (attachmentDrafts != null && !attachmentDrafts.isEmpty()) {
				DraftsStorage.getInstance().discardPreparedAttachmentDrafts(attachmentDrafts);
			}
		}
	}

	public static class ImportViewModel extends TaskViewModel<ImportTask, ImportResult> {}
}
