package com.mishiranu.dashchan.widget;

import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.Selection;
import android.text.Spanned;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.core.content.MimeTypeFilter;
import androidx.core.view.ContentInfoCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;

import com.mishiranu.dashchan.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// Rich-content paste handling adapted from TrixiEther/DashchanFork.
public class UriPasteEditText extends SafePasteEditText implements ActionMode.Callback {
	public enum PasteResult {
		ACCEPTED,
		FILES_LIMIT_REACHED,
		IMPORT_IN_PROGRESS,
		FAILED
	}

	public interface Callback {
		PasteResult onUrisWithAllowedMimeTypePasted(List<UriContent> uriContents);
	}

	public static final class UriContent {
		private final Uri uri;
		private Object permissionOwner;
		private final AtomicBoolean released = new AtomicBoolean(false);

		private UriContent(Uri uri, Object permissionOwner) {
			this.uri = uri;
			this.permissionOwner = permissionOwner;
		}

		public Uri getUri() {
			return uri;
		}

		public void releasePermission() {
			if (!released.getAndSet(true)) {
				// Keeping the receive-content payload reachable keeps transient URI grants alive.
				permissionOwner = null;
			}
		}
	}

	private Callback callback;
	private String[] allowedUriMimeTypes;
	private ActionMode actionMode;
	private ActionMode.Callback actionModeCallbackDelegate;

	public UriPasteEditText(Context context) {
		super(context);
	}

