package com.mishiranu.dashchan.content;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import chan.content.Chan;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.model.FileHolder;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.ui.MainActivity;
import com.mishiranu.dashchan.util.AndroidUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PostingShareActivity extends Activity {
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
		return uri != null && ("content".equalsIgnoreCase(uri.getScheme())
				|| "file".equalsIgnoreCase(uri.getScheme()));
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
		DraftsStorage draftsStorage = DraftsStorage.getInstance();
		Intent intent = getIntent();
		ArrayList<Uri> uris = collectStreamUris(intent);
		String sharedText = collectSharedText(intent);
		Uri contentUri = findChanUri(sharedText);

		int success = 0;
		if (!uris.isEmpty()) {
			for (Uri uri : uris) {
				FileHolder fileHolder = FileHolder.obtain(uri);
				if (fileHolder != null && draftsStorage.storeFuture(fileHolder)) {
					success++;
				}
			}
		}

		if (success > 0) {
			Preferences.storeFuturePostText(sharedText);
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
}