	public UriPasteEditText(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public UriPasteEditText(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@SuppressWarnings("unused")
	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	public UriPasteEditText(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
	}

	public void setCallback(Callback callback, List<String> allowedUriMimeTypes) {
		this.callback = callback;
		this.allowedUriMimeTypes = allowedUriMimeTypes != null
				? allowedUriMimeTypes.toArray(new String[0]) : null;
		boolean enabled = uriPasteAllowed();
		ViewCompat.setOnReceiveContentListener(this, enabled ? this.allowedUriMimeTypes : null,
				enabled ? (view, payload) -> handleContent(payload) : null);
	}

	@Override
	public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
		InputConnection inputConnection = super.onCreateInputConnection(outAttrs);
		String[] mimeTypes = ViewCompat.getOnReceiveContentMimeTypes(this);
		if (inputConnection != null && mimeTypes != null) {
			EditorInfoCompat.setContentMimeTypes(outAttrs, mimeTypes);
			inputConnection = InputConnectionCompat.createWrapper(this, inputConnection, outAttrs);
		}
		return inputConnection;
	}

	@Override
	public boolean onTextContextMenuItem(int id) {
		if (id == android.R.id.paste && uriPasteAllowed()) {
			try {
				ClipboardManager clipboardManager = (ClipboardManager) getContext()
						.getSystemService(Context.CLIPBOARD_SERVICE);
				ClipData clipData = clipboardManager != null ? clipboardManager.getPrimaryClip() : null;
				if (clipData != null && clipData.getItemCount() > 0) {
					ContentInfoCompat payload = new ContentInfoCompat.Builder(clipData,
							ContentInfoCompat.SOURCE_CLIPBOARD).build();
					ContentInfoCompat remaining = handleContent(payload);
					if (remaining != payload) {
						if (remaining != null) {
							insertRemainingText(remaining);
						}
						cancelActionMode();
						return true;
					}
				}
			} catch (RuntimeException e) {
				ClickableToast.show(R.string.unknown_error);
				cancelActionMode();
				return true;
			}
		}
		return super.onTextContextMenuItem(id);
	}

	private ContentInfoCompat handleContent(ContentInfoCompat payload) {
		Pair<ContentInfoCompat, ContentInfoCompat> partition =
				payload.partition(item -> item.getUri() != null);
		ContentInfoCompat uriPayload = partition.first;
		if (uriPayload == null) {
			return payload;
		}
		ClipData clipData = uriPayload.getClip();
		ClipDescription description = clipData.getDescription();
		ArrayList<UriContent> uriContents = new ArrayList<>(clipData.getItemCount());
		boolean unsupported = false;
		for (int i = 0; i < clipData.getItemCount(); i++) {
			Uri uri = clipData.getItemAt(i).getUri();
			boolean allowed = false;
			if (uri != null) {
				try {
					allowed = isMimeTypeAllowed(uri, description);
				} catch (RuntimeException e) {
					// Treat inaccessible provider metadata as an unsupported item.
				}
			}
			if (allowed) {
				uriContents.add(new UriContent(uri, payload));
			} else {
				unsupported = true;
			}
		}
		if (unsupported) {
			ClickableToast.show(R.string.file_format_is_not_supported);
		}
		if (!uriContents.isEmpty()) {
			handleUriContents(uriContents);
		}
		return partition.second;
	}

	private void handleUriContents(List<UriContent> uriContents) {
		PasteResult result;
		try {
			result = callback.onUrisWithAllowedMimeTypePasted(uriContents);
		} catch (RuntimeException e) {
			result = PasteResult.FAILED;
		}
		switch (result) {
			case ACCEPTED: {
				break;
			}
			case FILES_LIMIT_REACHED: {
				releasePermissions(uriContents);
				ClickableToast.show(R.string.files_limit_reached);
				break;
			}
			case IMPORT_IN_PROGRESS: {
				releasePermissions(uriContents);
				ClickableToast.show(R.string.processing_data__ellipsis);
				break;
			}
			case FAILED: {
				releasePermissions(uriContents);
				ClickableToast.show(R.string.unknown_error);
				break;
			}
		}
		cancelActionMode();
	}

	private static void releasePermissions(List<UriContent> uriContents) {
		for (UriContent uriContent : uriContents) {
			uriContent.releasePermission();
		}
	}

	private boolean isMimeTypeAllowed(Uri uri, ClipDescription description) {
		ContentResolver contentResolver = getContext().getContentResolver();
		String mimeType = contentResolver.getType(uri);
		if (mimeType != null) {
			return MimeTypeFilter.matches(mimeType, allowedUriMimeTypes) != null;
		}
		if (description != null) {
			for (int i = 0; i < description.getMimeTypeCount(); i++) {
				if (MimeTypeFilter.matches(description.getMimeType(i), allowedUriMimeTypes) != null) {
					return true;
				}
			}
		}
		return false;
	}

	private void insertRemainingText(ContentInfoCompat payload) {
		ClipData clipData = payload.getClip();
		Editable editable = getEditableText();
		int selectionStart = Selection.getSelectionStart(editable);
		int selectionEnd = Selection.getSelectionEnd(editable);
		int start = Math.max(0, Math.min(selectionStart, selectionEnd));
		int end = Math.max(0, Math.max(selectionStart, selectionEnd));
		boolean first = true;
		for (int i = 0; i < clipData.getItemCount(); i++) {
			ClipData.Item item = clipData.getItemAt(i);
			CharSequence text = (payload.getFlags() & ContentInfoCompat.FLAG_CONVERT_TO_PLAIN_TEXT) != 0
					? item.coerceToText(getContext()) : item.coerceToStyledText(getContext());
			if (text == null) {
				continue;
			}
			if (text instanceof Spanned) {
				text = text.toString();
			}
			if (first) {
				Selection.setSelection(editable, end);
				editable.replace(start, end, text);
				Selection.setSelection(editable, Math.min(editable.length(), start + text.length()));
				first = false;
			} else {
				int position = Selection.getSelectionEnd(editable);
				if (position < 0) {
					position = editable.length();
				}
				editable.insert(position, "\n");
				position++;
				editable.insert(position, text);
				Selection.setSelection(editable, Math.min(editable.length(), position + text.length()));
			}
		}
	}

	private boolean uriPasteAllowed() {
		return callback != null && allowedUriMimeTypes != null && allowedUriMimeTypes.length > 0;
	}

	private void cancelActionMode() {
		if (actionMode != null) {
			actionMode.finish();
		}
	}

	@Override
	public ActionMode startActionMode(ActionMode.Callback callback) {
		actionModeCallbackDelegate = callback;
		actionMode = super.startActionMode(this);
		return actionMode;
	}

	@Override
	public ActionMode startActionMode(ActionMode.Callback callback, int type) {
		actionModeCallbackDelegate = callback;
		actionMode = super.startActionMode(this, type);
		return actionMode;
	}

	@Override
	public boolean onCreateActionMode(ActionMode mode, Menu menu) {
		return actionModeCallbackDelegate != null && actionModeCallbackDelegate.onCreateActionMode(mode, menu);
	}

	@Override
	public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
		return actionModeCallbackDelegate != null && actionModeCallbackDelegate.onPrepareActionMode(mode, menu);
	}

	@Override
	public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
		return actionModeCallbackDelegate != null && actionModeCallbackDelegate.onActionItemClicked(mode, item);
	}

	@Override
	public void onDestroyActionMode(ActionMode mode) {
		if (actionModeCallbackDelegate != null) {
			actionModeCallbackDelegate.onDestroyActionMode(mode);
		}
		actionMode = null;
		actionModeCallbackDelegate = null;
	}
}
